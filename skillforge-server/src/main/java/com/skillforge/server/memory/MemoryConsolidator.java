package com.skillforge.server.memory;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.util.VectorUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
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

    /**
     * MEMORY-DREAM-CONSOLIDATION-V2.5 — per-user consolidation result with detailed
     * action counts. Surfaced via the admin endpoint so operators can see what
     * actually happened (vs. just "succeeded:1") without SQL.
     */
    public record ConsolidationResult(
            int dedupArchived,        // Phase 0: cosine-similarity dedup losers archived
            int ttlArchived,          // Phase 1: ACTIVE/STALE → ARCHIVED via age threshold
            int staleTransitioned,    // Phase 1: ACTIVE → STALE via age threshold
            int capacityDemoted,      // Phase 2: ACTIVE → STALE via capacity overflow
            int expiredDeleted,       // Phase 1: ARCHIVED rows past delete-after-days dropped
            int activeAfter           // ACTIVE count after all phases
    ) {
        public static ConsolidationResult empty() {
            return new ConsolidationResult(0, 0, 0, 0, 0, 0);
        }
        public ConsolidationResult plus(ConsolidationResult o) {
            return new ConsolidationResult(
                    dedupArchived + o.dedupArchived,
                    ttlArchived + o.ttlArchived,
                    staleTransitioned + o.staleTransitioned,
                    capacityDemoted + o.capacityDemoted,
                    expiredDeleted + o.expiredDeleted,
                    activeAfter + o.activeAfter
            );
        }
    }

    private final MemoryRepository memoryRepository;
    private MemoryProperties memoryProperties = new MemoryProperties();

    /**
     * Optional — used only to probe whether the {@code t_memory.embedding} column exists
     * before issuing the embedding-dedup query. When pgvector is not installed in the
     * cluster, V7's {@code CREATE EXTENSION} fails and the {@code embedding} column is never
     * added (FTS-only degraded mode); issuing the native dedup query then throws SQLState
     * 42703, which Hibernate logs at ERROR on every consolidation run. Probing once (cached)
     * lets us skip the doomed query entirely. Null in unit tests → falls back to the legacy
     * "issue query, catch failure" path.
     */
    private DataSource dataSource;
    /** Cached result of the embedding-column probe; null = not yet probed. */
    private volatile Boolean embeddingColumnAvailable;

    public MemoryConsolidator(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Autowired(required = false)
    public void setMemoryProperties(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties != null ? memoryProperties : new MemoryProperties();
    }

    @Autowired(required = false)
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Lazily detect (and cache) whether {@code t_memory.embedding} exists, using JDBC
     * {@link java.sql.DatabaseMetaData} — a metadata lookup, not a query against the column,
     * so it never trips SQLState 42703 nor Hibernate's ERROR-level SqlExceptionHelper.
     *
     * <p>When no {@link DataSource} is wired (unit tests), returns {@code true} so callers
     * keep their existing behaviour (issue the query, catch any failure).
     *
     * <p>Caching semantics: only a <em>definitive</em> probe result (the metadata query
     * succeeded) is cached in {@link #embeddingColumnAvailable} — write-once thereafter.
     * A probe <em>failure</em> (e.g. transient pool exhaustion) returns {@code true}
     * <em>without</em> caching, so a one-off blip can't permanently pin us to the noisy
     * issue-and-catch path; the next run re-probes. The probe is idempotent, so a benign
     * race between two first-callers just recomputes the same value.
     */
    private boolean isEmbeddingColumnAvailable() {
        Boolean cached = embeddingColumnAvailable;
        if (cached != null) {
            return cached;
        }
        if (dataSource == null) {
            return true; // can't probe → let the query run and be caught (legacy/unit-test path)
        }
        try (Connection c = dataSource.getConnection();
             // catalog=null (not getCatalog()): pgjdbc honours the catalog filter and
             // getCatalog() returns the db name, which can yield a false-negative; null = any.
             ResultSet rs = c.getMetaData().getColumns(null, null, "t_memory", "embedding")) {
            // getColumns treats '_' as a single-char wildcard → confirm exact table + column.
            boolean found = false;
            while (rs.next()) {
                if ("t_memory".equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && "embedding".equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                log.info("Memory dedup: t_memory.embedding column not present "
                        + "(pgvector disabled, FTS-only mode); embedding dedup disabled for this runtime.");
            }
            embeddingColumnAvailable = found; // cache only the definitive result
            return found;
        } catch (Exception e) {
            // Probe failure → fail open (don't disable dedup) and DON'T cache → retry next run.
            log.debug("Memory dedup: embedding-column probe failed, will retry next run: {}",
                    e.getMessage());
            return true;
        }
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
    public ConsolidationResult consolidate(Long userId) {
        if (memoryRepository == null || userId == null) {
            return ConsolidationResult.empty();
        }

        // Phase 0 — embedding dedup BEFORE scoring/transition. Demote-as-archive happens
        // here so subsequent capacity / status work sees the post-dedup ACTIVE set.
        int dedupArchived = deduplicateByEmbedding(userId);

        List<MemoryEntity> all = memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (all.isEmpty()) {
            return new ConsolidationResult(dedupArchived, 0, 0, 0, 0, 0);
        }
        Instant now = Instant.now();
        int ttlArchived = 0;
        int staleTransitioned = 0;
        int expiredDeleted = 0;
        for (MemoryEntity memory : all) {
            if (isExpiredArchived(memory, now)) {
                memoryRepository.delete(memory);
                expiredDeleted++;
                log.info("Deleted expired archived memory: id={}, title={}, reason={}",
                        memory.getId(), memory.getTitle(), memory.getArchivedReason());
                continue;
            }
            memory.setLastScore(scoreMemory(memory, now));
            memory.setLastScoredAt(now);
            String beforeStatus = memory.getStatus();
            transitionStatus(memory, now);
            String afterStatus = memory.getStatus();
            if (!beforeStatus.equals(afterStatus)) {
                if ("ARCHIVED".equals(afterStatus)) {
                    ttlArchived++;
                } else if ("STALE".equals(afterStatus)) {
                    staleTransitioned++;
                }
            }
            memoryRepository.save(memory);
        }
        int capacityDemoted = enforceCapacity(all);
        long activeAfter = all.stream()
                .filter(m -> "ACTIVE".equals(m.getStatus()))
                .count();
        return new ConsolidationResult(
                dedupArchived, ttlArchived, staleTransitioned,
                capacityDemoted, expiredDeleted, (int) activeAfter);
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
    private int deduplicateByEmbedding(Long userId) {
        double threshold = memoryProperties.getDedup().getCosineMergeThreshold();
        if (threshold <= 0.0 || threshold >= 1.0) {
            return 0; // disabled / nonsensical config
        }

        // Skip the doomed native query when the embedding column doesn't exist (pgvector
        // unavailable). Avoids a recurring SQLState 42703 ERROR in the logs every run.
        if (!isEmbeddingColumnAvailable()) {
            return 0;
        }

        List<Object[]> rows;
        try {
            rows = memoryRepository.findEmbeddingsForActiveByUser(userId);
        } catch (Exception e) {
            // pgvector unavailable, transient DB issue, etc. — degrade gracefully.
            log.warn("Memory dedup: embedding fetch failed for userId={}, skipping dedup: {}",
                    userId, e.getMessage());
            return 0;
        }
        if (rows == null || rows.size() < 2) {
            return 0;
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
            return 0;
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
        return archivedCount;
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

    private int enforceCapacity(List<MemoryEntity> all) {
        int cap = memoryProperties.getEviction().getMaxActivePerUser();
        List<MemoryEntity> active = all.stream()
                .filter(memory -> "ACTIVE".equals(memory.getStatus()))
                .sorted(Comparator
                        .comparing(MemoryEntity::getLastScore, Comparator.nullsLast(Double::compareTo))
                        .thenComparing(MemoryEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (active.size() <= cap) {
            return 0;
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
        return overflow;
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
