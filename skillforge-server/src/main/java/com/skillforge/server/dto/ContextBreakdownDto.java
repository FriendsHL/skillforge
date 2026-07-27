package com.skillforge.server.dto;

import java.util.List;

/**
 * Breakdown of the estimated tokens currently occupying a session's LLM context window.
 *
 * <p>Values come from {@link com.skillforge.core.compact.TokenEstimator} — ±10% precision —
 * sufficient to tell users what is taking up their budget, not an exact billing counter.
 */
public record ContextBreakdownDto(
        String sessionId,
        long total,
        long windowLimit,
        int pct,
        List<Segment> segments,
        ObservationSummary observation) {

    public ContextBreakdownDto(
            String sessionId,
            long total,
            long windowLimit,
            int pct,
            List<Segment> segments) {
        this(sessionId, total, windowLimit, pct, segments, null);
    }

    /**
     * A single segment. May carry optional children for hierarchical display.
     * Leaf segments pass {@code null} for {@code children}.
     */
    public record Segment(
            String key,
            String label,
            long tokens,
            List<Segment> children,
            SegmentMetadata metadata) {

        public Segment(String key, String label, long tokens, List<Segment> children) {
            this(key, label, tokens, children, null);
        }

        public static Segment leaf(String key, String label, long tokens) {
            return new Segment(key, label, tokens, null, null);
        }

        public static Segment observed(
                String key, String label, long tokens, SegmentMetadata metadata) {
            return new Segment(key, label, tokens, null, metadata);
        }
    }

    /**
     * Content-free diagnostic metadata. No prompt body, memory text, or secret is exposed.
     */
    public record SegmentMetadata(
            String sourceType,
            String placement,
            Boolean stable,
            Boolean cacheable,
            String contentHash,
            String kind,
            String source,
            String exposureReason) {
    }

    public record ObservationSummary(
            String stablePrefixHash,
            String assemblyHash,
            String toolSchemasHash,
            long durationMicros) {
    }
}
