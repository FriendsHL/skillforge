package com.skillforge.server.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.MemoryProposalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * MEMORY-LLM-SYNTHESIS (V68): DTO for {@link MemoryProposalEntity} returned by the
 * admin endpoints.
 *
 * <p>FU-1: {@code sourceMemories} carries the inline preview rows the FE needs to
 * render the source memory pane.
 *
 * <p>r2 fix R2-2: {@code sourceMemoryIds} is exposed as {@code List<Long>} (not the
 * raw jsonb string) so the FE TypeScript layer can do {@code .length} arithmetic
 * directly. Previously Jackson emitted a JSON string like {@code "[1, 2]"} which
 * the FE consumed as a 6-char string, causing the B-3 mass-delete confirmation
 * modal to misfire on any dedup ("Archive 6 rows" instead of "Archive 2 rows").
 */
public record MemoryProposalDto(
        Long id,
        Long userId,
        String synthesisRunId,
        String proposalType,
        List<Long> sourceMemoryIds,
        List<MemorySourceSummary> sourceMemories,
        Long winnerMemoryId,
        String suggestedTitle,
        String suggestedContent,
        String suggestedImportance,
        String reasoning,
        JsonNode evidence,
        String llmResponseExcerpt,
        String status,
        Long reviewedByUserId,
        Instant reviewedAt,
        Instant createdAt,
        Instant autoArchiveAfter) {

    private static final Logger log = LoggerFactory.getLogger(MemoryProposalDto.class);
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {};
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    /**
     * Build without source enrichment using a caller-supplied {@link ObjectMapper}.
     * Preferred form — caller passes the Spring-managed bean.
     */
    public static MemoryProposalDto from(MemoryProposalEntity e, ObjectMapper mapper) {
        return from(e, null, mapper);
    }

    /**
     * Build with enriched source-memory previews. Caller-supplied mapper used to
     * parse the entity's jsonb-string {@code sourceMemoryIds} once.
     */
    public static MemoryProposalDto from(MemoryProposalEntity e,
                                          List<MemorySourceSummary> sourceMemories,
                                          ObjectMapper mapper) {
        return new MemoryProposalDto(
                e.getId(),
                e.getUserId(),
                e.getSynthesisRunId(),
                e.getProposalType(),
                parseSourceIds(e.getSourceMemoryIds(), mapper),
                sourceMemories,
                e.getWinnerMemoryId(),
                e.getSuggestedTitle(),
                e.getSuggestedContent(),
                e.getSuggestedImportance(),
                e.getReasoning(),
                parseEvidence(e.getEvidenceJson(), mapper),
                e.getLlmResponseExcerpt(),
                e.getStatus(),
                e.getReviewedByUserId(),
                e.getReviewedAt(),
                e.getCreatedAt(),
                e.getAutoArchiveAfter());
    }

    /**
     * Backward-compat factory for callers that don't have an injected ObjectMapper
     * (e.g. tests). Uses a static fallback mapper — fine because we only ever read
     * a fixed JSON-array-of-Long shape, no time / type serialization concerns.
     */
    public static MemoryProposalDto from(MemoryProposalEntity e) {
        return from(e, null, FALLBACK_MAPPER);
    }

    /** Backward-compat factory matching the older 2-arg shape (entity + summaries). */
    public static MemoryProposalDto from(MemoryProposalEntity e,
                                          List<MemorySourceSummary> sourceMemories) {
        return from(e, sourceMemories, FALLBACK_MAPPER);
    }

    private static List<Long> parseSourceIds(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return List.of();
        ObjectMapper m = mapper != null ? mapper : FALLBACK_MAPPER;
        try {
            List<Long> parsed = m.readValue(json, LONG_LIST);
            return parsed != null ? parsed : List.of();
        } catch (Exception ex) {
            log.warn("MemoryProposalDto: failed to parse sourceMemoryIds={}: {}", json, ex.getMessage());
            return List.of();
        }
    }

    private static JsonNode parseEvidence(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return null;
        ObjectMapper m = mapper != null ? mapper : FALLBACK_MAPPER;
        try {
            return m.readTree(json);
        } catch (Exception ex) {
            log.warn("MemoryProposalDto: failed to parse evidenceJson={}: {}", json, ex.getMessage());
            return null;
        }
    }
}
