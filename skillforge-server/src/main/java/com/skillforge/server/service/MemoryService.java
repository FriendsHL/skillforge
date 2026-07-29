package com.skillforge.server.service;

import com.skillforge.core.engine.MemoryInjection;
import com.skillforge.core.engine.MemoryInjectionRef;
import com.skillforge.core.reminder.MemoryAgeStatsProvider;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.dto.MemorySearchResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemorySnapshotEntity;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.repository.MemorySnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.format.DateTimeFormatter;
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
    private static final int DEDUP_NEIGHBOR_LIMIT = 3;

    private final MemoryRepository memoryRepository;
    private final MemorySnapshotRepository memorySnapshotRepository;
    private final MemoryEmbeddingWorker embeddingWorker;
    private final EmbeddingService embeddingService;
    private MemoryProperties memoryProperties = new MemoryProperties();

    public MemoryService(MemoryRepository memoryRepository,
                         MemorySnapshotRepository memorySnapshotRepository,
                         MemoryEmbeddingWorker embeddingWorker,
                         EmbeddingService embeddingService) {
        this.memoryRepository = memoryRepository;
        this.memorySnapshotRepository = memorySnapshotRepository;
        this.embeddingWorker = embeddingWorker;
        this.embeddingService = embeddingService;
    }

    @Autowired(required = false)
    public void setMemoryProperties(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties != null ? memoryProperties : new MemoryProperties();
    }

    public List<MemoryEntity> listMemories(Long userId, String type) {
        return listMemories(userId, type, null);
    }

    public List<MemoryEntity> listMemories(Long userId, String type, String status) {
        List<MemoryEntity> base;
        if (status != null && !status.isBlank()) {
            base = memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, normalizeStatus(status));
        } else if (type != null && !type.isBlank()) {
            base = memoryRepository.findByUserIdAndType(userId, type);
        } else {
            base = memoryRepository.findByUserId(userId);
        }
        if (type == null || type.isBlank()) {
            return base;
        }
        return base.stream()
                .filter(memory -> type.equalsIgnoreCase(memory.getType()))
                .toList();
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
        memory.setProvenanceSource("USER_EXPLICIT");
        memory.setConfirmationStatus("CONFIRMED");
        memory.setConfidence(1.0d);
        MemoryEntity saved = memoryRepository.save(memory);
        scheduleEmbeddingAfterCommit(saved);
        return saved;
    }

    /** Agent-proposed memory is durable but never masquerades as user-confirmed fact. */
    @Transactional
    public MemoryEntity createAgentSuggestedMemory(MemoryEntity memory) {
        memory.setExtractionBatchId(null);
        memory.setProvenanceSource("AGENT_SUGGESTED");
        memory.setConfirmationStatus("UNVERIFIED");
        if (memory.getConfidence() == null) {
            memory.setConfidence(0.5d);
        }
        MemoryEntity saved = memoryRepository.save(memory);
        scheduleEmbeddingAfterCommit(saved);
        return saved;
    }

    @Transactional
    public MemoryEntity updateMemory(Long id, MemoryEntity memory) {
        MemoryEntity existing = memoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Memory not found: " + id));
        if (memory.getVersion() != null
                && !memory.getVersion().equals(existing.getVersion())) {
            throw new MemoryVersionConflictException(
                    id, memory.getVersion(), existing.getVersion());
        }
        if (memory.getType() != null) existing.setType(memory.getType());
        if (memory.getTitle() != null) existing.setTitle(memory.getTitle());
        if (memory.getContent() != null) existing.setContent(memory.getContent());
        if (memory.getTags() != null) existing.setTags(memory.getTags());
        existing.setExtractionBatchId(null);
        existing.setProvenanceSource("USER_EXPLICIT");
        existing.setConfirmationStatus("CONFIRMED");
        existing.setConfidence(1.0d);
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
        String provenance = row.length > 7 && row[7] != null
                ? String.valueOf(row[7]) : "LEGACY_UNKNOWN";
        String confirmation = row.length > 8 && row[8] != null
                ? String.valueOf(row[8]) : "UNVERIFIED";
        Double confidence = row.length > 9 && row[9] != null
                ? ((Number) row[9]).doubleValue() : null;
        Long version = row.length > 10 && row[10] != null
                ? ((Number) row[10]).longValue() : 0L;
        return new MemorySearchResult(
                id, type, title, content, score,
                provenance, confirmation, confidence, version);
    }

    private String buildEmbedText(MemoryEntity m) {
        if (m == null) return "";
        return buildEmbedText(m.getTitle(), m.getContent(), m.getTags());
    }

    private String buildEmbedText(String title, String content, String tags) {
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title).append("\n");
        if (content != null) sb.append(content);
        if (tags != null) sb.append("\nTags: ").append(tags);
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
    public int updateStatus(Long id, Long userId, String status) {
        if (id == null || userId == null || status == null || status.isBlank()) {
            return 0;
        }
        MemoryEntity memory = memoryRepository.findById(id).orElse(null);
        if (memory == null || !userId.equals(memory.getUserId())) {
            return 0;
        }
        applyStatus(memory, normalizeStatus(status));
        memoryRepository.save(memory);
        return 1;
    }

    @Transactional
    public int batchArchive(List<Long> ids, Long userId) {
        return batchUpdateStatus(ids, userId, "ARCHIVED");
    }

    @Transactional
    public int batchRestore(List<Long> ids, Long userId) {
        return batchUpdateStatus(ids, userId, "ACTIVE");
    }

    @Transactional
    public int batchUpdateStatus(List<Long> ids, Long userId, String status) {
        return updateStatuses(ids, userId, status);
    }

    @Transactional
    public int batchDelete(List<Long> ids, Long userId) {
        if (ids == null || ids.isEmpty() || userId == null) {
            return 0;
        }
        int changed = 0;
        for (Long id : ids) {
            MemoryEntity memory = memoryRepository.findById(id).orElse(null);
            if (memory == null || !userId.equals(memory.getUserId())) {
                continue;
            }
            memoryRepository.deleteById(id);
            changed++;
        }
        return changed;
    }

    public MemoryStats getStats(Long userId) {
        long active = memoryRepository.countByUserIdAndStatus(userId, "ACTIVE");
        long stale = memoryRepository.countByUserIdAndStatus(userId, "STALE");
        long archived = memoryRepository.countByUserIdAndStatus(userId, "ARCHIVED");
        return new MemoryStats(active, stale, archived, memoryProperties.getEviction().getMaxActivePerUser());
    }

    /**
     * REMINDER-MVP MemoryAgeSource (W2): single aggregate call returning active + stale counts
     * + last-recalled timestamp for the user, in one transaction (one DB round-trip). Was 3
     * separate methods + 3 round-trips with a TOCTOU race window in the r1 design.
     *
     * <p>{@code Stats.staleCount} reflects memories whose {@code lastRecalledAt} is older than
     * {@code daysThreshold} days, or has never been recalled. The same {@code @Transactional
     * (readOnly = true)} scope guarantees the count + max are computed against the same
     * MVCC snapshot.
     *
     * <p>Defensive defaults: null userId / non-positive threshold returns
     * {@link MemoryAgeStatsProvider.Stats#EMPTY} without touching the DB.
     */
    @Transactional(readOnly = true)
    public MemoryAgeStatsProvider.Stats getMemoryAgeStats(Long userId, int daysThreshold) {
        if (userId == null || daysThreshold <= 0) {
            return MemoryAgeStatsProvider.Stats.EMPTY;
        }
        Instant threshold = Instant.now().minus(Duration.ofDays(daysThreshold));
        Object[] row = memoryRepository.aggregateActiveStats(userId, threshold);
        if (row == null || row.length < 3) {
            return MemoryAgeStatsProvider.Stats.EMPTY;
        }
        long active = row[0] instanceof Number n ? n.longValue() : 0L;
        long stale = row[1] instanceof Number n ? n.longValue() : 0L;
        Instant lastRecalled = row[2] instanceof Instant i ? i : null;
        return new MemoryAgeStatsProvider.Stats(active, stale, Optional.ofNullable(lastRecalled));
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
            updateExistingMemory(existing.get(0), type, title, content, tags, extractionBatchId, Optional.empty());
            return;
        }

        Optional<float[]> embedding = embedForDedup(title, content);
        if (embedding.isPresent()) {
            Optional<MemorySearchResult> neighbor = findNearestActiveMemoryByType(userId, type, embedding.get());
            if (neighbor.isPresent()) {
                double similarity = 1.0d - neighbor.get().score();
                double updateThreshold = memoryProperties.getDedup().getCosineUpdateThreshold();
                double mergeThreshold = memoryProperties.getDedup().getCosineMergeThreshold();

                if (similarity >= updateThreshold) {
                    MemoryEntity target = memoryRepository.findById(neighbor.get().memoryId()).orElse(null);
                    if (target != null) {
                        updateExistingMemory(target, type, title, content, tags, extractionBatchId, embedding);
                        return;
                    }
                } else if (similarity >= mergeThreshold) {
                    MemoryEntity target = memoryRepository.findById(neighbor.get().memoryId()).orElse(null);
                    if (target != null) {
                        mergeIntoExistingMemory(target, type, title, content, tags, extractionBatchId);
                        return;
                    }
                }
            }
        }

        MemoryEntity entity = new MemoryEntity();
        entity.setUserId(userId);
        entity.setType(normalizeType(type));
        entity.setTitle(title);
        entity.setContent(content);
        entity.setTags(tags);
        entity.setImportance(extractImportance(tags));
        entity.setExtractionBatchId(normalizeBatchId(extractionBatchId));
        entity.setProvenanceSource("USER_TRANSCRIPT");
        entity.setConfirmationStatus("UNVERIFIED");
        MemoryEntity saved = memoryRepository.save(entity);
        persistKnownEmbeddingOrSchedule(saved, embedding);
    }

    private Optional<float[]> embedForDedup(String title, String content) {
        if (embeddingService == null) {
            return Optional.empty();
        }
        String text = buildEmbedText(title, content, null);
        if (text.isBlank()) {
            return Optional.empty();
        }
        return embeddingService.embed(text);
    }

    private Optional<MemorySearchResult> findNearestActiveMemoryByType(Long userId, String type, float[] embedding) {
        try {
            return memoryRepository.findByVectorAndType(
                            userId,
                            normalizeType(type),
                            VectorUtils.toVectorString(embedding),
                            DEDUP_NEIGHBOR_LIMIT)
                    .stream()
                    .map(this::toSearchResult)
                    .findFirst();
        } catch (Exception e) {
            log.warn("Memory dedup vector lookup failed for userId={} type={}: {}",
                    userId, type, e.getMessage());
            return Optional.empty();
        }
    }

    private void updateExistingMemory(MemoryEntity target,
                                      String type,
                                      String title,
                                      String content,
                                      String tags,
                                      String extractionBatchId,
                                      Optional<float[]> knownEmbedding) {
        target.setType(normalizeType(type));
        target.setTitle(title);
        target.setContent(content);
        target.setTags(tags);
        target.setImportance(maxImportance(target.getImportance(), extractImportance(tags)));
        target.setExtractionBatchId(normalizeBatchId(extractionBatchId));
        target.setProvenanceSource("USER_TRANSCRIPT");
        target.setConfirmationStatus("UNVERIFIED");
        reviveMemory(target);
        MemoryEntity saved = memoryRepository.save(target);
        persistKnownEmbeddingOrSchedule(saved, knownEmbedding);
    }

    private void mergeIntoExistingMemory(MemoryEntity target,
                                         String type,
                                         String title,
                                         String content,
                                         String tags,
                                         String extractionBatchId) {
        target.setType(normalizeType(type));
        target.setContent(appendMergedContent(target.getContent(), title, content));
        target.setTags(mergeCsvTags(target.getTags(), tags));
        target.setImportance(maxImportance(target.getImportance(), extractImportance(tags)));
        target.setExtractionBatchId(normalizeBatchId(extractionBatchId));
        target.setProvenanceSource("USER_TRANSCRIPT");
        target.setConfirmationStatus("UNVERIFIED");
        reviveMemory(target);
        MemoryEntity saved = memoryRepository.save(target);
        scheduleEmbeddingAfterCommit(saved);
    }

    private static void reviveMemory(MemoryEntity target) {
        target.setStatus("ACTIVE");
        target.setArchivedAt(null);
    }

    private void persistKnownEmbeddingOrSchedule(MemoryEntity saved, Optional<float[]> knownEmbedding) {
        if (knownEmbedding.isPresent() && saved.getId() != null) {
            persistEmbeddingAfterCommit(saved.getId(), VectorUtils.toVectorString(knownEmbedding.get()));
            return;
        }
        scheduleEmbeddingAfterCommit(saved);
    }

    private void persistEmbeddingAfterCommit(Long memoryId, String embedding) {
        if (memoryId == null || embedding == null || embedding.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            memoryRepository.updateEmbedding(memoryId, embedding);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                memoryRepository.updateEmbedding(memoryId, embedding);
            }
        });
    }

    private static String appendMergedContent(String existingContent, String incomingTitle, String incomingContent) {
        StringBuilder sb = new StringBuilder();
        if (existingContent != null && !existingContent.isBlank()) {
            sb.append(existingContent.stripTrailing());
        }
        if (!sb.isEmpty()) {
            sb.append("\n---\n");
        }
        sb.append("[merged from \"")
                .append(incomingTitle != null ? incomingTitle : "untitled")
                .append("\" at ")
                .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .append("]\n");
        if (incomingContent != null) {
            sb.append(incomingContent);
        }
        return sb.toString();
    }

    private static String mergeCsvTags(String existingTags, String incomingTags) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addCsvTags(tags, existingTags);
        addCsvTags(tags, incomingTags);
        return String.join(",", tags);
    }

    private static void addCsvTags(Set<String> tags, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String raw : csv.split(",")) {
            String tag = raw.trim();
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
    }

    private static String normalizeType(String type) {
        return type != null && !type.isBlank() ? type.toLowerCase() : "knowledge";
    }

    private int updateStatuses(List<Long> ids, Long userId, String status) {
        if (ids == null || ids.isEmpty() || userId == null) {
            return 0;
        }
        int changed = 0;
        for (Long id : ids) {
            MemoryEntity memory = memoryRepository.findById(id).orElse(null);
            if (memory == null || !userId.equals(memory.getUserId())) {
                continue;
            }
            applyStatus(memory, status);
            memoryRepository.save(memory);
            changed++;
        }
        return changed;
    }

    private static void applyStatus(MemoryEntity memory, String status) {
        String normalized = normalizeStatus(status);
        memory.setStatus(normalized);
        if ("ARCHIVED".equals(normalized)) {
            if (memory.getArchivedAt() == null) {
                memory.setArchivedAt(Instant.now());
            }
        } else {
            memory.setArchivedAt(null);
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null) {
            return "ACTIVE";
        }
        return switch (status.trim().toUpperCase()) {
            case "STALE" -> "STALE";
            case "ARCHIVED" -> "ARCHIVED";
            default -> "ACTIVE";
        };
    }

    private static String extractImportance(String tags) {
        if (tags == null || tags.isBlank()) {
            return "medium";
        }
        for (String raw : tags.split(",")) {
            String tag = raw.trim().toLowerCase();
            if (tag.equals("importance:high")) {
                return "high";
            }
            if (tag.equals("importance:medium")) {
                return "medium";
            }
            if (tag.equals("importance:low")) {
                return "low";
            }
        }
        return "medium";
    }

    private static String maxImportance(String left, String right) {
        return importanceRank(left) >= importanceRank(right) ? normalizeImportance(left) : normalizeImportance(right);
    }

    private static int importanceRank(String importance) {
        return switch (normalizeImportance(importance)) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 2;
        };
    }

    private static String normalizeImportance(String importance) {
        if (importance == null) {
            return "medium";
        }
        return switch (importance.toLowerCase()) {
            case "high" -> "high";
            case "low" -> "low";
            default -> "medium";
        };
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
        snapshot.setProvenanceSource(memory.getProvenanceSource());
        snapshot.setConfirmationStatus(memory.getConfirmationStatus());
        snapshot.setConfidence(memory.getConfidence());
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
        memory.setProvenanceSource(snapshot.getProvenanceSource());
        memory.setConfirmationStatus(snapshot.getConfirmationStatus());
        memory.setConfidence(snapshot.getConfidence());
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
    public record MemoryStats(long active, long stale, long archived, int capacityCap) {}

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

    private static final int AUTO_INJECTION_MAX_ENTRIES = 6;
    private static final int AUTO_INJECTION_MAX_CHARS = 3_200;
    private static final int AUTO_INJECTION_PER_ENTRY_CHARS = 400;

    /**
     * Injects only explicit, confirmed long-term memories. Session digests, extraction
     * candidates and other unverified data remain available through memory search/detail
     * tools, but never consume every turn's system prompt.
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
        return new MemoryInjection(rendered, injectedIds, provenanceFor(injectedIds));
    }

    /** Preview the same confirmed block without recall-count side effects. */
    @Transactional(readOnly = true)
    public String previewMemoriesForPrompt(Long userId, String taskContext) {
        return previewMemoryInjectionForPrompt(userId, taskContext).text();
    }

    /**
     * Memory context preview with rendered text and ids, without bumping recall counts.
     * Used by flywheel/system-agent read-only context surfaces that need an auditable id set.
     */
    @Transactional(readOnly = true)
    public MemoryInjection previewMemoryInjectionForPrompt(Long userId, String taskContext) {
        Set<Long> injectedIds = new LinkedHashSet<>();
        String rendered = renderMemoriesForPromptInjection(userId, taskContext, injectedIds);
        return new MemoryInjection(rendered, injectedIds, provenanceFor(injectedIds));
    }

    private List<MemoryInjectionRef> provenanceFor(Set<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) return List.of();
        Map<Long, MemoryEntity> byId = memoryRepository.findAllById(memoryIds).stream()
                .collect(Collectors.toMap(MemoryEntity::getId, memory -> memory));
        List<MemoryInjectionRef> refs = new ArrayList<>();
        for (Long id : memoryIds) {
            MemoryEntity memory = byId.get(id);
            if (memory == null) continue;
            refs.add(new MemoryInjectionRef(
                    memory.getId(),
                    memory.getProvenanceSource(),
                    memory.getConfirmationStatus(),
                    memory.getConfidence(),
                    memory.getVersion()));
        }
        return refs;
    }

    private String renderMemoriesForPromptInjection(Long userId, String taskContext,
                                                    Set<Long> injectedIds) {
        if (userId == null) return "";

        List<MemoryEntity> confirmed = memoryRepository
                .findByUserIdAndStatusOrderByUpdatedAtDesc(userId, "ACTIVE")
                .stream()
                .filter(memory -> "CONFIRMED".equals(memory.getConfirmationStatus()))
                .limit(AUTO_INJECTION_MAX_ENTRIES)
                .toList();
        if (confirmed.isEmpty()) return "";

        StringBuilder rendered = new StringBuilder("### Confirmed Long-term Memory\n");
        for (MemoryEntity memory : confirmed) {
            String entry = renderConfirmedEntry(memory);
            if (rendered.length() + entry.length() > AUTO_INJECTION_MAX_CHARS) {
                break;
            }
            rendered.append(entry);
            if (memory.getId() != null) {
                injectedIds.add(memory.getId());
            }
        }
        return injectedIds.isEmpty() ? "" : rendered.toString();
    }

    private static String renderConfirmedEntry(MemoryEntity memory) {
        String content = memory.getContent() != null ? memory.getContent() : "";
        if (content.length() > AUTO_INJECTION_PER_ENTRY_CHARS) {
            content = content.substring(0, AUTO_INJECTION_PER_ENTRY_CHARS) + "...[truncated]";
        }
        String title = memory.getTitle() != null ? memory.getTitle() : "Untitled";
        return "- [memory:" + memory.getId()
                + " provenance=" + safeMetadata(memory.getProvenanceSource())
                + " confirmation=" + safeMetadata(memory.getConfirmationStatus())
                + " confidence=" + memory.getConfidence()
                + " version=" + memory.getVersion()
                + "] **" + title + "**: " + content + "\n";
    }

    private static String safeMetadata(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
