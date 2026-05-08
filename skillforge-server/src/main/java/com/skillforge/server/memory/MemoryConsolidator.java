package com.skillforge.server.memory;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.util.VectorUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    /** Reasons recorded in {@link MemoryEntity#getArchivedReason()} (V66). */
    static final String REASON_EXPIRED_TTL = "expired_ttl";
    static final String REASON_CAPACITY_DEMOTE = "capacity_demote";
    static final String REASON_DEDUP_MERGE_PREFIX = "dedup_merge_with_";

    private final MemoryRepository memoryRepository;
    private MemoryProperties memoryProperties = new MemoryProperties();

    public MemoryConsolidator(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Autowired(required = false)
    public void setMemoryProperties(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties != null ? memoryProperties : new MemoryProperties();
    }

    /**
     * Memory v2 PR-5 + MEMORY-DREAM-CONSOLIDATION: status lifecycle sweep, capacity
     * enforcement, and embedding-based dedup of redundant ACTIVE memories.
     *
     * <p>Order of phases:
     * <ol>
     *   <li>Embedding dedup — pairwise cosine similarity over ACTIVE memories;
     *       the lower-scored row in any pair above {@code cosineMergeThreshold}
     *       is archived with {@code archivedReason = dedup_merge_with_<winnerId>}.
     *       Skipped silently if embeddings are unavailable.</li>
     *   <li>Reload all memories, then for each: delete if expired-archived;
     *       otherwise score, transition status, save.</li>
     *   <li>Enforce capacity by demoting lowest-scored overflow to STALE.</li>
     * </ol>
     */
    public void consolidate(Long userId) {
        if (memoryRepository == null || userId == null) {
            return;
        }

        // Phase 0 — embedding dedup BEFORE scoring/transition. Demote-as-archive happens
        // here so subsequent capacity / status work sees the post-dedup ACTIVE set.
        deduplicateByEmbedding(userId);

        List<MemoryEntity> all = memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (all.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (MemoryEntity memory : all) {
            if (isExpiredArchived(memory, now)) {
                memoryRepository.delete(memory);
                log.info("Deleted expired archived memory: id={}, title={}, reason={}",
                        memory.getId(), memory.getTitle(), memory.getArchivedReason());
                continue;
            }
            memory.setLastScore(scoreMemory(memory, now));
            memory.setLastScoredAt(now);
            transitionStatus(memory, now);
            memoryRepository.save(memory);
        }
        enforceCapacity(all);
    }

    /**
     * MEMORY-DREAM-CONSOLIDATION step 0: pairwise cosine similarity over ACTIVE memories.
     * For any pair above {@code cosineMergeThreshold}, the lower-scored memory is
     * archived with {@code archivedReason = dedup_merge_with_<winnerId>}. {@code O(N^2)}
     * is acceptable under the 1500/user capacity cap.
     *
     * <p>Failure modes (all recoverable, dedup is best-effort):
     * <ul>
     *   <li>pgvector not installed in the cluster → repository call throws → log + skip.</li>
     *   <li>No ACTIVE memories with embeddings → no-op.</li>
     *   <li>Embedding text fails to parse → that row is skipped (won't participate in dedup).</li>
     * </ul>
     */
    private void deduplicateByEmbedding(Long userId) {
        double threshold = memoryProperties.getDedup().getCosineMergeThreshold();
        if (threshold <= 0.0 || threshold >= 1.0) {
            return; // disabled / nonsensical config
        }

        List<Object[]> rows;
        try {
            rows = memoryRepository.findEmbeddingsForActiveByUser(userId);
        } catch (Exception e) {
            // pgvector unavailable, transient DB issue, etc. — degrade gracefully.
            log.warn("Memory dedup: embedding fetch failed for userId={}, skipping dedup: {}",
                    userId, e.getMessage());
            return;
        }
        if (rows == null || rows.size() < 2) {
            return;
        }

        // Parse embeddings + load entities in one pass.
        record EmbeddedMemory(MemoryEntity entity, float[] embedding) {}
        List<EmbeddedMemory> candidates = new java.util.ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            String embText = row[1] == null ? null : row[1].toString();
            float[] embedding = VectorUtils.parseVectorString(embText);
            if (embedding == null || embedding.length == 0) {
                continue;
            }
            MemoryEntity entity = memoryRepository.findById(id).orElse(null);
            if (entity == null || !"ACTIVE".equals(entity.getStatus())) {
                continue;
            }
            candidates.add(new EmbeddedMemory(entity, embedding));
        }
        if (candidates.size() < 2) {
            return;
        }

        // Score everyone once up-front; dedup decisions need a stable ordering so the
        // pairwise scan can never end up archiving both sides of a triangle.
        Instant now = Instant.now();
        Map<Long, Double> scoreCache = new HashMap<>();
        for (EmbeddedMemory em : candidates) {
            scoreCache.put(em.entity().getId(), scoreMemory(em.entity(), now));
        }

        Set<Long> archivedIds = new HashSet<>();
        int archivedCount = 0;
        for (int i = 0; i < candidates.size(); i++) {
            EmbeddedMemory a = candidates.get(i);
            if (archivedIds.contains(a.entity().getId())) {
                continue;
            }
            for (int j = i + 1; j < candidates.size(); j++) {
                EmbeddedMemory b = candidates.get(j);
                if (archivedIds.contains(b.entity().getId())) {
                    continue;
                }
                double sim = VectorUtils.cosineSimilarity(a.embedding(), b.embedding());
                if (sim <= threshold) {
                    continue;
                }
                // Pick the winner: higher score wins; on tie, keep the one with the
                // larger id (stable; newer rows generally have larger ids).
                double scoreA = scoreCache.getOrDefault(a.entity().getId(), 0.0);
                double scoreB = scoreCache.getOrDefault(b.entity().getId(), 0.0);
                MemoryEntity winner;
                MemoryEntity loser;
                if (scoreA > scoreB || (scoreA == scoreB && a.entity().getId() > b.entity().getId())) {
                    winner = a.entity();
                    loser = b.entity();
                } else {
                    winner = b.entity();
                    loser = a.entity();
                }
                archiveAsDuplicate(loser, winner, sim);
                archivedIds.add(loser.getId());
                archivedCount++;
                if (loser == a.entity()) {
                    // a was archived — bail on this row's inner loop, advance to next i.
                    break;
                }
            }
        }
        if (archivedCount > 0) {
            log.info("Memory dedup: userId={} archived {} duplicate(s) (threshold={})",
                    userId, archivedCount, threshold);
        }
    }

    private void archiveAsDuplicate(MemoryEntity loser, MemoryEntity winner, double similarity) {
        loser.setStatus("ARCHIVED");
        if (loser.getArchivedAt() == null) {
            loser.setArchivedAt(Instant.now());
        }
        loser.setArchivedReason(REASON_DEDUP_MERGE_PREFIX + winner.getId());
        memoryRepository.save(loser);
        log.info("Memory dedup: archived id={} (title={}) merged into winnerId={} (title={}) similarity={}",
                loser.getId(), loser.getTitle(), winner.getId(), winner.getTitle(),
                String.format(java.util.Locale.ROOT, "%.4f", similarity));
    }

    private void enforceCapacity(List<MemoryEntity> all) {
        int cap = memoryProperties.getEviction().getMaxActivePerUser();
        List<MemoryEntity> active = all.stream()
                .filter(memory -> "ACTIVE".equals(memory.getStatus()))
                .sorted(Comparator
                        .comparing(MemoryEntity::getLastScore, Comparator.nullsLast(Double::compareTo))
                        .thenComparing(MemoryEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (active.size() <= cap) {
            return;
        }
        int overflow = active.size() - cap;
        for (int i = 0; i < overflow; i++) {
            MemoryEntity demoted = active.get(i);
            demoted.setStatus("STALE");
            demoted.setArchivedAt(null);
            demoted.setArchivedReason(REASON_CAPACITY_DEMOTE);
            memoryRepository.save(demoted);
            log.info("Demoted memory due to capacity pressure: id={}, title={}, score={}",
                    demoted.getId(), demoted.getTitle(), demoted.getLastScore());
        }
    }

    private void transitionStatus(MemoryEntity memory, Instant now) {
        long ageDays = ageDays(memory, now);
        if (!"ARCHIVED".equals(memory.getStatus())
                && ageDays >= memoryProperties.getEviction().getArchiveAfterDays()) {
            memory.setStatus("ARCHIVED");
            if (memory.getArchivedAt() == null) {
                memory.setArchivedAt(now);
            }
            if (memory.getArchivedReason() == null) {
                memory.setArchivedReason(REASON_EXPIRED_TTL);
            }
            return;
        }
        if ("ACTIVE".equals(memory.getStatus())
                && ageDays >= memoryProperties.getEviction().getStaleAfterDays()
                && memory.getRecallCount() < 3) {
            memory.setStatus("STALE");
            memory.setArchivedAt(null);
            // Do NOT clear archivedReason: STALE is reversible and a future ARCHIVE
            // transition will overwrite the reason itself.
        }
    }

    private boolean isExpiredArchived(MemoryEntity memory, Instant now) {
        if (!"ARCHIVED".equals(memory.getStatus()) || memory.getArchivedAt() == null) {
            return false;
        }
        return memory.getArchivedAt()
                .isBefore(now.minus(memoryProperties.getEviction().getDeleteAfterDays(), ChronoUnit.DAYS));
    }

    private static long ageDays(MemoryEntity memory, Instant now) {
        Instant anchor = memory.getLastRecalledAt();
        if (anchor == null && memory.getCreatedAt() != null) {
            anchor = memory.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (anchor == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(anchor, now);
        return Math.max(days, 0);
    }

    private static double scoreMemory(MemoryEntity memory, Instant now) {
        double importanceScore = switch (memory.getImportance() == null ? "medium" : memory.getImportance()) {
            case "high" -> 1.0;
            case "low" -> 0.3;
            default -> 0.6;
        };
        long ageDays = ageDays(memory, now);
        double recencyScore = Math.exp(-ageDays / 30.0);
        double recallScore = Math.log(1 + Math.max(memory.getRecallCount(), 0)) / Math.log(11);
        double freshnessScore = 0.5 + 0.5 * recencyScore;
        double usageScore = 0.3 + 0.7 * recallScore;
        return 0.45 * importanceScore
                + 0.35 * freshnessScore
                + 0.20 * usageScore;
    }
}
