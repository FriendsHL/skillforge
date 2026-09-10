package com.skillforge.server.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.skill.SkillContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryCursorCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HistoryCursorCodec codec = new HistoryCursorCodec(objectMapper);
    private final CurrentSessionHistoryScope scope = scope("session-a", 91L, 4L);
    private final HistoryCursorCodec.SnapshotCutoff cutoff =
            new HistoryCursorCodec.SnapshotCutoff(77, 66, 9, 12, 100, 90);

    @Test
    void searchCursorRoundTripsFrozenCutoffAndWatermarks() {
        HistoryCursorCodec.SearchCursor value = new HistoryCursorCodec.SearchCursor(
                "selector-sha", 3, cutoff,
                new HistoryCursorCodec.SourceWatermarks(90, 8, 11, false, true, false, 120),
                new HistoryCursorCodec.SearchPosition(
                        0, 64, 1_700_000_000L, 123_000_000, 2,
                        "msg:e4:id77:block2"));

        String token = codec.encodeSearch(scope, value);

        assertThat(token).doesNotContain("session-a");
        assertThat(token.length()).isLessThanOrEqualTo(4096);
        assertThat(codec.decodeSearch(scope, token, "selector-sha", 3)).isEqualTo(value);
    }

    @Test
    void searchCursorBindsTheFullLastEmittedBlockSortKey() {
        HistoryCursorCodec.SearchCursor value = new HistoryCursorCodec.SearchCursor(
                "selector-sha", 3, cutoff,
                new HistoryCursorCodec.SourceWatermarks(90, 8, 11, false, false, false, 8),
                new HistoryCursorCodec.SearchPosition(
                        0, 64, 1_700_000_000L, 123_000_000, 2,
                        "msg:e4:id77:block2"));

        String token = codec.encodeSearch(scope, value);

        assertThat(codec.decodeSearch(scope, token, "selector-sha", 3).position())
                .isEqualTo(value.position());
    }

    @Test
    void cursorBindsCurrentSessionEpochSelectorAndProjection() {
        String token = codec.encodeEvent(scope, new HistoryCursorCodec.EventReadCursor(
                "selector-sha", 3, cutoff,
                new HistoryCursorCodec.SourceWatermarks(90, 8, 11, false, false, false, 40),
                "manifest-sha", 2, 1, 6));

        assertCode(() -> codec.decodeEvent(scope("session-b", 91, 4), token,
                "selector-sha", 3, "manifest-sha"), "INVALID_CURSOR");
        assertCode(() -> codec.decodeEvent(scope("session-a", 91, 5), token,
                "selector-sha", 3, "manifest-sha"), "HISTORY_STALE");
        assertCode(() -> codec.decodeEvent(scope, token,
                "changed-selector", 3, "manifest-sha"), "CURSOR_SELECTOR_MISMATCH");
        assertCode(() -> codec.decodeEvent(scope, token,
                "selector-sha", 4, "manifest-sha"), "HISTORY_PROJECTION_CHANGED");
        assertCode(() -> codec.decodeEvent(scope, token,
                "selector-sha", 3, "changed-manifest"), "HISTORY_CONTENT_CHANGED");
    }

    @Test
    void blockAndEventDiscriminantsCannotBeCrossDecoded() {
        String block = codec.encodeBlock(scope, new HistoryCursorCodec.BlockReadCursor(
                "selector-sha", 3, cutoff, "archive:e4:idabc", "content-sha", 12));
        String event = codec.encodeEvent(scope, new HistoryCursorCodec.EventReadCursor(
                "selector-sha", 3, cutoff,
                new HistoryCursorCodec.SourceWatermarks(1, 2, 3, true, true, true, 3),
                "manifest-sha", 0, 0, 0));

        assertCode(() -> codec.decodeEvent(scope, block,
                "selector-sha", 3, "manifest-sha"), "INVALID_CURSOR");
        assertCode(() -> codec.decodeBlock(scope, event,
                "selector-sha", 3, "content-sha"), "INVALID_CURSOR");
    }

    @Test
    void rejectsMalformedExtraAndCutoffMutationAsInvalidCursor() throws Exception {
        String token = codec.encodeSearch(scope, new HistoryCursorCodec.SearchCursor(
                "selector-sha", 3, cutoff,
                new HistoryCursorCodec.SourceWatermarks(1, 2, 3, false, false, false, 3)));

        assertCode(() -> codec.decodeSearch(scope, "***", "selector-sha", 3),
                "INVALID_CURSOR");
        assertCode(() -> codec.decodeSearch(scope, "a".repeat(4097), "selector-sha", 3),
                "INVALID_CURSOR");

        ObjectNode decoded = (ObjectNode) objectMapper.readTree(Base64.getUrlDecoder().decode(token));
        decoded.put("unexpected", true);
        assertCode(() -> codec.decodeSearch(scope, encode(decoded), "selector-sha", 3),
                "INVALID_CURSOR");

        ObjectNode mutated = decoded.deepCopy();
        mutated.remove("unexpected");
        ((ObjectNode) mutated.get("cutoff")).put("maxMessageSeq", 999);
        assertCode(() -> codec.decodeSearch(scope, encode(mutated), "selector-sha", 3),
                "INVALID_CURSOR");
    }

    @Test
    void rejectsRecomputedCursorWithImpossibleSearchSortKey() throws Exception {
        String token = codec.encodeSearch(scope, new HistoryCursorCodec.SearchCursor(
                "selector-sha", 3, cutoff,
                new HistoryCursorCodec.SourceWatermarks(1, 2, 3, false, false, false, 3),
                new HistoryCursorCodec.SearchPosition(
                        0, 10, 1_700_000_000L, 0, 0, "msg:e4:id1:block0")));
        ObjectNode forged = (ObjectNode) objectMapper.readTree(Base64.getUrlDecoder().decode(token));
        ((ObjectNode) forged.get("position")).put("evidenceRank", 2);
        String forgedToken = recomputeIntegrity(forged);

        assertCode(() -> codec.decodeSearch(
                scope, forgedToken, "selector-sha", 3), "INVALID_CURSOR");
    }

    @Test
    void blockCursorUsesUnicodeCodePointOffsetAndContentBinding() {
        HistoryCursorCodec.BlockReadCursor value = new HistoryCursorCodec.BlockReadCursor(
                "selector-sha", 3, cutoff, "archive:e4:idabc", "content-sha", 2);
        String token = codec.encodeBlock(scope, value);

        assertThat(codec.decodeBlock(scope, token,
                "selector-sha", 3, "content-sha")).isEqualTo(value);
        assertCode(() -> codec.decodeBlock(scope, token,
                "selector-sha", 3, "other-content"), "HISTORY_CONTENT_CHANGED");
    }

    @Test
    void recomputedPublicDigestCannotExpandSnapshotBeyondTrustedPreIntentFrontier()
            throws Exception {
        String token = codec.encodeSearch(scope, new HistoryCursorCodec.SearchCursor(
                "selector-sha", 3, cutoff,
                new HistoryCursorCodec.SourceWatermarks(1, 2, 3, false, false, false, 3)));
        ObjectNode forged = (ObjectNode) objectMapper.readTree(Base64.getUrlDecoder().decode(token));
        ((ObjectNode) forged.get("cutoff")).put("maxMessageId", 101);
        String forgedToken = recomputeIntegrity(forged);

        assertCode(() -> codec.decodeSearch(
                scope, forgedToken, "selector-sha", 3), "INVALID_CURSOR");
    }

    @Test
    void cursorRequiresExactTrustedFrontierAndEmptyFrontierHasOnlyZeroCutoffs()
            throws Exception {
        String token = codec.encodeSearch(scope, new HistoryCursorCodec.SearchCursor(
                "selector-sha", 3, cutoff,
                new HistoryCursorCodec.SourceWatermarks(1, 2, 3, false, false, false, 3)));
        ObjectNode forged = (ObjectNode) objectMapper.readTree(Base64.getUrlDecoder().decode(token));
        ((ObjectNode) forged.get("cutoff")).put("preIntentMaxMessageId", 99);
        String forgedToken = recomputeIntegrity(forged);

        assertCode(() -> codec.decodeSearch(
                scope, forgedToken, "selector-sha", 3), "INVALID_CURSOR");

        CurrentSessionHistoryScope emptyScope = emptyScope("session-empty", 91, 4);
        HistoryCursorCodec.SnapshotCutoff emptyCutoff =
                new HistoryCursorCodec.SnapshotCutoff(0, 0, 0, 0, -1, -1);
        HistoryCursorCodec.SearchCursor emptyCursor = new HistoryCursorCodec.SearchCursor(
                "selector-sha", 3, emptyCutoff,
                new HistoryCursorCodec.SourceWatermarks(0, 0, 0, true, true, true, 0));

        assertThat(codec.decodeSearch(emptyScope, codec.encodeSearch(emptyScope, emptyCursor),
                "selector-sha", 3)).isEqualTo(emptyCursor);
        assertThatThrownBy(() -> new HistoryCursorCodec.SnapshotCutoff(1, 0, 0, 0, -1, -1))
                .isInstanceOf(HistoryProtocolException.class)
                .extracting(error -> ((HistoryProtocolException) error).getCode())
                .isEqualTo("INVALID_CURSOR");
    }

    private String encode(JsonNode node) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new AssertionError("Failed to encode cursor fixture", error);
        }
    }

    private String recomputeIntegrity(ObjectNode root) throws Exception {
        ObjectNode body = root.deepCopy();
        body.remove("integrity");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsBytes(body));
        root.put("integrity", HexFormat.of().formatHex(digest));
        return encode(root);
    }

    private static CurrentSessionHistoryScope scope(String sessionId, long userId, long epoch) {
        SkillContext context = new SkillContext();
        context.setSessionId(sessionId);
        context.setUserId(userId);
        context.setToolUseId("toolu_history");
        return CurrentSessionHistoryScope.from(context, epoch, 100, 90);
    }

    private static CurrentSessionHistoryScope emptyScope(
            String sessionId, long userId, long epoch) {
        SkillContext context = new SkillContext();
        context.setSessionId(sessionId);
        context.setUserId(userId);
        context.setToolUseId("toolu_history");
        return CurrentSessionHistoryScope.from(context, epoch, -1, -1);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(HistoryProtocolException.class)
                .extracting(error -> ((HistoryProtocolException) error).getCode())
                .isEqualTo(code);
    }
}
