package com.skillforge.server.history.query;

import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryCanonicalSelector;
import com.skillforge.server.history.HistoryCursorCodec;
import com.skillforge.server.history.HistoryProtocolException;
import com.skillforge.server.history.HistoryRefCodec;
import com.skillforge.server.history.HistoryUnicode;
import com.skillforge.server.history.SessionHistoryReadInput;
import com.skillforge.server.history.SessionHistoryReadResponse;
import com.skillforge.server.history.SessionHistorySearchInput;
import com.skillforge.server.history.SessionHistorySearchResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Read-only Search/Read implementation over an owner-scoped frozen row-store snapshot. */
@Service
public class SessionHistoryQueryService {

    private static final int SCHEMA_VERSION = 1;
    private static final int DEFAULT_SEARCH_LIMIT = 8;
    private static final int MAX_SCAN_ROWS_PER_SOURCE = 100_000;
    private static final int PREVIEW_CODE_POINTS = 240;
    private static final int EVENT_PAGE_MAX_EVENTS = 20;
    private static final int EVENT_PAGE_MAX_CODE_POINTS = 8_000;

    private static final Comparator<HistoryEvidence> SEARCH_ORDER = Comparator
            .comparingInt((HistoryEvidence value) -> value.evidenceClass().ordinal())
            .thenComparing(HistoryEvidence::logicalSeq, Comparator.reverseOrder())
            .thenComparing(HistoryEvidence::createdAt, Comparator.reverseOrder())
            .thenComparingInt(HistoryEvidence::sourceOrder)
            .thenComparing(HistoryEvidence::ref);
    private static final Comparator<HistoryEvidence> TIMELINE_ORDER = Comparator
            .comparingLong(HistoryEvidence::logicalSeq)
            .thenComparing(HistoryEvidence::createdAt)
            .thenComparingInt(HistoryEvidence::sourceOrder)
            .thenComparing(HistoryEvidence::ref);

    private final HistoryQueryStore store;
    private final HistoryEvidenceMaterializer materializer;
    private final HistoryCanonicalSelector selector;
    private final HistoryCursorCodec cursorCodec;
    private final HistoryRefCodec refCodec;

    public SessionHistoryQueryService(
            HistoryQueryStore store,
            HistoryEvidenceMaterializer materializer,
            HistoryCanonicalSelector selector,
            HistoryCursorCodec cursorCodec,
            HistoryRefCodec refCodec) {
        this.store = Objects.requireNonNull(store, "store");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
        this.refCodec = Objects.requireNonNull(refCodec, "refCodec");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionHistorySearchResponse search(
            CurrentSessionHistoryScope scope,
            SessionHistorySearchInput input) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(input, "input");
        requireQueryableScope(scope);
        String selectorHash = selector.hash(input);
        HistoryCursorCodec.SearchCursor continuation = input.cursor() == null
                ? null : cursorCodec.decodeSearch(
                        scope, input.cursor(), selectorHash, HistoryAuthorizedProjection.VERSION);
        HistoryCursorCodec.SnapshotCutoff cutoff = continuation == null
                ? store.captureCutoff(scope) : continuation.cutoff();
        HistoryEvidenceMaterializer.Materialized materialized = loadEvidence(scope, cutoff,
                HistoryQueryStore.Selection.range(input.seqFrom(), input.seqTo(), false));
        List<HistoryEvidence> orderedMatches = materialized.evidence().stream()
                .filter(value -> matches(input, value))
                .sorted(SEARCH_ORDER)
                .toList();
        if (continuation != null && (continuation.position() == null
                || orderedMatches.stream().noneMatch(
                        value -> samePosition(value, continuation.position())))) {
            throw invalidCursor();
        }
        List<HistoryEvidence> matches = orderedMatches.stream()
                .filter(value -> continuation == null || isAfter(value, continuation.position()))
                .toList();

        int limit = input.limit() == null ? DEFAULT_SEARCH_LIMIT : input.limit();
        List<HistoryEvidence> page = matches.stream().limit(limit).toList();
        boolean more = matches.size() > page.size();
        String nextCursor = null;
        if (more && !page.isEmpty()) {
            HistoryEvidence last = page.get(page.size() - 1);
            nextCursor = cursorCodec.encodeSearch(scope, new HistoryCursorCodec.SearchCursor(
                    selectorHash, HistoryAuthorizedProjection.VERSION, cutoff,
                    searchWatermarks(cutoff, matches.subList(page.size(), matches.size())),
                    position(last)));
        }
        return new SessionHistorySearchResponse(
                SCHEMA_VERSION, page.stream().map(this::locator).toList(),
                nextCursor, !more);
    }

