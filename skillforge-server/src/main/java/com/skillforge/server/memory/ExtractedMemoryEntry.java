package com.skillforge.server.memory;

/**
 * LLM extracted memory entry parsed from JSON output.
 *
 * @param type       memory category: knowledge, preference, feedback, project, reference
 * @param title      short descriptive title
 * @param content    memory content text
 * @param importance high, medium, or low
 */
public record ExtractedMemoryEntry(
        String type,
        String title,
        String content,
        String importance
) {
}
