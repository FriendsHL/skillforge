package com.skillforge.core.reminder;

import com.skillforge.core.context.ContextAttachment;

import java.util.List;

/**
 * Structured reminder output. The compatibility text remains the only value
 * written into Message content. Attachments retain content for future renderers;
 * diagnostics must use {@link ReminderObservation}, which stores only hashes.
 */
public record ReminderBuildResult(
        String renderedText,
        List<ReminderEntry> entries,
        List<ContextAttachment> attachments) {

    public ReminderBuildResult {
        renderedText = renderedText == null ? "" : renderedText;
        entries = entries == null ? List.of() : List.copyOf(entries);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public static ReminderBuildResult empty() {
        return new ReminderBuildResult("", List.of(), List.of());
    }
}