    private void validateReadRefs(
            CurrentSessionHistoryScope scope,
            SessionHistoryReadInput input) {
        if (input.arm() == SessionHistoryReadInput.SelectorArm.REFS) {
            for (String ref : input.refs()) refCodec.parse(ref, scope);
            return;
        }
        if (input.arm() == SessionHistoryReadInput.SelectorArm.ARCHIVE) {
            HistoryRefCodec.HistoryRef parsed = refCodec.parse(input.archiveRef(), scope);
            if (parsed instanceof HistoryRefCodec.SummaryRef) {
                throw new HistoryProtocolException("INVALID_REF", "Invalid History reference");
            }
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionHistoryReadResponse read(
            CurrentSessionHistoryScope scope,
            SessionHistoryReadInput input) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(input, "input");
        validateReadRefs(scope, input);
        requireQueryableScope(scope);
        String selectorHash = selector.hash(input);
        return input.arm() == SessionHistoryReadInput.SelectorArm.ARCHIVE
                ? readBlock(scope, input, selectorHash)
                : readEvents(scope, input, selectorHash);
    }

    private SessionHistoryReadResponse readBlock(
            CurrentSessionHistoryScope scope,
            SessionHistoryReadInput input,
            String selectorHash) {
        HistoryCursorCodec.BlockReadCursor continuation = input.cursor() == null
                ? null : cursorCodec.decodeBlock(
                        scope, input.cursor(), selectorHash, HistoryAuthorizedProjection.VERSION);
        HistoryCursorCodec.SnapshotCutoff cutoff = continuation == null
                ? store.captureCutoff(scope) : continuation.cutoff();
        HistoryEvidenceMaterializer.Materialized materialized = loadReadEvidence(scope, cutoff, input);
        String targetRef = input.archiveRef();
        HistoryEvidence target = byRef(materialized, targetRef);
        if (continuation != null) {
            if (!continuation.targetRef().equals(targetRef)) throw invalidCursor();
            cursorCodec.requireBlockContent(continuation, target.authorizedContentHash());
        }
        int offset = continuation == null ? input.offset() : continuation.codePointOffset();
        int length = HistoryUnicode.codePointLength(target.content());
        if (offset > length) throw invalidCursor();
        int count = Math.min(input.maxChars(), length - offset);
        String content = HistoryUnicode.sliceByCodePoints(target.content(), offset, count);
        int nextOffset = offset + count;
        boolean complete = nextOffset == length;
        String cursor = complete ? null : cursorCodec.encodeBlock(
                scope, new HistoryCursorCodec.BlockReadCursor(
                        selectorHash, HistoryAuthorizedProjection.VERSION, cutoff, targetRef,
                        target.authorizedContentHash(), nextOffset));
        return new SessionHistoryReadResponse(
                SCHEMA_VERSION,
                List.of(event(target, content, offset, complete)), cursor, !complete);
    }

    private SessionHistoryReadResponse readEvents(
            CurrentSessionHistoryScope scope,
            SessionHistoryReadInput input,
            String selectorHash) {
        HistoryCursorCodec.EventReadCursor continuation = input.cursor() == null
                ? null : cursorCodec.decodeEvent(
                        scope, input.cursor(), selectorHash, HistoryAuthorizedProjection.VERSION);
        HistoryCursorCodec.SnapshotCutoff cutoff = continuation == null
                ? store.captureCutoff(scope) : continuation.cutoff();
        HistoryEvidenceMaterializer.Materialized materialized = loadReadEvidence(scope, cutoff, input);
        List<HistoryEvidence> selected = selectEvents(input, materialized);
        String manifestHash = manifestHash(selected);
        if (continuation != null) cursorCodec.requireEventManifest(continuation, manifestHash);

        int eventIndex = continuation == null ? 0 : continuation.eventIndex();
        int codePointOffset = continuation == null ? 0 : continuation.codePointOffset();
        if (eventIndex > selected.size()
                || eventIndex == selected.size() && codePointOffset != 0) {
            throw invalidCursor();
        }
        Page page = pageEvents(selected, eventIndex, codePointOffset);
        String nextCursor = page.complete() ? null : cursorCodec.encodeEvent(
                scope, new HistoryCursorCodec.EventReadCursor(
                        selectorHash, HistoryAuthorizedProjection.VERSION, cutoff,
                        exhaustedWatermarks(cutoff), manifestHash,
                        page.nextEventIndex(), 0, page.nextCodePointOffset()));
        return new SessionHistoryReadResponse(
                SCHEMA_VERSION, page.events(), nextCursor, !page.complete());
    }

    private Page pageEvents(
            List<HistoryEvidence> selected,
            int startEventIndex,
            int startCodePointOffset) {
        List<SessionHistoryReadResponse.Event> out = new ArrayList<>();
        int remaining = EVENT_PAGE_MAX_CODE_POINTS;
        int eventIndex = startEventIndex;
        int offset = startCodePointOffset;
        while (eventIndex < selected.size() && out.size() < EVENT_PAGE_MAX_EVENTS) {
            HistoryEvidence value = selected.get(eventIndex);
            int length = HistoryUnicode.codePointLength(value.content());
            if (offset > length) throw invalidCursor();
            int count = Math.min(remaining, length - offset);
            boolean complete = offset + count == length;
            out.add(event(
                    value, HistoryUnicode.sliceByCodePoints(value.content(), offset, count),
                    offset, complete));
            remaining -= count;
            if (!complete) return new Page(List.copyOf(out), eventIndex, offset + count, false);
            eventIndex++;
            offset = 0;
            if (remaining == 0) break;
        }
        return new Page(List.copyOf(out), eventIndex, offset, eventIndex == selected.size());
    }

    private List<HistoryEvidence> selectEvents(
            SessionHistoryReadInput input,
            HistoryEvidenceMaterializer.Materialized materialized) {
        List<HistoryEvidence> timeline = materialized.evidence().stream()
                .sorted(TIMELINE_ORDER).toList();
        return switch (input.arm()) {
            case REFS -> input.refs().stream().map(ref -> byRef(materialized, ref)).toList();
            case RANGE -> timeline.stream()
                    .filter(value -> value.logicalSeq() >= input.seqFrom()
                            && value.logicalSeq() <= input.seqTo())
                    .toList();
            case AROUND -> around(timeline, input.aroundSeq(), input.before(), input.after());
            case TAIL -> timeline.subList(Math.max(0, timeline.size() - input.tail()), timeline.size());
            case ARCHIVE -> throw new IllegalStateException("Block selector reached event reader");
        };
    }

    private static List<HistoryEvidence> around(
            List<HistoryEvidence> timeline,
            long aroundSeq,
            int before,
            int after) {
        if (timeline.isEmpty()) return List.of();
        int center = 0;
        long distance = Long.MAX_VALUE;
        for (int index = 0; index < timeline.size(); index++) {
            long candidate = unsignedDistance(timeline.get(index).logicalSeq(), aroundSeq);
            if (candidate < distance) {
                distance = candidate;
                center = index;
            }
        }
        int start = Math.max(0, center - before);
        int end = Math.min(timeline.size(), center + after + 1);
        return timeline.subList(start, end);
    }

    private HistoryEvidenceMaterializer.Materialized loadReadEvidence(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            SessionHistoryReadInput input) {
        if (input.arm() == SessionHistoryReadInput.SelectorArm.TAIL) {
            return loadWindow(scope, cutoff, null, cutoff.maxMessageSeq(), false, input.tail());
        }
        if (input.arm() == SessionHistoryReadInput.SelectorArm.AROUND) {
            int count = input.before() + input.after() + 1;
            var before = loadWindow(scope, cutoff, null, input.aroundSeq(), false, count);
            if (input.aroundSeq() == Long.MAX_VALUE) return before;
            var after = loadWindow(scope, cutoff, input.aroundSeq() + 1, null, true, count);
            List<HistoryEvidence> evidence = new ArrayList<>(before.evidence());
            evidence.addAll(after.evidence());
            Map<String, String> redirects = new LinkedHashMap<>(before.redirects());
            redirects.putAll(after.redirects());
            return new HistoryEvidenceMaterializer.Materialized(List.copyOf(evidence), redirects);
        }
        if (input.arm() == SessionHistoryReadInput.SelectorArm.RANGE) {
            return loadEvidence(scope, cutoff,
                    HistoryQueryStore.Selection.range(input.seqFrom(), input.seqTo(), true));
        }
        List<Long> messageIds = new ArrayList<>();
        List<Long> summaryIds = new ArrayList<>();
        List<String> archiveIds = new ArrayList<>();
        List<String> refs = input.arm() == SessionHistoryReadInput.SelectorArm.ARCHIVE
                ? List.of(input.archiveRef()) : input.refs();
        for (String ref : refs) {
            HistoryRefCodec.HistoryRef parsed = refCodec.parse(ref, scope);
            if (parsed instanceof HistoryRefCodec.MessageRef value) messageIds.add(value.messageId());
            else if (parsed instanceof HistoryRefCodec.SummaryRef value) summaryIds.add(value.summaryId());
            else archiveIds.add(((HistoryRefCodec.ArchiveRef) parsed).archiveId());
        }
        return loadEvidence(scope, cutoff, new HistoryQueryStore.Selection(
                null, null, messageIds, summaryIds, archiveIds, true));
    }

    /** Advances by persisted seq keys, so sparse timelines do not cause empty numeric scans. */
    private HistoryEvidenceMaterializer.Materialized loadWindow(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            Long from, Long to, boolean ascending, int requiredEvents) {
        List<HistoryEvidence> evidence = new ArrayList<>();
        Map<String, String> redirects = new LinkedHashMap<>();
        int scanned = 0;
        while (evidence.size() < requiredEvents) {
            var window = HistoryQueryStore.Selection.range(from, to, ascending);
            List<HistoryQueryStore.MessageRow> messages = store.loadMessages(scope, cutoff, window, 512);
            List<HistoryQueryStore.SummaryRow> summaries = store.loadSummaries(scope, cutoff, window, 512);
            if (messages.isEmpty() && summaries.isEmpty()) break;
            // Fetch the whole boundary seq to preserve ordering between message blocks and summaries.
            Long boundary = null;
            if (messages.size() > 512) boundary = messages.get(511).seqNo();
            if (summaries.size() > 512) {
                long summaryBoundary = summaries.get(511).endSeq();
                boundary = boundary == null ? summaryBoundary : ascending
                        ? Math.min(boundary, summaryBoundary) : Math.max(boundary, summaryBoundary);
            }
            var selected = HistoryQueryStore.Selection.range(
                    ascending ? from : boundary == null ? from : boundary,
                    ascending && boundary != null ? boundary : to, ascending);
            var part = loadEvidence(scope, cutoff, selected);
            evidence.addAll(part.evidence());
            redirects.putAll(part.redirects());
            scanned += messages.size() + summaries.size();
            if (boundary == null || evidence.size() >= requiredEvents) break;
            if (scanned > MAX_SCAN_ROWS_PER_SOURCE) throw scanLimit();
            if (ascending) {
                if (boundary == Long.MAX_VALUE) break;
                from = boundary + 1;
            } else {
                if (boundary == 0) break;
                to = boundary - 1;
            }
        }
        return new HistoryEvidenceMaterializer.Materialized(List.copyOf(evidence), Map.copyOf(redirects));
    }

    private HistoryEvidenceMaterializer.Materialized loadEvidence(
            CurrentSessionHistoryScope scope,
            HistoryCursorCodec.SnapshotCutoff cutoff,
            HistoryQueryStore.Selection selection) {
        List<HistoryQueryStore.MessageRow> messages = store.loadMessages(
                scope, cutoff, selection, MAX_SCAN_ROWS_PER_SOURCE);
        List<HistoryQueryStore.SummaryRow> summaries = store.loadSummaries(
                scope, cutoff, selection, MAX_SCAN_ROWS_PER_SOURCE);
        if (messages.size() > MAX_SCAN_ROWS_PER_SOURCE || summaries.size() > MAX_SCAN_ROWS_PER_SOURCE) {
            throw scanLimit();
        }
        boolean fullScan = selection.seqFrom() == null && selection.seqTo() == null
                && selection.messageIds() == null;
        // Batch ID lists below PostgreSQL's bind limit; these reads remain in the same snapshot.
        if (fullScan) {
            List<HistoryQueryStore.ArchiveRow> archives = store.loadArchives(scope, cutoff, MAX_SCAN_ROWS_PER_SOURCE);
            if (archives.size() > MAX_SCAN_ROWS_PER_SOURCE) throw scanLimit();
            return materializer.materialize(scope, messages, summaries, archives, cutoff.maxSummaryId());
        }
        List<HistoryQueryStore.ArchiveRow> archives = new ArrayList<>();
        Map<Long, HistoryQueryStore.MessageRow> context = new LinkedHashMap<>();
        for (int start = 0; start < messages.size(); start += 512) {
            List<Long> ids = messages.subList(start, Math.min(start + 512, messages.size()))
                    .stream().map(HistoryQueryStore.MessageRow::id).toList();
            archives.addAll(store.loadArchivesForMessages(scope, cutoff, ids, MAX_SCAN_ROWS_PER_SOURCE));
            store.loadPairingContext(scope, cutoff, ids, MAX_SCAN_ROWS_PER_SOURCE)
                    .forEach(row -> context.put(row.id(), row));
            if (archives.size() > MAX_SCAN_ROWS_PER_SOURCE || context.size() > MAX_SCAN_ROWS_PER_SOURCE) {
                throw scanLimit();
            }
        }
        return materializer.materialize(scope, messages, summaries, archives, cutoff.maxSummaryId(),
                List.copyOf(context.values()));
    }

    private static HistoryProtocolException scanLimit() {
        return new HistoryProtocolException(
                "HISTORY_SCAN_LIMIT", "History scan requires a narrower seqFrom/seqTo range or explicit refs");
    }

    private void requireQueryableScope(CurrentSessionHistoryScope scope) {
        HistoryQueryStore.ScopeState state = store.requireCurrentScope(scope);
        if (state.historyEpoch() != scope.historyEpoch()) {
            throw new HistoryProtocolException("HISTORY_STALE", "History scope is stale");
        }
        if (state.legacyOnly()) {
            throw new HistoryProtocolException(
                    "HISTORY_UNAVAILABLE", "History is unavailable for the current Session");
        }
    }

    private static boolean matches(SessionHistorySearchInput input, HistoryEvidence value) {
        if (input.seqFrom() != null && value.logicalSeq() < input.seqFrom()) return false;
        if (input.seqTo() != null && value.logicalSeq() > input.seqTo()) return false;
        if (input.roles() != null && input.roles().stream()
                .noneMatch(role -> role.name().equals(value.role()))) return false;
        if (input.kinds() != null && input.kinds().stream()
                .noneMatch(kind -> kind.name().equals(value.kind().name()))) return false;
        if (input.toolName() != null && !input.toolName().equals(value.toolName())) return false;
        if (input.toolUseId() != null && !input.toolUseId().equals(value.toolUseId())) return false;
        if (input.compacted() != null && input.compacted() != SessionHistorySearchInput.Compacted.ANY
                && (input.compacted() == SessionHistorySearchInput.Compacted.COMPACTED)
                        != value.compacted()) return false;
        if (value.kind() == HistoryEvidence.Kind.SUMMARY && input.summaryState() != null
                && input.summaryState() != SessionHistorySearchInput.SummaryState.ANY
                && !input.summaryState().name().equals(value.summaryState())) return false;
        if (input.query() == null) return true;
        String needle = input.query().toLowerCase(Locale.ROOT);
        return value.content().toLowerCase(Locale.ROOT).contains(needle)
                || value.toolName() != null
                        && value.toolName().toLowerCase(Locale.ROOT).contains(needle)
                || value.toolUseId() != null
                        && value.toolUseId().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean isAfter(
            HistoryEvidence value,
            HistoryCursorCodec.SearchPosition position) {
        if (position == null) return true;
        return SEARCH_ORDER.compare(value, fromPosition(position)) > 0;
    }

    private static boolean samePosition(
            HistoryEvidence value,
            HistoryCursorCodec.SearchPosition position) {
        return value.evidenceClass().ordinal() == position.evidenceRank()
                && value.logicalSeq() == position.logicalSeq()
                && value.createdAt().getEpochSecond() == position.createdAtEpochSecond()
                && value.createdAt().getNano() == position.createdAtNano()
                && value.sourceOrder() == position.sourceOrder()
                && value.ref().equals(position.stableRef());
    }

    private static HistoryEvidence fromPosition(HistoryCursorCodec.SearchPosition position) {
        return new HistoryEvidence(
                position.stableRef(),
                HistoryEvidence.EvidenceClass.values()[position.evidenceRank()],
                HistoryEvidence.Kind.TEXT, "USER", position.logicalSeq(), null, null,
                false, null, "", "cursor", Instant.ofEpochSecond(
                        position.createdAtEpochSecond(), position.createdAtNano()),
                position.sourceOrder(), null);
    }

    private static HistoryCursorCodec.SearchPosition position(HistoryEvidence value) {
        return new HistoryCursorCodec.SearchPosition(
                value.evidenceClass().ordinal(), value.logicalSeq(),
                value.createdAt().getEpochSecond(), value.createdAt().getNano(),
                value.sourceOrder(), value.ref());
    }

    private static HistoryCursorCodec.SourceWatermarks searchWatermarks(
            HistoryCursorCodec.SnapshotCutoff cutoff,
            List<HistoryEvidence> remaining) {
        boolean originalsRemain = remaining.stream()
                .anyMatch(value -> value.evidenceClass() == HistoryEvidence.EvidenceClass.ORIGINAL);
        boolean summariesRemain = remaining.stream()
                .anyMatch(value -> value.evidenceClass()
                        == HistoryEvidence.EvidenceClass.DERIVED_SUMMARY);
        return new HistoryCursorCodec.SourceWatermarks(
                cutoff.maxMessageId(), cutoff.maxSummaryId(), cutoff.maxArchiveRowId(),
                !originalsRemain, !summariesRemain, !originalsRemain, 0);
    }

    private static HistoryCursorCodec.SourceWatermarks exhaustedWatermarks(
            HistoryCursorCodec.SnapshotCutoff cutoff) {
        return new HistoryCursorCodec.SourceWatermarks(
                cutoff.maxMessageId(), cutoff.maxSummaryId(), cutoff.maxArchiveRowId(),
                true, true, true, 0);
    }

    private SessionHistorySearchResponse.Locator locator(HistoryEvidence value) {
        String preview = HistoryUnicode.sliceByCodePoints(
                value.content(), 0,
                Math.min(PREVIEW_CODE_POINTS, HistoryUnicode.codePointLength(value.content())));
        return new SessionHistorySearchResponse.Locator(
                value.ref(), value.evidenceClass().name(), value.kind().name(), value.role(),
                value.logicalSeq(), value.toolName(), value.toolUseId(), value.compacted(),
                value.summaryState(), preview, value.authorizedContentHash());
    }

    private static SessionHistoryReadResponse.Event event(
            HistoryEvidence value,
            String content,
            int offset,
            boolean complete) {
        return new SessionHistoryReadResponse.Event(
                value.ref(), value.evidenceClass().name(), value.kind().name(), value.role(),
                value.logicalSeq(), value.toolName(), value.toolUseId(), content, offset,
                complete, value.authorizedContentHash());
    }

    private static HistoryEvidence byRef(
            HistoryEvidenceMaterializer.Materialized materialized,
            String ref) {
        String redirect = materialized.redirects().get(ref);
        if (redirect != null) {
            throw new HistoryProtocolException(
                    "HISTORY_REF_REDIRECT", "History reference now has a canonical archive");
        }
        return materialized.evidence().stream()
                .filter(value -> value.ref().equals(ref))
                .findFirst()
                .orElseThrow(() -> new HistoryProtocolException(
                        "INVALID_REF", "Invalid History reference"));
    }

    private static String manifestHash(List<HistoryEvidence> values) {
        StringBuilder framed = new StringBuilder("history-event-manifest-v1\n");
        for (HistoryEvidence value : values) {
            appendFramed(framed, value.ref());
            appendFramed(framed, value.authorizedContentHash());
            appendFramed(framed, Integer.toString(
                    HistoryUnicode.codePointLength(value.content())));
        }
        return sha256(framed.toString());
    }

    private static void appendFramed(StringBuilder target, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        target.append(bytes.length).append(':').append(value).append('\n');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long unsignedDistance(long first, long second) {
        if (first >= second) return first - second;
        return second - first;
    }

    private static HistoryProtocolException invalidCursor() {
        return new HistoryProtocolException("INVALID_CURSOR", "Invalid History cursor");
    }

    private record Page(
            List<SessionHistoryReadResponse.Event> events,
            int nextEventIndex,
            int nextCodePointOffset,
            boolean complete) { }
}
