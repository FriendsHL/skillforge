package com.skillforge.server.history;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict unsigned v1 cursor codec.
 *
 * <p>The digest detects accidental or byte-level mutation of an exact-return token. It is not a
 * signature and grants no authority; current trusted scope and epoch are checked on every decode.
 */
@Component
public final class HistoryCursorCodec {

    private static final int VERSION = 1;
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final int MAX_BINDING_LENGTH = 4096;
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

    private static final List<String> SEARCH_FIELDS = List.of(
            "v", "type", "scope", "epoch", "selectorHash", "projectionVersion",
            "cutoff", "watermarks", "position", "integrity");
    private static final List<String> EVENT_FIELDS = List.of(
            "v", "type", "scope", "epoch", "selectorHash", "projectionVersion",
            "cutoff", "watermarks", "manifestHash", "eventIndex", "blockIndex",
            "codePointOffset", "integrity");
    private static final List<String> BLOCK_FIELDS = List.of(
            "v", "type", "scope", "epoch", "selectorHash", "projectionVersion",
            "cutoff", "targetRef", "contentHash", "codePointOffset", "integrity");
    private static final List<String> CUTOFF_FIELDS = List.of(
            "maxMessageId", "maxMessageSeq", "maxSummaryId", "maxArchiveRowId",
            "preIntentMaxMessageId", "preIntentMaxSeq");
    private static final List<String> WATERMARK_FIELDS = List.of(
            "messageWatermark", "summaryWatermark", "archiveWatermark",
            "messagesExhausted", "summariesExhausted", "archivesExhausted", "scanCount");
    private static final List<String> SEARCH_POSITION_FIELDS = List.of(
            "evidenceRank", "logicalSeq", "createdAtEpochSecond", "createdAtNano",
            "sourceOrder", "stableRef");

    private final ObjectMapper objectMapper;

    public HistoryCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String encodeSearch(CurrentSessionHistoryScope scope, SearchCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        validateCutoffAgainstScope(scope, cursor.cutoff());
        Map<String, Object> fields = commonFields(
                scope, "search", cursor.selectorHash(), cursor.projectionVersion(), cursor.cutoff());
        fields.put("watermarks", watermarksMap(cursor.watermarks()));
        fields.put("position", searchPositionMap(cursor.position()));
        return encode(fields);
    }

    public SearchCursor decodeSearch(
            CurrentSessionHistoryScope scope,
            String token,
            String expectedSelectorHash,
            int expectedProjectionVersion) {
        ObjectNode root = decodeRoot(token);
        validateCommon(root, SEARCH_FIELDS, "search", scope,
                expectedSelectorHash, expectedProjectionVersion);
        SnapshotCutoff cutoff = parseCutoff(root.get("cutoff"));
        validateCutoffAgainstScope(scope, cutoff);
        return new SearchCursor(
                text(root, "selectorHash"), integer(root, "projectionVersion"), cutoff,
                parseWatermarks(root.get("watermarks")), parseSearchPosition(root.get("position")));
    }

    public String encodeEvent(CurrentSessionHistoryScope scope, EventReadCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        validateCutoffAgainstScope(scope, cursor.cutoff());
        Map<String, Object> fields = commonFields(
                scope, "event", cursor.selectorHash(), cursor.projectionVersion(), cursor.cutoff());
        fields.put("watermarks", watermarksMap(cursor.watermarks()));
        fields.put("manifestHash", cursor.manifestHash());
        fields.put("eventIndex", cursor.eventIndex());
        fields.put("blockIndex", cursor.blockIndex());
        fields.put("codePointOffset", cursor.codePointOffset());
        return encode(fields);
    }

    public EventReadCursor decodeEvent(
            CurrentSessionHistoryScope scope,
            String token,
            String expectedSelectorHash,
            int expectedProjectionVersion,
            String expectedManifestHash) {
        EventReadCursor cursor = decodeEvent(
                scope, token, expectedSelectorHash, expectedProjectionVersion);
        requireBinding(expectedManifestHash, cursor.manifestHash(),
                "HISTORY_CONTENT_CHANGED", "History event content changed");
        return cursor;
    }

