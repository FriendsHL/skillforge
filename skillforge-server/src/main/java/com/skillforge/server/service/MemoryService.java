package com.skillforge.server.service;

import com.skillforge.server.dto.MemorySearchResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.skillforge.server.util.VectorUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryRepository memoryRepository;
    private final MemoryEmbeddingWorker embeddingWorker;
    private final EmbeddingService embeddingService;

    public MemoryService(MemoryRepository memoryRepository, MemoryEmbeddingWorker embeddingWorker, EmbeddingService embeddingService) {
        this.memoryRepository = memoryRepository;
        this.embeddingWorker = embeddingWorker;
        this.embeddingService = embeddingService;
    }

    public List<MemoryEntity> listMemories(Long userId, String type) {
        if (type != null && !type.isBlank()) {
            return memoryRepository.findByUserIdAndType(userId, type);
        }
        return memoryRepository.findByUserId(userId);
    }

    public List<MemoryEntity> searchMemories(Long userId, String keyword) {
        return memoryRepository.findByUserIdAndContentContaining(userId, keyword);
    }

    @Transactional
    public MemoryEntity createMemory(MemoryEntity memory) {
        MemoryEntity saved = memoryRepository.save(memory);
        scheduleEmbeddingAfterCommit(saved);
        return saved;
    }

    @Transactional
    public MemoryEntity updateMemory(Long id, MemoryEntity memory) {
        MemoryEntity existing = memoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Memory not found: " + id));
        existing.setType(memory.getType());
        existing.setTitle(memory.getTitle());
        existing.setContent(memory.getContent());
        existing.setTags(memory.getTags());
        MemoryEntity saved = memoryRepository.save(existing);
        scheduleEmbeddingAfterCommit(saved);
        return saved;
    }

    public Optional<MemoryEntity> findById(Long id) {
        return memoryRepository.findById(id);
    }

    /**
     * Full-text search via tsvector.
     */
    public List<MemorySearchResult> searchByFts(Long userId, String query, int limit) {
        return memoryRepository.findByFts(userId, query, limit).stream()
                .map(this::toSearchResult)
                .toList();
    }

    /**
     * Vector similarity search via pgvector cosine distance.
     */
    public List<MemorySearchResult> searchByVector(Long userId, float[] vec, int limit) {
        String embedding = VectorUtils.toVectorString(vec);
        return memoryRepository.findByVector(userId, embedding, limit).stream()
                .map(this::toSearchResult)
                .toList();
    }

    private MemorySearchResult toSearchResult(Object[] row) {
        long id = ((Number) row[0]).longValue();
        String type = (String) row[1];
        String title = (String) row[2];
        String content = (String) row[3];
        // row[4] = tags, row[5] = recall_count, row[6] = rank/distance
        double score = row[6] != null ? ((Number) row[6]).doubleValue() : 0.0;
        return new MemorySearchResult(id, type, title, content, score);
    }

    private String buildEmbedText(MemoryEntity m) {
        StringBuilder sb = new StringBuilder();
        if (m.getTitle() != null) sb.append(m.getTitle()).append("\n");
        if (m.getContent() != null) sb.append(m.getContent());
        if (m.getTags() != null) sb.append("\nTags: ").append(m.getTags());
        return sb.toString();
    }

    /**
     * Schedule async embedding generation to fire only after the current transaction commits.
     * This avoids a race condition where the async thread tries to UPDATE a row
     * that hasn't been committed yet.
     */
    private void scheduleEmbeddingAfterCommit(MemoryEntity saved) {
        String text = buildEmbedText(saved);
        Long memoryId = saved.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                embeddingWorker.triggerEmbeddingAsync(memoryId, text);
            }
        });
    }

    public void deleteMemory(Long id) {
        memoryRepository.deleteById(id);
    }

    @Transactional
    public void createMemoryIfNotDuplicate(Long userId, String type, String title, String content, String tags) {
        List<MemoryEntity> existing = memoryRepository.findByUserIdAndTitle(userId, title);
        if (!existing.isEmpty()) {
            MemoryEntity e = existing.get(0);
            e.setContent(content);
            e.setTags(tags);
            MemoryEntity saved = memoryRepository.save(e);
            scheduleEmbeddingAfterCommit(saved);
            return;
        }
        MemoryEntity entity = new MemoryEntity();
        entity.setUserId(userId);
        entity.setType(type);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setTags(tags);
        MemoryEntity saved = memoryRepository.save(entity);
        scheduleEmbeddingAfterCommit(saved);
    }

    public List<MemoryEntity> searchWithRanking(Long userId, String query) {
        if (query == null || query.isBlank()) return listMemories(userId, null);

        String[] terms = query.toLowerCase().split("\\s+");
        List<MemoryEntity> all = memoryRepository.findByUserId(userId);

        return all.stream()
                .map(m -> new AbstractMap.SimpleEntry<>(m, calculateScore(m, terms)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<MemoryEntity, Double>comparingByValue().reversed())
                .limit(15)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private double calculateScore(MemoryEntity m, String[] terms) {
        String text = ((m.getTitle() != null ? m.getTitle() : "") + " "
                + (m.getContent() != null ? m.getContent() : "") + " "
                + (m.getTags() != null ? m.getTags() : "")).toLowerCase();

        long matchCount = Arrays.stream(terms).filter(text::contains).count();
        if (matchCount == 0) return 0;

        double daysSinceUpdate = m.getUpdatedAt() != null
                ? Duration.between(m.getUpdatedAt(), LocalDateTime.now()).toDays()
                : 90;
        if (daysSinceUpdate < 0) daysSinceUpdate = 0;
        double recencyBoost = Math.exp(-Math.log(2) / 30.0 * daysSinceUpdate);

        double recallBoost = 1.0 + Math.min(m.getRecallCount(), 10) * 0.1;

        return matchCount * recencyBoost * recallBoost;
    }

    /**
     * Get memories for prompt injection.
     * Legacy version: no semantic context, falls back to time-based ranking.
     */
    @Transactional
    public String getMemoriesForPrompt(Long userId) {
        return getMemoriesForPrompt(userId, null);
    }

    /**
     * Get memories for prompt injection with semantic search.
     *
     * @param userId the user ID
     * @param taskContext optional current task context for semantic retrieval
     * @return formatted memories for injection into agent prompt
     */
    @Transactional
    public String getMemoriesForPrompt(Long userId, String taskContext) {
        List<Long> injectedIds = new ArrayList<>();
        String rendered = renderMemoriesForPrompt(userId, taskContext, injectedIds);
        if (!injectedIds.isEmpty()) {
            Instant now = Instant.now();
            for (Long id : injectedIds) {
                memoryRepository.incrementRecallCount(id, now);
            }
        }
        return rendered;
    }

    /**
     * Render the memories-for-prompt block without bumping recall counts. For read-only
     * callers (e.g. context-breakdown estimation) — returns the same text a real
     * prompt-injection call would produce, but with zero side effects.
     */
    @Transactional(readOnly = true)
    public String previewMemoriesForPrompt(Long userId, String taskContext) {
        return renderMemoriesForPrompt(userId, taskContext, new ArrayList<>());
    }

    private String renderMemoriesForPrompt(Long userId, String taskContext,
                                           List<Long> injectedIds) {
        StringBuilder sb = new StringBuilder();

        // Always inject preferences and feedback (time-based, most relevant for behavior)
        List<MemoryEntity> all = memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (all.isEmpty()) return "";

        Map<String, List<MemoryEntity>> byType = all.stream()
                .collect(Collectors.groupingBy(m -> m.getType() != null ? m.getType() : "knowledge"));

        appendTypeMemories(sb, byType.get("preference"), "Preferences", 10, injectedIds);
        appendTypeMemories(sb, byType.get("feedback"), "Feedback", 10, injectedIds);

        // For knowledge/project/reference: use semantic search if taskContext is provided
        if (taskContext != null && !taskContext.isBlank()) {
            appendSemanticallyRankedMemories(sb, userId, taskContext, byType, injectedIds);
        } else {
            // Fallback to time-based ranking
            List<MemoryEntity> kpr = new ArrayList<>();
            if (byType.containsKey("knowledge")) kpr.addAll(byType.get("knowledge"));
            if (byType.containsKey("project")) kpr.addAll(byType.get("project"));
            if (byType.containsKey("reference")) kpr.addAll(byType.get("reference"));
            kpr.sort(Comparator.comparing(MemoryEntity::getUpdatedAt).reversed());
            appendTypeMemories(sb, kpr.subList(0, Math.min(10, kpr.size())), "Knowledge & Context", 10, injectedIds);
        }

        return sb.toString();
    }

    /**
     * Append memories ranked by semantic similarity to the task context.
     * Uses hybrid search (FTS + Vector) with RRF fusion, similar to MemorySearchTool.
     */
    private void appendSemanticallyRankedMemories(
            StringBuilder sb,
            Long userId,
            String taskContext,
            Map<String, List<MemoryEntity>> byType,
            List<Long> injectedIds) {

        // Filter to knowledge/project/reference types only
        List<MemoryEntity> candidates = new ArrayList<>();
        for (String type : List.of("knowledge", "project", "reference")) {
            if (byType.containsKey(type)) {
                candidates.addAll(byType.get(type));
            }
        }
        if (candidates.isEmpty()) return;

        // FTS recall
        List<MemorySearchResult> ftsResults = searchByFts(userId, taskContext, 20);

        // Vector recall
        List<MemorySearchResult> vectorResults = embeddingService.embed(taskContext)
                .map(vec -> searchByVector(userId, vec, 20))
                .orElse(List.of());

        // RRF merge
        List<MemorySearchResult> merged = mergeWithRrf(ftsResults, vectorResults, 15);

        if (merged.isEmpty()) {
            // Fallback to time-based if semantic search returns nothing
            appendTypeMemories(sb, candidates.subList(0, Math.min(10, candidates.size())), 
                    "Knowledge & Context", 10, injectedIds);
            return;
        }

        // Append semantically ranked memories
        sb.append("### Knowledge & Context (ranked by relevance)\n");
        for (MemorySearchResult result : merged) {
            if (sb.length() >= MAX_TOTAL_CHARS) break;
            sb.append("- **").append(result.title()).append("**: ");
            String content = result.content();
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS) + "...[truncated]";
            }
            sb.append(content).append("\n");
            injectedIds.add(result.memoryId());
        }
        sb.append("\n");
    }

    /**
     * Reciprocal Rank Fusion: merge FTS and vector results into a unified ranking.
     */
    private List<MemorySearchResult> mergeWithRrf(
            List<MemorySearchResult> ftsResults,
            List<MemorySearchResult> vectorResults,
            int topK) {

        java.util.Map<Long, Double> scores = new java.util.HashMap<>();
        int RRF_K = 60;

        for (int i = 0; i < ftsResults.size(); i++) {
            long id = ftsResults.get(i).memoryId();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < vectorResults.size(); i++) {
            long id = vectorResults.get(i).memoryId();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        }

        // Merge candidates from both sources, keyed by id
        java.util.Map<Long, MemorySearchResult> byId = new java.util.HashMap<>();
        ftsResults.forEach(r -> byId.put(r.memoryId(), r));
        vectorResults.forEach(r -> byId.putIfAbsent(r.memoryId(), r));

        return scores.entrySet().stream()
                .sorted(java.util.Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }

    private static final int MAX_CONTENT_CHARS = 500;
    private static final int MAX_TOTAL_CHARS = 8000;

    private void appendTypeMemories(StringBuilder sb, List<MemoryEntity> memories, String section, int cap, List<Long> injectedIds) {
        if (memories == null || memories.isEmpty()) return;
        sb.append("### ").append(section).append("\n");
        int count = 0;
        for (MemoryEntity m : memories) {
            if (count >= cap || sb.length() >= MAX_TOTAL_CHARS) break;
            sb.append("- ");
            if (m.getTitle() != null) sb.append("**").append(m.getTitle()).append("**: ");
            String content = m.getContent() != null ? m.getContent() : "";
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS) + "...[truncated]";
            }
            sb.append(content).append("\n");
            if (m.getId() != null) injectedIds.add(m.getId());
            count++;
        }
        sb.append("\n");
    }
}
