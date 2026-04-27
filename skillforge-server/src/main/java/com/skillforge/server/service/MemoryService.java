package com.skillforge.server.service;

import com.skillforge.core.engine.MemoryInjection;
import com.skillforge.server.dto.MemorySearchResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemorySnapshotEntity;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.repository.MemorySnapshotRepository;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryRepository memoryRepository;
    private final MemorySnapshotRepository memorySnapshotRepository;
    private final MemoryEmbeddingWorker embeddingWorker;
    private final EmbeddingService embeddingService;

    public MemoryService(MemoryRepository memoryRepository,
                         MemorySnapshotRepository memorySnapshotRepository,
                         MemoryEmbeddingWorker embeddingWorker,
                         EmbeddingService embeddingService) {
        this.memoryRepository = memoryRepository;
        this.memorySnapshotRepository = memorySnapshotRepository;
        this.embeddingWorker = embeddingWorker;
        this.embeddingService = embeddingService;
    }

    public List<MemoryEntity> listMemories(Long userId, String type) {
        if (type != null && !type.isBlank()) {
            return memoryRepository.findByUserIdAndType(userId, type);
        }
        return memoryRepository.findByUserId(userId);
    }

    /**
     * Memory v2 PR-3: context passed to the incremental extractor must only use
     * ACTIVE memories; STALE/ARCHIVED rows are invisible to extraction prompts.
     */
    @Transactional(readOnly = true)
    public List<MemoryEntity> listActiveMemoriesForExtractionContext(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, "ACTIVE");
    }

    public List<MemoryEntity> searchMemories(Long userId, String keyword) {
        return memoryRepository.findByUserIdAndContentContaining(userId, keyword);
    }

    @Transactional
    public MemoryEntity createMemory(MemoryEntity memory) {
        memory.setExtractionBatchId(null);
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
        existing.setExtractionBatchId(null);
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
        if (embeddingWorker == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            embeddingWorker.triggerEmbeddingAsync(memoryId, text);
            return;
        }
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
        createMemoryIfNotDuplicate(userId, type, title, content, tags, null);
    }

    @Transactional
    public void createMemoryIfNotDuplicate(Long userId, String type, String title,
                                           String content, String tags,
                                           String extractionBatchId) {
        List<MemoryEntity> existing = memoryRepository.findByUserIdAndTitle(userId, title);
        if (!existing.isEmpty()) {
            MemoryEntity e = existing.get(0);
            e.setType(type);
            e.setContent(content);
            e.setTags(tags);
            e.setExtractionBatchId(normalizeBatchId(extractionBatchId));
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
        entity.setExtractionBatchId(normalizeBatchId(extractionBatchId));
        MemoryEntity saved = memoryRepository.save(entity);
        scheduleEmbeddingAfterCommit(saved);
    }

    @Transactional
    public String beginExtractionBatch(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        String batchId = UUID.randomUUID().toString();
        Instant snapshotAt = Instant.now();
        List<MemorySnapshotEntity> snapshots = memoryRepository.findByUserId(userId).stream()
                .map(memory -> toSnapshot(batchId, snapshotAt, memory))
                .toList();
        if (!snapshots.isEmpty()) {
            memorySnapshotRepository.saveAll(snapshots);
        }
        return batchId;
    }

    @Transactional
    public RollbackResult rollbackExtractionBatch(String extractionBatchId, Long userId) {
        String batchId = normalizeBatchId(extractionBatchId);
        if (batchId == null || userId == null) {
            return new RollbackResult(0, 0);
        }

        Map<Long, MemorySnapshotEntity> snapshotsByMemoryId = memorySnapshotRepository
                .findByExtractionBatchIdAndUserId(batchId, userId)
                .stream()
                .filter(snapshot -> snapshot.getMemoryId() != null)
                .collect(Collectors.toMap(
                        MemorySnapshotEntity::getMemoryId,
                        snapshot -> snapshot,
                        (left, right) -> left));

        int restored = 0;
        int deleted = 0;
        List<MemoryEntity> batchMemories = memoryRepository.findByExtractionBatchIdAndUserId(batchId, userId);
        for (MemoryEntity memory : batchMemories) {
            MemorySnapshotEntity snapshot = snapshotsByMemoryId.get(memory.getId());
            if (snapshot == null) {
                memoryRepository.delete(memory);
                deleted++;
                continue;
            }
            restoreFromSnapshot(memory, snapshot);
            MemoryEntity saved = memoryRepository.save(memory);
            scheduleEmbeddingAfterCommit(saved);
            restored++;
        }
        return new RollbackResult(restored, deleted);
    }

    private static MemorySnapshotEntity toSnapshot(String batchId, Instant snapshotAt, MemoryEntity memory) {
        MemorySnapshotEntity snapshot = new MemorySnapshotEntity();
        snapshot.setExtractionBatchId(batchId);
        snapshot.setMemoryId(memory.getId());
        snapshot.setUserId(memory.getUserId());
        snapshot.setType(memory.getType());
        snapshot.setTitle(memory.getTitle());
        snapshot.setContent(memory.getContent());
        snapshot.setTags(memory.getTags());
        snapshot.setSourceExtractionBatchId(memory.getExtractionBatchId());
        snapshot.setRecallCount(memory.getRecallCount());
        snapshot.setLastRecalledAt(memory.getLastRecalledAt());
        // Memory v2 (V29): mirror lifecycle / scoring fields so rollback can restore them.
        // Defensive defaults match V29's column DEFAULTs in case the entity was constructed
        // outside the JPA managed path (e.g. raw JDBC).
        snapshot.setStatus(memory.getStatus() != null ? memory.getStatus() : "ACTIVE");
        snapshot.setArchivedAt(memory.getArchivedAt());
        snapshot.setImportance(memory.getImportance() != null ? memory.getImportance() : "medium");
        snapshot.setLastScore(memory.getLastScore());
        snapshot.setLastScoredAt(memory.getLastScoredAt());
        snapshot.setMemoryCreatedAt(memory.getCreatedAt());
        snapshot.setMemoryUpdatedAt(memory.getUpdatedAt());
        snapshot.setSnapshotAt(snapshotAt);
        return snapshot;
    }

    private static void restoreFromSnapshot(MemoryEntity memory, MemorySnapshotEntity snapshot) {
        memory.setType(snapshot.getType());
        memory.setTitle(snapshot.getTitle());
        memory.setContent(snapshot.getContent());
        memory.setTags(snapshot.getTags());
        memory.setExtractionBatchId(snapshot.getSourceExtractionBatchId());
        memory.setRecallCount(snapshot.getRecallCount());
        memory.setLastRecalledAt(snapshot.getLastRecalledAt());
        // Memory v2 (V29): restore lifecycle / scoring fields.
        // Defensive default for snapshots constructed in-memory before being persisted;
        // post-V29 DB-loaded snapshots already have non-null status/importance via the
        // V29 step 5b backfill (which mirrors live t_memory state onto pre-existing
        // snapshot rows).
        memory.setStatus(snapshot.getStatus() != null ? snapshot.getStatus() : "ACTIVE");
        memory.setArchivedAt(snapshot.getArchivedAt());
        memory.setImportance(snapshot.getImportance() != null ? snapshot.getImportance() : "medium");
        memory.setLastScore(snapshot.getLastScore());
        memory.setLastScoredAt(snapshot.getLastScoredAt());
        memory.setCreatedAt(snapshot.getMemoryCreatedAt());
        memory.setUpdatedAt(snapshot.getMemoryUpdatedAt());
    }

    private static String normalizeBatchId(String extractionBatchId) {
        if (extractionBatchId == null || extractionBatchId.isBlank()) {
            return null;
        }
        return extractionBatchId.trim();
    }

    public record RollbackResult(int restored, int deleted) {}

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

    // ──────────────────────────────────────────────────────────────────────────
    //  Memory v2 (PR-2) — L0/L1 layered prompt injection
    // ──────────────────────────────────────────────────────────────────────────
    //
    // L0 = preference + feedback memories (always inject, recency-ordered, hard cap
    //      L0_BUDGET_CHARS = 2048). Per-entry cap L0_PER_ENTRY_CHARS = 200.
    // L1 = knowledge + project + reference memories (task-aware hybrid recall:
    //      FTS + Vector → RRF → top-K=8, hard cap L1_BUDGET_CHARS = 4096). Per-entry
    //      cap L1_PER_ENTRY_CHARS = 500. taskContext null/blank/length<3 → fallback
    //      to recency ordering.
    //
    // All budgets are CHAR counts (matches String.length() / sb.length() — NOT UTF-8
    // bytes). Constants are intentionally hard-coded here; PR-3 will yaml-ify via
    // MemoryProperties (and rename keys *-bytes → *-chars in the design doc).
    //
    // Public API surface (PR-2):
    //   - getMemoriesForPromptInjection(userId, taskContext) — @Transactional, bumps
    //     recall_count for every injected id. Used by AgentLoopEngine.memoryProvider.
    //   - previewMemoriesForPrompt(userId, taskContext)      — @Transactional(readOnly),
    //     same rendered text, zero side effects. Used by ContextBreakdownService /
    //     CompactionService for size-estimation paths.
    //
    // ⚠️ The legacy 1-arg + 2-arg getMemoriesForPrompt overloads were removed in PR-2;
    // callers must migrate to the two methods above.

    /** L0 budget: preference + feedback total chars (matches String.length(), NOT UTF-8 bytes). */
    private static final int L0_BUDGET_CHARS = 2048;
    /** L1 budget: knowledge + project + reference total chars. */
    private static final int L1_BUDGET_CHARS = 4096;
    /** Per-entry char cap inside L0 sections (preferences are typically short). */
    private static final int L0_PER_ENTRY_CHARS = 200;
    /** Per-entry char cap inside L1 sections (knowledge content can be long). */
    private static final int L1_PER_ENTRY_CHARS = 500;
    /** L1 RRF top-K — final number of L1 entries returned. */
    private static final int L1_TOP_K = 8;
    /** FTS candidate fetch limit (per query). Larger than L1_TOP_K so RRF + filter has room. */
    private static final int FTS_LIMIT = 20;
    /** Vector candidate fetch limit (per query). */
    private static final int VEC_LIMIT = 20;
    /** Reciprocal-Rank-Fusion smoothing constant (industry default). */
    private static final int RRF_K = 60;
    /** Min taskContext length to bother running hybrid recall (skip "hi" / "ok" / etc.). */
    private static final int TASK_CONTEXT_MIN_LEN = 3;

    /** L1 types eligible for hybrid recall, in canonical render order. */
    private static final List<String> L1_TYPES = List.of("knowledge", "project", "reference");

    /**
     * Memory v2 (PR-2): get memories for system-prompt injection with task-aware L0/L1 layering.
     * <p>
     * L0 (preference + feedback): always injected, recency-ordered. Total budget {@value #L0_BUDGET_CHARS} chars,
     * <strong>split evenly</strong> between Preferences (≤{@value #L0_BUDGET_CHARS}/2) and Feedback (≤{@value #L0_BUDGET_CHARS}/2);
     * an empty section does <strong>not</strong> grant its budget to the other side (by-design, see plan §3.4 step 2).
     * <br>
     * L1 (knowledge + project + reference): if {@code taskContext} is non-blank and ≥{@value #TASK_CONTEXT_MIN_LEN}
     * chars, runs hybrid (FTS + Vector) recall fused via RRF → top-{@value #L1_TOP_K}; else falls back
     * to recency-ordered top entries. Capped to {@value #L1_BUDGET_CHARS} chars.
     * <p>
     * Side effect: increments {@code recall_count} for every injected memory id (L0 + L1).
     * <p>
     * Default propagation {@code @Transactional} (REQUIRED) — recall_count UPDATE needs a write tx.
     *
     * @param userId      the user whose memories to fetch (must be non-null caller side; null returns "")
     * @param taskContext current user message used to drive L1 hybrid recall; null/blank/<3 chars
     *                    falls back to recency ordering (skips FTS + embedding calls)
     * @return non-null {@link MemoryInjection}; {@code text()} may be blank if user has zero ACTIVE memories
     */
    @Transactional
    public MemoryInjection getMemoriesForPromptInjection(Long userId, String taskContext) {
        Set<Long> injectedIds = new LinkedHashSet<>();
        String rendered = renderMemoriesForPromptInjection(userId, taskContext, injectedIds);
        if (!injectedIds.isEmpty()) {
            Instant now = Instant.now();
            for (Long id : injectedIds) {
                memoryRepository.incrementRecallCount(id, now);
            }
        }
        return new MemoryInjection(rendered, injectedIds);
    }

    /**
     * Memory v2 (PR-2): preview the prompt-injection block without bumping recall counts. For
     * read-only callers (e.g. {@code ContextBreakdownService} size estimation,
     * {@code CompactionService} session-memory compact) — returns the same text a real
     * {@link #getMemoriesForPromptInjection} call would produce, but with zero side effects.
     * <p>
     * {@code @Transactional(readOnly = true)} for connection pooling efficiency; no UPDATEs run.
     */
    @Transactional(readOnly = true)
    public String previewMemoriesForPrompt(Long userId, String taskContext) {
        return renderMemoriesForPromptInjection(userId, taskContext, new LinkedHashSet<>());
    }

    /**
     * Memory v2 (PR-2): shared L0/L1 renderer — collects rendered text + injected ids in one pass.
     * <p>
     * Intentionally NOT annotated {@code @Transactional}: Spring AOP doesn't apply to private
     * methods (java.md footgun #2). Tx boundary lives on the two public callers above.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>L0 — fetch ACTIVE preference + feedback memories ordered by updatedAt DESC,
     *       render under "### Preferences" / "### Feedback" with per-entry cap
     *       {@value #L0_PER_ENTRY_CHARS} and section budget {@value #L0_BUDGET_CHARS}.</li>
     *   <li>L1 — if taskContext is null/blank/<{@value #TASK_CONTEXT_MIN_LEN} chars, fall back to
     *       recency ordering of K/P/R types (skips FTS + embedding I/O); else run FTS
     *       (limit {@value #FTS_LIMIT}) + Vector (limit {@value #VEC_LIMIT}) → RRF (K={@value #RRF_K})
     *       → top-{@value #L1_TOP_K}. Filter to L1 types only. Render under
     *       "### Knowledge &amp; Context" with per-entry cap {@value #L1_PER_ENTRY_CHARS}
     *       and section budget {@value #L1_BUDGET_CHARS}.</li>
     * </ol>
     * Empty user → returns "". Hybrid recall returning empty (rare) → recency fallback for L1.
     */
    private String renderMemoriesForPromptInjection(Long userId, String taskContext,
                                                    Set<Long> injectedIds) {
        if (userId == null) return "";

        List<MemoryEntity> activeMemories = memoryRepository
                .findByUserIdAndStatusOrderByUpdatedAtDesc(userId, "ACTIVE");
        if (activeMemories.isEmpty()) return "";

        Map<String, List<MemoryEntity>> byType = activeMemories.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getType() != null ? m.getType() : "knowledge"));

        StringBuilder sb = new StringBuilder();

        // ── L0: preference + feedback ──────────────────────────────────────────
        appendL0Section(sb, byType.get("preference"), "Preferences", injectedIds);
        appendL0Section(sb, byType.get("feedback"), "Feedback", injectedIds);

        // ── L1: knowledge + project + reference ────────────────────────────────
        boolean hasTaskContext = taskContext != null
                && !taskContext.isBlank()
                && taskContext.length() >= TASK_CONTEXT_MIN_LEN;

        List<MemoryEntity> l1Candidates = collectL1Candidates(byType);

        if (hasTaskContext) {
            List<MemorySearchResult> ranked = hybridRecallL1(userId, taskContext);
            if (!ranked.isEmpty()) {
                appendL1RankedSection(sb, ranked, injectedIds);
            } else if (!l1Candidates.isEmpty()) {
                // Hybrid returned empty (rare: tsquery whitespace, no embedding hits) — fall back.
                appendL1RecencySection(sb, l1Candidates, injectedIds);
            }
        } else if (!l1Candidates.isEmpty()) {
            appendL1RecencySection(sb, l1Candidates, injectedIds);
        }

        return sb.toString();
    }

    /** Collect L1 candidates from the type-grouped map, in canonical type order, recency within. */
    private static List<MemoryEntity> collectL1Candidates(Map<String, List<MemoryEntity>> byType) {
        List<MemoryEntity> kpr = new ArrayList<>();
        for (String type : L1_TYPES) {
            List<MemoryEntity> bucket = byType.get(type);
            if (bucket != null) kpr.addAll(bucket);
        }
        // findByUserIdAndStatusOrderByUpdatedAtDesc already sorted DESC, but type-grouping
        // erased the order across types — re-sort to keep recency consistent in fallback.
        kpr.sort(Comparator.comparing(MemoryEntity::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return kpr;
    }

    /** Run FTS + Vector hybrid recall and fuse with RRF. Vector skipped if embedding unavailable. */
    private List<MemorySearchResult> hybridRecallL1(Long userId, String taskContext) {
        List<MemorySearchResult> fts;
        try {
            fts = searchByFts(userId, taskContext, FTS_LIMIT);
        } catch (Exception e) {
            log.warn("L1 FTS recall failed for user={}: {}", userId, e.getMessage());
            fts = List.of();
        }

        List<MemorySearchResult> vec = embeddingService.embed(taskContext)
                .map(v -> {
                    try {
                        return searchByVector(userId, v, VEC_LIMIT);
                    } catch (Exception e) {
                        log.warn("L1 Vector recall failed for user={}: {}", userId, e.getMessage());
                        return List.<MemorySearchResult>of();
                    }
                })
                .orElse(List.of());

        // Filter to L1 types only — never let preference/feedback leak into L1 section.
        Set<String> l1Set = Set.copyOf(L1_TYPES);
        fts = fts.stream().filter(r -> l1Set.contains(r.type())).toList();
        vec = vec.stream().filter(r -> l1Set.contains(r.type())).toList();

        return rrfMerge(fts, vec, L1_TOP_K);
    }

    /**
     * Reciprocal Rank Fusion (K={@value #RRF_K}). Returns top-K fused results.
     * Local helper for L1; intentionally NOT shared with {@link com.skillforge.server.tool.MemorySearchTool}
     * to keep PR-2 scope tight (would need a 3rd caller before extracting a util).
     */
    private static List<MemorySearchResult> rrfMerge(
            List<MemorySearchResult> fts,
            List<MemorySearchResult> vec,
            int topK) {

        Map<Long, Double> scores = new HashMap<>();
        for (int i = 0; i < fts.size(); i++) {
            scores.merge(fts.get(i).memoryId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < vec.size(); i++) {
            scores.merge(vec.get(i).memoryId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }

        Map<Long, MemorySearchResult> byId = new HashMap<>();
        fts.forEach(r -> byId.put(r.memoryId(), r));
        vec.forEach(r -> byId.putIfAbsent(r.memoryId(), r));

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }

    /**
     * Append an L0 section (preference / feedback). Section budget = L0_BUDGET_CHARS / 2 so
     * the two L0 sections share the L0 budget evenly. Per-entry cap L0_PER_ENTRY_CHARS.
     */
    private static void appendL0Section(StringBuilder sb, List<MemoryEntity> memories,
                                        String sectionTitle, Set<Long> injectedIds) {
        if (memories == null || memories.isEmpty()) return;
        int sectionStart = sb.length();
        int sectionBudget = L0_BUDGET_CHARS / 2; // Preferences + Feedback share L0_BUDGET_CHARS

        sb.append("### ").append(sectionTitle).append("\n");
        for (MemoryEntity m : memories) {
            // Stop before exceeding section budget (compute against current section growth).
            if (sb.length() - sectionStart >= sectionBudget) break;
            String entry = renderEntry(m, L0_PER_ENTRY_CHARS);
            sb.append(entry);
            if (m.getId() != null) injectedIds.add(m.getId());
        }
        sb.append("\n");
    }

    /**
     * Append L1 hybrid-ranked section with per-entry + section budget caps.
     *
     * @implNote In the default config the section is bounded by
     *           {@code L1_TOP_K * L1_PER_ENTRY_CHARS = 8 * 500 = 4000} chars, which sits below
     *           {@link #L1_BUDGET_CHARS}={@value #L1_BUDGET_CHARS}. The section-budget break is therefore a
     *           <strong>defensive cap</strong> that only kicks in if {@link #L1_PER_ENTRY_CHARS} or
     *           {@link #L1_TOP_K} are raised in the future.
     */
    private static void appendL1RankedSection(StringBuilder sb, List<MemorySearchResult> ranked,
                                              Set<Long> injectedIds) {
        int sectionStart = sb.length();
        sb.append("### Knowledge & Context (ranked by relevance)\n");
        for (MemorySearchResult r : ranked) {
            if (sb.length() - sectionStart >= L1_BUDGET_CHARS) break;
            sb.append(renderEntryFromResult(r, L1_PER_ENTRY_CHARS));
            injectedIds.add(r.memoryId());
        }
        sb.append("\n");
    }

    /** Append L1 recency-fallback section (taskContext absent or hybrid empty). */
    private static void appendL1RecencySection(StringBuilder sb, List<MemoryEntity> candidates,
                                               Set<Long> injectedIds) {
        int sectionStart = sb.length();
        sb.append("### Knowledge & Context\n");
        int taken = 0;
        for (MemoryEntity m : candidates) {
            if (taken >= L1_TOP_K) break;
            if (sb.length() - sectionStart >= L1_BUDGET_CHARS) break;
            sb.append(renderEntry(m, L1_PER_ENTRY_CHARS));
            if (m.getId() != null) injectedIds.add(m.getId());
            taken++;
        }
        sb.append("\n");
    }

    /** Render a single entry as "- **title**: content" with per-entry char cap. */
    private static String renderEntry(MemoryEntity m, int perEntryCap) {
        StringBuilder e = new StringBuilder();
        e.append("- ");
        if (m.getTitle() != null) e.append("**").append(m.getTitle()).append("**: ");
        String content = m.getContent() != null ? m.getContent() : "";
        if (content.length() > perEntryCap) {
            content = content.substring(0, perEntryCap) + "...[truncated]";
        }
        e.append(content).append("\n");
        return e.toString();
    }

    /** Render an L1 hybrid-recall result as "- **title**: content" with per-entry char cap. */
    private static String renderEntryFromResult(MemorySearchResult r, int perEntryCap) {
        StringBuilder e = new StringBuilder();
        e.append("- ");
        if (r.title() != null) e.append("**").append(r.title()).append("**: ");
        String content = r.content() != null ? r.content() : "";
        if (content.length() > perEntryCap) {
            content = content.substring(0, perEntryCap) + "...[truncated]";
        }
        e.append(content).append("\n");
        return e.toString();
    }
}
