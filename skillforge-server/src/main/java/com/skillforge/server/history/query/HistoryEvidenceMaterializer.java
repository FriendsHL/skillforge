package com.skillforge.server.history.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryRefCodec;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Decodes immutable rows, applies the allowlist, and permanently removes History's own traces. */
@Component
final class HistoryEvidenceMaterializer {

    private static final Set<String> HISTORY_TOOL_NAMES = Set.of(
            "SessionHistorySearch", "SessionHistoryRead");

    private final ObjectMapper objectMapper;
    private final HistoryAuthorizedProjection projection;
    private final HistoryRefCodec refCodec;
    private final HistoryCanonicalArchiveResolver archiveResolver;

    HistoryEvidenceMaterializer(
            ObjectMapper objectMapper,
            HistoryAuthorizedProjection projection,
            HistoryRefCodec refCodec,
            HistoryCanonicalArchiveResolver archiveResolver) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.refCodec = Objects.requireNonNull(refCodec, "refCodec");
        this.archiveResolver = Objects.requireNonNull(archiveResolver, "archiveResolver");
    }

    Materialized materialize(
            CurrentSessionHistoryScope scope,
            List<HistoryQueryStore.MessageRow> messages,
            List<HistoryQueryStore.SummaryRow> summaries,
            List<HistoryQueryStore.ArchiveRow> archives,
            long maxSummaryId) {
        return materialize(scope, messages, summaries, archives, maxSummaryId, List.of());
    }

    Materialized materialize(
            CurrentSessionHistoryScope scope,
            List<HistoryQueryStore.MessageRow> messages,
            List<HistoryQueryStore.SummaryRow> summaries,
            List<HistoryQueryStore.ArchiveRow> archives,
            long maxSummaryId,
            List<HistoryQueryStore.MessageRow> pairingContext) {
        List<DecodedMessage> decoded = messages.stream().map(this::decodeMessage).toList();
        Map<Long, DecodedMessage> pairingRows = new LinkedHashMap<>();
        pairingContext.forEach(row -> pairingRows.put(row.id(), decodeMessage(row)));
        decoded.forEach(row -> pairingRows.put(row.row().id(), row));
        PairingPlan pairing = pairingPlan(List.copyOf(pairingRows.values()));
        List<HistoryEvidence> raw = new ArrayList<>();
        List<HistoryCanonicalArchiveResolver.RawToolResultOccurrence> rawResults =
                new ArrayList<>();

        for (DecodedMessage message : decoded) {
            for (DecodedBlock block : message.blocks()) {
                BlockOccurrence occurrence = new BlockOccurrence(message.row().id(), block.index());
                if (pairing.historyOccurrences().contains(occurrence)) continue;
                HistoryEvidence evidence = rawEvidence(
                        scope, message.row(), block, pairing.resultToolNames().get(occurrence));
                if (evidence == null) continue;
                raw.add(evidence);
                if (evidence.kind() == HistoryEvidence.Kind.TOOL_RESULT) {
                    rawResults.add(new HistoryCanonicalArchiveResolver.RawToolResultOccurrence(
                            evidence.rawOccurrence(), evidence.logicalSeq(), evidence.role(),
                            evidence.toolUseId(), evidence.toolName(),
                            block.resultContent().isTextual()
                                    ? block.resultContent().textValue() : null,
                            block.error(), block.errorType(), evidence.compacted(),
                            evidence.createdAt(), evidence.sourceOrder()));
                }
            }
        }

        HistoryCanonicalArchiveResolver.Resolution resolution = archiveResolver.resolve(
                List.copyOf(rawResults), List.copyOf(archives));
        Map<String, String> redirects = new LinkedHashMap<>();
        List<HistoryEvidence> originals = new ArrayList<>(raw.size());
        for (HistoryEvidence evidence : raw) {
            HistoryCanonicalArchiveResolver.CanonicalArchive archive = evidence.rawOccurrence() == null
                    ? null : resolution.byOccurrence().get(evidence.rawOccurrence());
            if (archive == null) {
                originals.add(evidence);
                continue;
            }
            String archiveRef = refCodec.format(
                    new HistoryRefCodec.ArchiveRef(scope.historyEpoch(), archive.archiveId()), scope);
            redirects.put(evidence.ref(), archiveRef);
            HistoryAuthorizedProjection.ProjectedContent projected =
                    projection.projectToolResult(
                            archive.content(), archive.error(), archive.errorType());
            originals.add(new HistoryEvidence(
                    archiveRef, HistoryEvidence.EvidenceClass.ORIGINAL,
                    HistoryEvidence.Kind.TOOL_RESULT, evidence.role(), archive.logicalSeq(),
                    archive.toolName(), archive.toolUseId(), archive.compacted(), null,
                    projected.content(), projected.authorizedContentHash(), archive.createdAt(),
                    archive.sourceOrder(), null));
        }

        List<HistoryEvidence> all = new ArrayList<>(originals);
        for (HistoryQueryStore.SummaryRow summary : summaries) {
            HistoryAuthorizedProjection.ProjectedContent projected =
                    projection.projectPlainText(summary.summaryText());
            String state = summary.supersededBy() == null || summary.supersededBy() > maxSummaryId
                    ? "ACTIVE" : "SUPERSEDED";
            all.add(new HistoryEvidence(
                    refCodec.format(new HistoryRefCodec.SummaryRef(
                            scope.historyEpoch(), summary.id()), scope),
                    HistoryEvidence.EvidenceClass.DERIVED_SUMMARY,
                    HistoryEvidence.Kind.SUMMARY, "USER", summary.endSeq(), null, null, true,
                    state, projected.content(), projected.authorizedContentHash(),
                    summary.createdAt(), 0, null));
        }
        return new Materialized(List.copyOf(all), Map.copyOf(redirects));
    }

    private HistoryEvidence rawEvidence(
            CurrentSessionHistoryScope scope,
            HistoryQueryStore.MessageRow row,
            DecodedBlock block,
            String pairedToolName) {
        String role = normalizedRole(row.role());
        if (role == null || !"NORMAL".equals(row.msgType())
                || !"normal".equals(row.messageType()) || row.controlId() != null) {
            return null;
        }
        HistoryAuthorizedProjection.ProjectedContent projected;
        HistoryEvidence.Kind kind;
        String toolName = null;
        String toolUseId = null;
        HistoryCanonicalArchiveResolver.OccurrenceKey occurrence = null;
        switch (block.type()) {
            case "text" -> {
                if (block.text() == null) return null;
                kind = HistoryEvidence.Kind.TEXT;
                projected = projection.projectPlainText(block.text());
            }
            case "tool_use" -> {
                if (!"ASSISTANT".equals(role) || block.toolUseId() == null
                        || block.toolName() == null || block.input() == null) return null;
                kind = HistoryEvidence.Kind.TOOL_USE;
                toolName = block.toolName();
                toolUseId = block.toolUseId();
                projected = projection.projectStructured(block.input());
            }
            case "tool_result" -> {
                if (!"USER".equals(role) || block.toolUseId() == null
                        || block.resultContent() == null) return null;
                kind = HistoryEvidence.Kind.TOOL_RESULT;
                toolUseId = block.toolUseId();
                toolName = pairedToolName;
                projected = projection.projectToolResult(
                        block.resultContent(), block.error(), block.errorType());
                occurrence = new HistoryCanonicalArchiveResolver.OccurrenceKey(
                        row.id(), block.index());
            }
            default -> { return null; }
        }
        String ref = refCodec.format(new HistoryRefCodec.MessageRef(
                scope.historyEpoch(), row.id(), block.index()), scope);
        return new HistoryEvidence(
                ref, HistoryEvidence.EvidenceClass.ORIGINAL, kind, role, row.seqNo(),
                toolName, toolUseId, row.compactedBySummaryId() != null, null,
                projected.content(), projected.authorizedContentHash(), row.createdAt(),
                block.index(), occurrence);
    }

    private DecodedMessage decodeMessage(HistoryQueryStore.MessageRow row) {
        try {
            JsonNode content = objectMapper.readTree(row.contentJson());
            List<DecodedBlock> blocks = new ArrayList<>();
            if (content != null && content.isTextual()) {
                blocks.add(DecodedBlock.text(0, content.textValue()));
            } else if (content != null && content.isArray()) {
                int index = 0;
                for (JsonNode item : content) {
                    DecodedBlock block = decodeBlock(index++, item);
                    if (block != null) blocks.add(block);
                }
            }
            return new DecodedMessage(row, List.copyOf(blocks));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return new DecodedMessage(row, List.of());
        }
    }

    private DecodedBlock decodeBlock(int index, JsonNode item) {
        if (!item.isObject() || !item.path("type").isTextual()) return null;
        return switch (item.path("type").textValue()) {
            case "text" -> item.path("text").isTextual()
                    ? DecodedBlock.text(index, item.path("text").textValue()) : null;
            case "tool_use" -> item.path("id").isTextual()
                    && item.path("name").isTextual() && item.has("input")
                    ? DecodedBlock.toolUse(index, item.path("id").textValue(),
                            item.path("name").textValue(), item.get("input")) : null;
            case "tool_result" -> item.path("tool_use_id").isTextual()
                    && item.has("content")
                    ? DecodedBlock.toolResult(
                            index, item.path("tool_use_id").textValue(),
                            textualOrNull(item.get("tool_name")), item.get("content"),
                            item.path("is_error").asBoolean(false),
                            textualOrNull(item.get("error_type"))) : null;
            default -> null;
        };
    }

    /**
     * Binds each result to the oldest still-open intent occurrence with the same provider ID.
     * Provider IDs are not Session-global identities: restore/replay can legitimately reuse one.
     * Therefore History self-exclusion is occurrence based, never a global set of IDs.
     */
    private static PairingPlan pairingPlan(List<DecodedMessage> messages) {
        List<DecodedMessage> timeline = messages.stream()
                .sorted(Comparator.comparingLong((DecodedMessage value) -> value.row().seqNo())
                        .thenComparing(value -> value.row().createdAt())
                        .thenComparingLong(value -> value.row().id()))
                .toList();
        Map<String, Deque<PendingToolIntent>> pendingByToolUseId = new LinkedHashMap<>();
        Set<BlockOccurrence> historyOccurrences = new HashSet<>();
        Map<BlockOccurrence, String> resultToolNames = new LinkedHashMap<>();
        for (DecodedMessage message : timeline) {
            String role = normalizedRole(message.row().role());
            boolean conversational = "NORMAL".equals(message.row().msgType())
                    && "normal".equals(message.row().messageType())
                    && message.row().controlId() == null;
            if (!conversational || role == null) continue;
            for (DecodedBlock block : message.blocks()) {
                BlockOccurrence occurrence = new BlockOccurrence(
                        message.row().id(), block.index());
                if ("tool_use".equals(block.type()) && "ASSISTANT".equals(role)
                        && block.toolUseId() != null && block.toolName() != null) {
                    boolean history = HISTORY_TOOL_NAMES.contains(block.toolName());
                    // A later intent with the same provider ID starts a new occurrence. Any
                    // older unmatched intent is dangling and must not capture the later result.
                    Deque<PendingToolIntent> pending = new ArrayDeque<>();
                    pending.addLast(new PendingToolIntent(block.toolName(), history));
                    pendingByToolUseId.put(block.toolUseId(), pending);
                    if (history) historyOccurrences.add(occurrence);
                    continue;
                }
                if (!"tool_result".equals(block.type()) || !"USER".equals(role)
                        || block.toolUseId() == null) {
                    continue;
                }
                Deque<PendingToolIntent> pending = pendingByToolUseId.get(block.toolUseId());
                PendingToolIntent intent = pending == null ? null : pending.pollFirst();
                if (pending != null && pending.isEmpty()) {
                    pendingByToolUseId.remove(block.toolUseId());
                }
                if (intent == null) continue;
                resultToolNames.put(occurrence, intent.toolName());
                if (intent.history()) historyOccurrences.add(occurrence);
            }
        }
        return new PairingPlan(Set.copyOf(historyOccurrences), Map.copyOf(resultToolNames));
    }

    private static String normalizedRole(String role) {
        return switch (role) {
            case "user" -> "USER";
            case "assistant" -> "ASSISTANT";
            default -> null;
        };
    }

    private static String textualOrNull(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    record Materialized(List<HistoryEvidence> evidence, Map<String, String> redirects) { }

    private record PairingPlan(
            Set<BlockOccurrence> historyOccurrences,
            Map<BlockOccurrence, String> resultToolNames) { }

    private record PendingToolIntent(String toolName, boolean history) { }

    private record BlockOccurrence(long messageId, int blockIndex) { }

    private record DecodedMessage(
            HistoryQueryStore.MessageRow row,
            List<DecodedBlock> blocks) { }

    private record DecodedBlock(
            int index,
            String type,
            String text,
            String toolUseId,
            String toolName,
            JsonNode input,
            JsonNode resultContent,
            boolean error,
            String errorType) {

        static DecodedBlock text(int index, String text) {
            return new DecodedBlock(index, "text", text, null, null,
                    null, null, false, null);
        }

        static DecodedBlock toolUse(int index, String id, String name, JsonNode input) {
            return new DecodedBlock(index, "tool_use", null, id, name,
                    input, null, false, null);
        }

        static DecodedBlock toolResult(
                int index, String id, String name, JsonNode content,
                boolean error, String errorType) {
            return new DecodedBlock(index, "tool_result", null, id, name,
                    null, content, error, errorType);
        }
    }
}
