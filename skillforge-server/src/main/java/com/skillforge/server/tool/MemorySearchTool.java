package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.dto.MemorySearchResult;
import com.skillforge.server.service.EmbeddingService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hybrid memory search skill using FTS + pgvector with RRF merge.
 * Returns snippets (first 100 chars) with memoryId; use memory_detail for full content.
 */
public class MemorySearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MemorySearchTool.class);

    private static final int FTS_LIMIT = 20;
    private static final int VEC_LIMIT = 20;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final int RRF_K = 60;

    private final MemoryService memoryService;
    private final EmbeddingService embeddingService;

    public MemorySearchTool(MemoryService memoryService, EmbeddingService embeddingService) {
        this.memoryService = memoryService;
        this.embeddingService = embeddingService;
    }

    @Override
    public String getName() {
        return "memory_search";
    }

    @Override
    public String getDescription() {
        return "Search long-term memories by semantic similarity and keyword matching. "
                + "Returns a ranked list of snippets (first 100 chars) with memoryId. "
                + "Call memory_detail to fetch the full content of a specific memory. "
                + "Use this before answering any question that might benefit from past context.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("userId", Map.of(
                "type", "integer",
                "description", "The user ID"
        ));
        properties.put("query", Map.of(
                "type", "string",
                "description", "Natural language search query"
        ));
        properties.put("topK", Map.of(
                "type", "integer",
                "description", "Max results to return (default 5, max 20)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("userId", "query"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            Long userId = SkillInputUtils.toLong(input.get("userId"));
            String query = (String) input.get("query");
            int topK = SkillInputUtils.toInt(input.get("topK"), DEFAULT_TOP_K);
            if (topK <= 0 || topK > MAX_TOP_K) topK = DEFAULT_TOP_K;

            if (userId == null || query == null || query.isBlank()) {
                return SkillResult.error("userId and query are required");
            }

            // FTS recall (always executed)
            List<MemorySearchResult> ftsResults = memoryService.searchByFts(userId, query, FTS_LIMIT);

            // Vector recall (executed when embedding is available)
            List<MemorySearchResult> vectorResults = embeddingService.embed(query)
                    .map(vec -> memoryService.searchByVector(userId, vec, VEC_LIMIT))
                    .orElse(List.of());

            // RRF merge
            List<MemorySearchResult> merged = mergeWithRrf(ftsResults, vectorResults, topK);

            if (merged.isEmpty()) {
                return SkillResult.success("No memories found for: " + query);
            }

            String output = merged.stream()
                    .map(r -> String.format("[id=%d, type=%s, score=%.3f] %s: %s",
                            r.memoryId(), r.type(), r.score(), r.title(), r.snippet()))
                    .collect(Collectors.joining("\n"));

            return SkillResult.success("Found " + merged.size() + " memories:\n" + output);
        } catch (Exception e) {
            log.warn("Memory search failed: {}", e.getMessage(), e);
            return SkillResult.error("Memory search failed");
        }
    }

    /**
     * Reciprocal Rank Fusion: merge FTS and vector results into a unified ranking.
     */
    private List<MemorySearchResult> mergeWithRrf(
            List<MemorySearchResult> ftsResults,
            List<MemorySearchResult> vectorResults,
            int topK) {

        Map<Long, Double> scores = new HashMap<>();

        for (int i = 0; i < ftsResults.size(); i++) {
            long id = ftsResults.get(i).memoryId();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < vectorResults.size(); i++) {
            long id = vectorResults.get(i).memoryId();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        }

        // Merge candidates from both sources, keyed by id
        Map<Long, MemorySearchResult> byId = new HashMap<>();
        ftsResults.forEach(r -> byId.put(r.memoryId(), r));
        vectorResults.forEach(r -> byId.putIfAbsent(r.memoryId(), r));

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }
}
