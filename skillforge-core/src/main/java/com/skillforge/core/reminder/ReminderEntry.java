package com.skillforge.core.reminder;

import com.skillforge.core.context.ContextLifecycle;
import com.skillforge.core.context.PromptCompactPolicy;
import com.skillforge.core.context.PromptPlacement;

import java.time.Instant;

/**
 * REMINDER-MVP: one piece of reminder text emitted by a single {@link ReminderSource}.
 *
 * <p>{@link ReminderBuilder} concatenates emitted entries into the final
 * {@code <system-reminder>} block and budgets total tokens (PRD D7) using
 * {@link #estimatedTokens()}.
 *
 * <p>Immutable record — sources MUST return a fresh instance per emit so the builder
 * can safely accumulate.
 */
public record ReminderEntry(
        String id,
        ReminderSourceType source,
        ReminderSeverity severity,
        ReminderReasonCode reasonCode,
        String text,
        int estimatedTokens,
        int debounceTurns,
        PromptPlacement placement,
        ContextLifecycle lifecycle,
        PromptCompactPolicy compactPolicy,
        Instant expiresAt) {

    /**
     * Compatibility constructor for existing and third-party ReminderSource
     * implementations. ReminderBuilder fills source-specific defaults before
     * producing structured attachments.
     */
    public ReminderEntry(String text, int estimatedTokens) {
        this(null, ReminderSourceType.OTHER, ReminderSeverity.INFO,
                ReminderReasonCode.UNSPECIFIED, text, estimatedTokens, 0,
                PromptPlacement.BEFORE_NEXT_MODEL_CALL,
                ContextLifecycle.SINGLE_TURN,
                PromptCompactPolicy.DROP_ON_COMPACT,
                null);
    }

    public ReminderEntry {
        id = id == null ? "" : id.trim();
        source = source == null ? ReminderSourceType.OTHER : source;
        severity = severity == null ? ReminderSeverity.INFO : severity;
        reasonCode = reasonCode == null ? ReminderReasonCode.UNSPECIFIED : reasonCode;
        if (text == null) text = "";
        if (estimatedTokens < 0) estimatedTokens = 0;
        if (debounceTurns < 0) debounceTurns = 0;
        placement = placement == null
                ? PromptPlacement.BEFORE_NEXT_MODEL_CALL : placement;
        lifecycle = lifecycle == null ? ContextLifecycle.SINGLE_TURN : lifecycle;
        compactPolicy = compactPolicy == null
                ? PromptCompactPolicy.DROP_ON_COMPACT : compactPolicy;
    }

    ReminderEntry withSourceDefaults(String sourceName) {
        String stableId = id.isBlank() ? sourceName : id;
        ReminderSourceType stableSource = source == ReminderSourceType.OTHER
                ? sourceTypeFor(sourceName) : source;
        return new ReminderEntry(stableId, stableSource, severity, reasonCode, text,
                estimatedTokens, debounceTurns, placement, lifecycle, compactPolicy, expiresAt);
    }

    private static ReminderSourceType sourceTypeFor(String sourceName) {
        if (sourceName == null) return ReminderSourceType.OTHER;
        return switch (sourceName) {
            case ContextUsageSource.NAME -> ReminderSourceType.CONTEXT_USAGE;
            case MemoryAgeSource.NAME -> ReminderSourceType.MEMORY_AGE;
            case FileActivitySource.NAME -> ReminderSourceType.FILE_ACTIVITY;
            case "todo-list" -> ReminderSourceType.TODO_LIST;
            default -> ReminderSourceType.OTHER;
        };
    }
}
