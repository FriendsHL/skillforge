package com.skillforge.core.reminder;

import java.util.List;

/**
 * Preserves the existing provider and persistence bytes while ReminderBuilder
 * internally works with typed entries.
 */
final class ReminderCompatibilityRenderer {

    String render(List<ReminderEntry> entries) {
        if (entries == null || entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(256);
        sb.append("<system-reminder>\n");
        for (ReminderEntry entry : entries) {
            sb.append(entry.text());
            if (!entry.text().endsWith("\n")) sb.append('\n');
        }
        sb.append("</system-reminder>\n");
        return sb.toString();
    }
}
