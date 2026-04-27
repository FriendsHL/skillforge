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
 *     extraction:
 *       idle-window-minutes: 30
 *       idle-scanner-interval-minutes: 10
 *       max-unextracted-turns: 30
 *       cooldown-minutes: 5
 *       empty-result-cooldown-minutes: 15
 *     dedup:
 *       cosine-update-threshold: 0.95
 *       cosine-merge-threshold: 0.85
 * </pre>
 */
@ConfigurationProperties(prefix = "skillforge.memory")
public class MemoryProperties {

    private String extractionMode = "rule";
    private String extractionProvider;
    private int maxConversationChars = 8000;
    private Extraction extraction = new Extraction();
    private Dedup dedup = new Dedup();

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

    public Extraction getExtraction() {
        return extraction;
    }

    public void setExtraction(Extraction extraction) {
        this.extraction = extraction != null ? extraction : new Extraction();
    }

    public Dedup getDedup() {
        return dedup;
    }

    public void setDedup(Dedup dedup) {
        this.dedup = dedup != null ? dedup : new Dedup();
    }

    public boolean isLlmMode() {
        return "llm".equalsIgnoreCase(extractionMode);
    }

    public static class Extraction {
        private int idleWindowMinutes = 30;
        private int idleScannerIntervalMinutes = 10;
        private int maxUnextractedTurns = 30;
        private int cooldownMinutes = 5;
        private int emptyResultCooldownMinutes = 15;

        public int getIdleWindowMinutes() {
            return idleWindowMinutes;
        }

        public void setIdleWindowMinutes(int idleWindowMinutes) {
            this.idleWindowMinutes = idleWindowMinutes;
        }

        public int getIdleScannerIntervalMinutes() {
            return idleScannerIntervalMinutes;
        }

        public void setIdleScannerIntervalMinutes(int idleScannerIntervalMinutes) {
            this.idleScannerIntervalMinutes = idleScannerIntervalMinutes;
        }

        public int getMaxUnextractedTurns() {
            return maxUnextractedTurns;
        }

        public void setMaxUnextractedTurns(int maxUnextractedTurns) {
            this.maxUnextractedTurns = maxUnextractedTurns;
        }

        public int getCooldownMinutes() {
            return cooldownMinutes;
        }

        public void setCooldownMinutes(int cooldownMinutes) {
            this.cooldownMinutes = cooldownMinutes;
        }

        public int getEmptyResultCooldownMinutes() {
            return emptyResultCooldownMinutes;
        }

        public void setEmptyResultCooldownMinutes(int emptyResultCooldownMinutes) {
            this.emptyResultCooldownMinutes = emptyResultCooldownMinutes;
        }
    }

    public static class Dedup {
        private double cosineUpdateThreshold = 0.95;
        private double cosineMergeThreshold = 0.85;

        public double getCosineUpdateThreshold() {
            return cosineUpdateThreshold;
        }

        public void setCosineUpdateThreshold(double cosineUpdateThreshold) {
            this.cosineUpdateThreshold = cosineUpdateThreshold;
        }

        public double getCosineMergeThreshold() {
            return cosineMergeThreshold;
        }

        public void setCosineMergeThreshold(double cosineMergeThreshold) {
            this.cosineMergeThreshold = cosineMergeThreshold;
        }
    }
}