    /** Decodes scope/selector/snapshot first so the caller can rebuild the frozen manifest. */
    public EventReadCursor decodeEvent(
            CurrentSessionHistoryScope scope,
            String token,
            String expectedSelectorHash,
            int expectedProjectionVersion) {
        ObjectNode root = decodeRoot(token);
        validateCommon(root, EVENT_FIELDS, "event", scope,
                expectedSelectorHash, expectedProjectionVersion);
        SnapshotCutoff cutoff = parseCutoff(root.get("cutoff"));
        validateCutoffAgainstScope(scope, cutoff);
        return new EventReadCursor(
                text(root, "selectorHash"), integer(root, "projectionVersion"), cutoff,
                parseWatermarks(root.get("watermarks")), text(root, "manifestHash"),
                nonNegativeInt(root, "eventIndex"), nonNegativeInt(root, "blockIndex"),
                nonNegativeInt(root, "codePointOffset"));
    }

    public String encodeBlock(CurrentSessionHistoryScope scope, BlockReadCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        validateCutoffAgainstScope(scope, cursor.cutoff());
        Map<String, Object> fields = commonFields(
                scope, "block", cursor.selectorHash(), cursor.projectionVersion(), cursor.cutoff());
        fields.put("targetRef", cursor.targetRef());
        fields.put("contentHash", cursor.contentHash());
        fields.put("codePointOffset", cursor.codePointOffset());
        return encode(fields);
    }

    public BlockReadCursor decodeBlock(
            CurrentSessionHistoryScope scope,
            String token,
            String expectedSelectorHash,
            int expectedProjectionVersion,
            String expectedContentHash) {
        BlockReadCursor cursor = decodeBlock(
                scope, token, expectedSelectorHash, expectedProjectionVersion);
        requireBinding(expectedContentHash, cursor.contentHash(),
                "HISTORY_CONTENT_CHANGED", "History block content changed");
        return cursor;
    }

    /** Decodes scope/selector/snapshot first so the caller can re-authorize the target content. */
    public BlockReadCursor decodeBlock(
            CurrentSessionHistoryScope scope,
            String token,
            String expectedSelectorHash,
            int expectedProjectionVersion) {
        ObjectNode root = decodeRoot(token);
        validateCommon(root, BLOCK_FIELDS, "block", scope,
                expectedSelectorHash, expectedProjectionVersion);
        SnapshotCutoff cutoff = parseCutoff(root.get("cutoff"));
        validateCutoffAgainstScope(scope, cutoff);
        return new BlockReadCursor(
                text(root, "selectorHash"), integer(root, "projectionVersion"), cutoff,
                text(root, "targetRef"), text(root, "contentHash"),
                nonNegativeInt(root, "codePointOffset"));
    }

    public void requireEventManifest(EventReadCursor cursor, String expectedManifestHash) {
        Objects.requireNonNull(cursor, "cursor");
        requireBinding(expectedManifestHash, cursor.manifestHash(),
                "HISTORY_CONTENT_CHANGED", "History event content changed");
    }

    public void requireBlockContent(BlockReadCursor cursor, String expectedContentHash) {
        Objects.requireNonNull(cursor, "cursor");
        requireBinding(expectedContentHash, cursor.contentHash(),
                "HISTORY_CONTENT_CHANGED", "History block content changed");
    }

