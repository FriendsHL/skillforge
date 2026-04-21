package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memory extraction configuration.
 *
 * <pre>
 * skillforge:
 *   memory:
 *     extraction-mode: rule          # "rule" (Phase 2) or "llm" (Phase 3)
 *     extraction-provider: bailian   # null = use default LLM provider
 *     max-conversation-chars: 8000   # truncation limit for LLM input
 * </pre>
 */
@ConfigurationProperties(prefix = "skillforge.memory")
public class MemoryProperties {

    private String extractionMode = "rule";
    private String extractionProvider;
    private int maxConversationChars = 8000;

    public String getExtractionMode() {
        return extractionMode;
    }

    public void setExtractionMode(String extractionMode) {
        this.extractionMode = extractionMode;
    }

    public String getExtractionProvider() {
        return extractionProvider;
    }

    public void setExtractionProvider(String extractionProvider) {
        this.extractionProvider = extractionProvider;
    }

    public int getMaxConversationChars() {
        return maxConversationChars;
    }

    public void setMaxConversationChars(int maxConversationChars) {
        this.maxConversationChars = maxConversationChars;
    }

    public boolean isLlmMode() {
        return "llm".equalsIgnoreCase(extractionMode);
    }
}
