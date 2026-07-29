package com.skillforge.server.dto;

/**
 * DTO for memory search results. Contains snippet for list views;
 * full content available via memory_detail tool.
 */
public record MemorySearchResult(
        long memoryId,
        String type,
        String title,
        String content,
        double score,
        String provenanceSource,
        String confirmationStatus,
        Double confidence,
        Long version
) {
    public MemorySearchResult(
            long memoryId,
            String type,
            String title,
            String content,
            double score) {
        this(memoryId, type, title, content, score,
                "LEGACY_UNKNOWN", "UNVERIFIED", null, 0L);
    }

    public String snippet() {
        if (content == null) return "";
        return content.length() <= 100 ? content : content.substring(0, 100) + "\u2026";
    }

    public MemorySearchResult withScore(double newScore) {
        return new MemorySearchResult(
                memoryId, type, title, content, newScore,
                provenanceSource, confirmationStatus, confidence, version);
    }
}