    private Map<String, Object> commonFields(
            CurrentSessionHistoryScope scope,
            String type,
            String selectorHash,
            int projectionVersion,
            SnapshotCutoff cutoff) {
        Objects.requireNonNull(scope, "scope");
        requireBounded(selectorHash, "selectorHash");
        if (projectionVersion <= 0) {
            throw new IllegalArgumentException("projectionVersion must be positive");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("v", VERSION);
        fields.put("type", type);
        fields.put("scope", scopeFingerprint(scope));
        fields.put("epoch", scope.historyEpoch());
        fields.put("selectorHash", selectorHash);
        fields.put("projectionVersion", projectionVersion);
        fields.put("cutoff", cutoffMap(cutoff));
        return fields;
    }

    private String encode(Map<String, Object> fields) {
        Map<String, Object> envelope = new LinkedHashMap<>(fields);
        envelope.put("integrity", sha256(write(fields)));
        byte[] json = write(envelope);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        if (token.length() > MAX_TOKEN_LENGTH) throw invalidCursor();
        return token;
    }

    private ObjectNode decodeRoot(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH
                || !BASE64URL.matcher(token).matches()) {
            throw invalidCursor();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
                throw invalidCursor();
            }
            JsonNode parsed;
            try (JsonParser parser = objectMapper.getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                parsed = objectMapper.readTree(parser);
                if (parser.nextToken() != null) throw invalidCursor();
            }
            if (!(parsed instanceof ObjectNode root)) throw invalidCursor();
            if (!MessageDigest.isEqual(bytes, write(root))) throw invalidCursor();
            validateIntegrity(root);
            return root;
        } catch (HistoryProtocolException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    private void validateIntegrity(ObjectNode root) {
        JsonNode integrityNode = root.get("integrity");
        if (integrityNode == null || !integrityNode.isTextual()) throw invalidCursor();
        ObjectNode body = root.deepCopy();
        body.remove("integrity");
        if (!constantTimeEquals(integrityNode.textValue(), sha256(write(body)))) {
            throw invalidCursor();
        }
    }

    private void validateCommon(
            ObjectNode root,
            List<String> expectedFields,
            String expectedType,
            CurrentSessionHistoryScope scope,
            String expectedSelectorHash,
            int expectedProjectionVersion) {
        Objects.requireNonNull(scope, "scope");
        requireExactFields(root, expectedFields);
        if (integer(root, "v") != VERSION || !expectedType.equals(text(root, "type"))) {
            throw invalidCursor();
        }
        if (!constantTimeEquals(scopeFingerprint(scope), text(root, "scope"))) {
            throw invalidCursor();
        }
        if (longValue(root, "epoch") != scope.historyEpoch()) {
            throw new HistoryProtocolException("HISTORY_STALE", "History cursor is stale");
        }
        requireBinding(expectedSelectorHash, text(root, "selectorHash"),
                "CURSOR_SELECTOR_MISMATCH", "History cursor selector changed");
        if (integer(root, "projectionVersion") != expectedProjectionVersion) {
            throw new HistoryProtocolException(
                    "HISTORY_PROJECTION_CHANGED", "History projection version changed");
        }
    }

    private SnapshotCutoff parseCutoff(JsonNode node) {
        ObjectNode value = object(node);
        requireExactFields(value, CUTOFF_FIELDS);
        return new SnapshotCutoff(
                longValue(value, "maxMessageId"), longValue(value, "maxMessageSeq"),
                longValue(value, "maxSummaryId"), longValue(value, "maxArchiveRowId"),
                longValue(value, "preIntentMaxMessageId"),
                longValue(value, "preIntentMaxSeq"));
    }

    private SourceWatermarks parseWatermarks(JsonNode node) {
        ObjectNode value = object(node);
        requireExactFields(value, WATERMARK_FIELDS);
        return new SourceWatermarks(
                longValue(value, "messageWatermark"), longValue(value, "summaryWatermark"),
                longValue(value, "archiveWatermark"), booleanValue(value, "messagesExhausted"),
                booleanValue(value, "summariesExhausted"), booleanValue(value, "archivesExhausted"),
                longValue(value, "scanCount"));
    }

    private SearchPosition parseSearchPosition(JsonNode node) {
        if (node == null || node.isNull()) return null;
        ObjectNode value = object(node);
        requireExactFields(value, SEARCH_POSITION_FIELDS);
        return new SearchPosition(
                nonNegativeInt(value, "evidenceRank"),
                longValue(value, "logicalSeq"),
                longValue(value, "createdAtEpochSecond"),
                nonNegativeInt(value, "createdAtNano"),
                nonNegativeInt(value, "sourceOrder"),
                text(value, "stableRef"));
    }

    private static Map<String, Object> cutoffMap(SnapshotCutoff value) {
        Objects.requireNonNull(value, "cutoff");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("maxMessageId", value.maxMessageId());
        fields.put("maxMessageSeq", value.maxMessageSeq());
        fields.put("maxSummaryId", value.maxSummaryId());
        fields.put("maxArchiveRowId", value.maxArchiveRowId());
        fields.put("preIntentMaxMessageId", value.preIntentMaxMessageId());
        fields.put("preIntentMaxSeq", value.preIntentMaxSeq());
        return fields;
    }

    private static Map<String, Object> watermarksMap(SourceWatermarks value) {
        Objects.requireNonNull(value, "watermarks");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("messageWatermark", value.messageWatermark());
        fields.put("summaryWatermark", value.summaryWatermark());
        fields.put("archiveWatermark", value.archiveWatermark());
        fields.put("messagesExhausted", value.messagesExhausted());
        fields.put("summariesExhausted", value.summariesExhausted());
        fields.put("archivesExhausted", value.archivesExhausted());
        fields.put("scanCount", value.scanCount());
        return fields;
    }

    private static Map<String, Object> searchPositionMap(SearchPosition value) {
        if (value == null) return null;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("evidenceRank", value.evidenceRank());
        fields.put("logicalSeq", value.logicalSeq());
        fields.put("createdAtEpochSecond", value.createdAtEpochSecond());
        fields.put("createdAtNano", value.createdAtNano());
        fields.put("sourceOrder", value.sourceOrder());
        fields.put("stableRef", value.stableRef());
        return fields;
    }

    private void validateCutoffAgainstScope(
            CurrentSessionHistoryScope scope,
            SnapshotCutoff cutoff) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(cutoff, "cutoff");
        boolean scopeHasFrontier = scope.preIntentMaxMessageId() >= 0;
        if (!scopeHasFrontier) {
            if (cutoff.preIntentMaxMessageId() != -1 || cutoff.preIntentMaxSeq() != -1
                    || cutoff.maxMessageId() != 0 || cutoff.maxMessageSeq() != 0
                    || cutoff.maxSummaryId() != 0 || cutoff.maxArchiveRowId() != 0) {
                throw invalidCursor();
            }
            return;
        }
        if (cutoff.preIntentMaxMessageId() != scope.preIntentMaxMessageId()
                || cutoff.preIntentMaxSeq() != scope.preIntentMaxSeq()) {
            throw invalidCursor();
        }
    }

