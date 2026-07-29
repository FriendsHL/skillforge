package com.skillforge.core.reminder;

import com.skillforge.core.context.ContextLifecycle;
import com.skillforge.core.context.PromptCompactPolicy;
import com.skillforge.core.context.PromptPlacement;

import java.time.Instant;

/**
 * Content-free metadata for Context Breakdown and traces.
 */
public record ReminderObservation(
        String id,
        ReminderSourceType source,
        ReminderSeverity severity,
        ReminderReasonCode reasonCode,
        int estimatedTokens,
        PromptPlacement placement,
        ContextLifecycle lifecycle,
        PromptCompactPolicy compactPolicy,
        Instant expiresAt,
        String contentHash) {
}
