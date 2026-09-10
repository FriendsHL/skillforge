package com.skillforge.server.history;

import com.skillforge.core.skill.SkillContext;

import java.util.Objects;

/**
 * Server-authoritative identity and snapshot ceiling for one History execution.
 *
 * <p>No model-supplied map participates in construction. The durable-attempt layer supplies
 * the epoch and pre-intent frontier; the Harness supplies current Session/user/tool-use identity.
 */
public final class CurrentSessionHistoryScope {

    private final String sessionId;
    private final long userId;
    private final long historyEpoch;
    private final long preIntentMaxMessageId;
    private final long preIntentMaxSeq;
    private final String currentToolUseId;

    private CurrentSessionHistoryScope(
            String sessionId,
            long userId,
            long historyEpoch,
            long preIntentMaxMessageId,
            long preIntentMaxSeq,
            String currentToolUseId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.historyEpoch = historyEpoch;
        this.preIntentMaxMessageId = preIntentMaxMessageId;
        this.preIntentMaxSeq = preIntentMaxSeq;
        this.currentToolUseId = currentToolUseId;
    }

    public static CurrentSessionHistoryScope from(
            SkillContext context,
            long historyEpoch,
            long preIntentMaxMessageId,
            long preIntentMaxSeq) {
        Objects.requireNonNull(context, "context");
        String sessionId = context.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("History requires a trusted current Session");
        }
        Long userId = context.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("History requires a trusted current user");
        }
        if (historyEpoch < 0) {
            throw new IllegalArgumentException("History history epoch must be >= 0");
        }
        boolean emptyFrontier = preIntentMaxMessageId == -1 && preIntentMaxSeq == -1;
        boolean populatedFrontier = preIntentMaxMessageId >= 0 && preIntentMaxSeq >= 0;
        if (!emptyFrontier && !populatedFrontier) {
            throw new IllegalArgumentException(
                    "History pre-intent frontier must be both empty or both populated");
        }
        String toolUseId = context.getToolUseId();
        if (toolUseId != null && toolUseId.isBlank()) {
            throw new IllegalArgumentException("History current tool use ID must not be blank");
        }
        return new CurrentSessionHistoryScope(
                sessionId, userId, historyEpoch, preIntentMaxMessageId, preIntentMaxSeq, toolUseId);
    }

    public String sessionId() {
        return sessionId;
    }

    public long userId() {
        return userId;
    }

    public long historyEpoch() {
        return historyEpoch;
    }

    public long preIntentMaxMessageId() {
        return preIntentMaxMessageId;
    }

    public long preIntentMaxSeq() {
        return preIntentMaxSeq;
    }

    public String currentToolUseId() {
        return currentToolUseId;
    }
}