    private byte[] write(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw invalidCursor();
        }
    }

    private static String scopeFingerprint(CurrentSessionHistoryScope scope) {
        String sessionId = scope.sessionId();
        String framed = "history-cursor-scope-v1\n"
                + sessionId.getBytes(StandardCharsets.UTF_8).length + ":" + sessionId
                + "\n" + scope.userId();
        return sha256(framed.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireExactFields(ObjectNode object, List<String> expected) {
        List<String> actual = new java.util.ArrayList<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalidCursor();
    }

    private static ObjectNode object(JsonNode node) {
        if (!(node instanceof ObjectNode value)) throw invalidCursor();
        return value;
    }

    private static String text(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()
                || node.textValue().length() > MAX_BINDING_LENGTH) {
            throw invalidCursor();
        }
        return node.textValue();
    }

    private static long longValue(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalidCursor();
        }
        return node.longValue();
    }

    private static int integer(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalidCursor();
        }
        return node.intValue();
    }

    private static int nonNegativeInt(ObjectNode object, String field) {
        int value = integer(object, field);
        if (value < 0) throw invalidCursor();
        return value;
    }

    private static boolean booleanValue(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isBoolean()) throw invalidCursor();
        return node.booleanValue();
    }

    private static void requireBinding(
            String expected,
            String actual,
            String code,
            String message) {
        requireBounded(expected, "expected binding");
        if (!constantTimeEquals(expected, actual)) {
            throw new HistoryProtocolException(code, message);
        }
    }

    private static void requireBounded(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_BINDING_LENGTH) {
            throw new IllegalArgumentException(field + " must be nonblank and bounded");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    private static HistoryProtocolException invalidCursor() {
        return new HistoryProtocolException("INVALID_CURSOR", "Invalid History cursor");
    }

    public record SnapshotCutoff(
            long maxMessageId,
            long maxMessageSeq,
            long maxSummaryId,
            long maxArchiveRowId,
            long preIntentMaxMessageId,
            long preIntentMaxSeq) {
        public SnapshotCutoff {
            if (maxMessageId < 0 || maxMessageSeq < 0 || maxSummaryId < 0 || maxArchiveRowId < 0) {
                throw invalidCursor();
            }
            boolean emptyFrontier = preIntentMaxMessageId == -1 && preIntentMaxSeq == -1;
            boolean populatedFrontier = preIntentMaxMessageId >= 0 && preIntentMaxSeq >= 0;
            if (!emptyFrontier && !populatedFrontier) throw invalidCursor();
            if (emptyFrontier && (maxMessageId != 0 || maxMessageSeq != 0
                    || maxSummaryId != 0 || maxArchiveRowId != 0)) {
                throw invalidCursor();
            }
            if (populatedFrontier
                    && (maxMessageId > preIntentMaxMessageId || maxMessageSeq > preIntentMaxSeq)) {
                throw invalidCursor();
            }
        }
    }

    public record SourceWatermarks(
            long messageWatermark,
            long summaryWatermark,
            long archiveWatermark,
            boolean messagesExhausted,
            boolean summariesExhausted,
            boolean archivesExhausted,
            long scanCount) {
        public SourceWatermarks {
            if (messageWatermark < 0 || summaryWatermark < 0 || archiveWatermark < 0
                    || scanCount < 0) {
                throw invalidCursor();
            }
        }
    }

    public record SearchCursor(
            String selectorHash,
            int projectionVersion,
            SnapshotCutoff cutoff,
            SourceWatermarks watermarks,
            SearchPosition position) {
        public SearchCursor {
            requireBounded(selectorHash, "selectorHash");
            if (projectionVersion <= 0) throw invalidCursor();
            Objects.requireNonNull(cutoff, "cutoff");
            Objects.requireNonNull(watermarks, "watermarks");
        }

        public SearchCursor(
                String selectorHash,
                int projectionVersion,
                SnapshotCutoff cutoff,
                SourceWatermarks watermarks) {
            this(selectorHash, projectionVersion, cutoff, watermarks, null);
        }
    }

    /** Complete locator sort key; permits a page cut between blocks of the same message. */
    public record SearchPosition(
            int evidenceRank,
            long logicalSeq,
            long createdAtEpochSecond,
            int createdAtNano,
            int sourceOrder,
            String stableRef) {
        public SearchPosition {
            if (evidenceRank < 0 || evidenceRank > 1 || logicalSeq < 0 || createdAtNano < 0
                    || createdAtNano > 999_999_999 || sourceOrder < 0) {
                throw invalidCursor();
            }
            requireBounded(stableRef, "stableRef");
        }
    }

    public record EventReadCursor(
            String selectorHash,
            int projectionVersion,
            SnapshotCutoff cutoff,
            SourceWatermarks watermarks,
            String manifestHash,
            int eventIndex,
            int blockIndex,
            int codePointOffset) {
        public EventReadCursor {
            requireBounded(selectorHash, "selectorHash");
            requireBounded(manifestHash, "manifestHash");
            if (projectionVersion <= 0 || eventIndex < 0 || blockIndex < 0 || codePointOffset < 0) {
                throw invalidCursor();
            }
            Objects.requireNonNull(cutoff, "cutoff");
            Objects.requireNonNull(watermarks, "watermarks");
        }
    }

    public record BlockReadCursor(
            String selectorHash,
            int projectionVersion,
            SnapshotCutoff cutoff,
            String targetRef,
            String contentHash,
            int codePointOffset) {
        public BlockReadCursor {
            requireBounded(selectorHash, "selectorHash");
            requireBounded(targetRef, "targetRef");
            requireBounded(contentHash, "contentHash");
            if (projectionVersion <= 0 || codePointOffset < 0) throw invalidCursor();
            Objects.requireNonNull(cutoff, "cutoff");
        }
    }
}
