package com.skillforge.core.context;

import java.util.Locale;
import java.util.Optional;

/** Classifies provider-visible tool results that contain non-instruction data. */
public final class ToolResultTrustClassifier {

    private ToolResultTrustClassifier() {
    }

    public static Optional<PromptSourceType> classify(String toolName) {
        if (toolName == null) return Optional.empty();
        String normalized = toolName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("mcp_")
                || normalized.equals("websearch")
                || normalized.equals("webfetch")) {
            return Optional.of(PromptSourceType.WEB);
        }
        if (normalized.equals("read")
                || normalized.equals("grep")
                || normalized.equals("glob")) {
            return Optional.of(PromptSourceType.FILE);
        }
        if (normalized.equals("memory_search")
                || normalized.equals("memorydetail")
                || normalized.equals("listrelevantmemories")) {
            return Optional.of(PromptSourceType.RAG);
        }
        return Optional.empty();
    }
}
