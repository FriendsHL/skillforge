package com.skillforge.server.migration;

import com.skillforge.server.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies V29__memory_v2_status_importance.sql post-conditions:
 *
 * <ol>
 *   <li>New columns exist on t_memory / t_memory_snapshot / t_session with the documented
 *       defaults so existing JPA reads/writes keep working unchanged (PR-1 invariant).</li>
 *   <li>The CSV-tag → status / importance backfill SQL (re-executed here against
 *       freshly inserted rows) catches every CSV-token-boundary case and rejects
 *       look-alike substrings such as {@code unstale_thing} or
 *       {@code importance:highish}.</li>
 *   <li>The chk_memory_status / chk_memory_importance constraints reject invalid values.</li>
 * </ol>
 *
 * <p>Note on rationale: V29 ran during Flyway startup of this Spring context, so we cannot
 * test the original UPDATE against pre-existing legacy data. We therefore re-run the same
 * UPDATE statements against test rows we control — this still validates the SQL pattern
 * (which is the part most likely to silently mis-match), and the constraint asserts double
 * down by exercising the post-V29 schema directly.
 */
@DisplayName("V29 memory v2 schema migration")
class V29MemoryV2MigrationIT extends AbstractPostgresIT {

    @PersistenceContext
    private EntityManager entityManager;

    @Nested
    @DisplayName("Schema: new columns and defaults")
    class SchemaShape {

        @Test
        @DisplayName("t_memory has status / archived_at / importance / last_score / last_scored_at")
        void memoryColumnsPresentWithDefaults() {
            Map<String, Map<String, Object>> cols = describeColumns("t_memory");

            assertThat(cols).containsKeys(
                    "status", "archived_at", "importance", "last_score", "last_scored_at");

            assertThat(cols.get("status").get("data_type")).isEqualTo("character varying");
            assertThat(cols.get("status").get("is_nullable")).isEqualTo("NO");
            assertThat(((String) cols.get("status").get("column_default"))).contains("ACTIVE");

            assertThat(cols.get("importance").get("is_nullable")).isEqualTo("NO");
            assertThat(((String) cols.get("importance").get("column_default"))).contains("medium");

            assertThat(cols.get("archived_at").get("is_nullable")).isEqualTo("YES");
            assertThat(cols.get("last_score").get("data_type")).isEqualTo("double precision");
            assertThat(cols.get("last_score").get("is_nullable")).isEqualTo("YES");
        }

        @Test
        @DisplayName("t_memory_snapshot mirrors v2 fields with same defaults")
        void snapshotColumnsMirrorMemory() {
            Map<String, Map<String, Object>> cols = describeColumns("t_memory_snapshot");

            assertThat(cols).containsKeys(
                    "status", "archived_at", "importance", "last_score", "last_scored_at");
            assertThat(cols.get("status").get("is_nullable")).isEqualTo("NO");
            assertThat(((String) cols.get("status").get("column_default"))).contains("ACTIVE");
            assertThat(cols.get("importance").get("is_nullable")).isEqualTo("NO");
            assertThat(((String) cols.get("importance").get("column_default"))).contains("medium");
        }

        @Test
        @DisplayName("t_session has last_extracted_message_seq BIGINT NOT NULL DEFAULT 0")
        void sessionCursorAdded() {
            Map<String, Map<String, Object>> cols = describeColumns("t_session");

            assertThat(cols).containsKey("last_extracted_message_seq");
            Map<String, Object> col = cols.get("last_extracted_message_seq");
            assertThat(col.get("data_type")).isEqualTo("bigint");
            assertThat(col.get("is_nullable")).isEqualTo("NO");
            assertThat(((String) col.get("column_default"))).contains("0");
        }

        @Test
        @DisplayName("indexes idx_memory_user_status_score and idx_memory_archived_at exist")
        void indexesPresent() {
            @SuppressWarnings("unchecked")
            List<String> idx = entityManager.createNativeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 't_memory'")
                    .getResultList();
            assertThat(idx).contains("idx_memory_user_status_score", "idx_memory_archived_at");
        }
    }

    @Nested
    @DisplayName("Backfill SQL: CSV tag boundary correctness")
    class BackfillBoundaries {

        /**
         * Re-runs V29's UPDATE clauses against rows we just inserted, then asserts that
         * legitimate token positions are caught and look-alike substrings are not.
         */
        @Test
        @Transactional
        @DisplayName("status='STALE' is set only when 'stale' is a whole CSV token")
        void staleBackfillRespectsTokenBoundaries() {
            // Truthy cases — should be promoted to STALE
            long shouldStaleA = insertMemory("knowledge", "leading", "x", "stale,foo");
            long shouldStaleB = insertMemory("knowledge", "middle", "x", "foo,stale,bar");
            long shouldStaleC = insertMemory("knowledge", "trailing", "x", "foo,stale");
            long shouldStaleD = insertMemory("knowledge", "only", "x", "stale");

            // Falsey cases — substring look-alikes that must NOT match
            long noMatchA = insertMemory("knowledge", "prefix-substr", "x", "unstale_thing");
            long noMatchB = insertMemory("knowledge", "suffix-substr", "x", "stalemate");
            long noMatchC = insertMemory("knowledge", "embedded-substr", "x", "foo,prestale,bar");
            long noMatchD = insertMemory("knowledge", "no-tags", "x", null);
            long noMatchE = insertMemory("knowledge", "empty-tags", "x", "");

            entityManager.createNativeQuery("""
                    UPDATE t_memory SET status = 'STALE'
                     WHERE status = 'ACTIVE'
                       AND ( tags = 'stale'
                          OR tags LIKE 'stale,%'
                          OR tags LIKE '%,stale,%'
                          OR tags LIKE '%,stale' )
                    """).executeUpdate();
            entityManager.flush();
            entityManager.clear();

            assertThat(statusOf(shouldStaleA)).isEqualTo("STALE");
            assertThat(statusOf(shouldStaleB)).isEqualTo("STALE");
            assertThat(statusOf(shouldStaleC)).isEqualTo("STALE");
            assertThat(statusOf(shouldStaleD)).isEqualTo("STALE");

            assertThat(statusOf(noMatchA)).isEqualTo("ACTIVE");
            assertThat(statusOf(noMatchB)).isEqualTo("ACTIVE");
            assertThat(statusOf(noMatchC)).isEqualTo("ACTIVE");
            assertThat(statusOf(noMatchD)).isEqualTo("ACTIVE");
            assertThat(statusOf(noMatchE)).isEqualTo("ACTIVE");
        }

        @Test
        @Transactional
        @DisplayName("importance backfill respects token boundaries for high/medium/low")
        void importanceBackfillRespectsTokenBoundaries() {
            long high = insertMemory("knowledge", "h-leading", "x", "importance:high,foo");
            long med = insertMemory("knowledge", "m-middle", "x", "foo,importance:medium,bar");
            long low = insertMemory("knowledge", "l-trailing", "x", "foo,importance:low");
            long highOnly = insertMemory("knowledge", "h-only", "x", "importance:high");

            // Substring look-alike that must NOT match
            long bogus = insertMemory("knowledge", "bogus", "x", "importance:highish");
            // Default-importance row should remain 'medium'
            long defaultRow = insertMemory("knowledge", "default", "x", "unrelated,tag");

            for (String level : List.of("high", "medium", "low")) {
                entityManager.createNativeQuery("""
                        UPDATE t_memory SET importance = :lvl
                         WHERE ( tags = :token
                              OR tags LIKE :leading
                              OR tags LIKE :middle
                              OR tags LIKE :trailing )
                        """)
                        .setParameter("lvl", level)
                        .setParameter("token", "importance:" + level)
                        .setParameter("leading", "importance:" + level + ",%")
                        .setParameter("middle", "%,importance:" + level + ",%")
                        .setParameter("trailing", "%,importance:" + level)
                        .executeUpdate();
            }
            entityManager.flush();
            entityManager.clear();

            assertThat(importanceOf(high)).isEqualTo("high");
            assertThat(importanceOf(highOnly)).isEqualTo("high");
            assertThat(importanceOf(med)).isEqualTo("medium");
            assertThat(importanceOf(low)).isEqualTo("low");
            assertThat(importanceOf(bogus)).isEqualTo("medium"); // V29 default, untouched
            assertThat(importanceOf(defaultRow)).isEqualTo("medium");
        }
    }

    @Nested
    @DisplayName("Step 5b: snapshot rows mirror just-backfilled memory status / importance")
    class SnapshotBackfill {

        /**
         * Regression for BE-W1 (round 1 review): pre-V29 in-flight extraction batches own
         * snapshot rows that were created with no status / importance columns. The V29
         * column ADDs default those snapshots to ACTIVE/medium, but the live memory may
         * have been promoted to STALE/high by the step-5 tag backfill. Without the 5b
         * UPDATE, rolling back such a batch via {@link
         * com.skillforge.server.service.MemoryService#rollbackExtractionBatch} would
         * silently restore the snapshot's stale ACTIVE value, producing the contradictory
         * {@code tags='stale' AND status='ACTIVE'} state that PR-2 would then surface as
         * a live recall.
         *
         * <p>This test exercises the 5b SQL directly: it sets up the post-step-5
         * divergent state (memory STALE/high, snapshot ACTIVE/medium) and re-runs the
         * 5b UPDATE, asserting the snapshot is brought back into sync.
         */
        @Test
        @Transactional
        @DisplayName("5b UPDATE syncs snapshot.status/importance to backfilled t_memory values")
        void step5b_propagatesStatusAndImportanceToSnapshot() {
            // Arrange: one memory already promoted to STALE/high (post-step-5 state),
            // and a matching snapshot row stuck at the V29 column defaults (ACTIVE/medium).
            long memoryId = insertMemory("knowledge", "stale memory", "c", "stale");
            entityManager.createNativeQuery(
                    "UPDATE t_memory SET status = 'STALE', importance = 'high' WHERE id = :id")
                    .setParameter("id", memoryId)
                    .executeUpdate();
            String batchId = "test-batch-" + memoryId;
            entityManager.createNativeQuery("""
                    INSERT INTO t_memory_snapshot
                        (extraction_batch_id, memory_id, user_id, type, title, content, tags,
                         status, importance)
                    VALUES (:b, :m, 999, 'knowledge', 'stale memory', 'c', 'stale',
                            'ACTIVE', 'medium')
                    """)
                    .setParameter("b", batchId)
                    .setParameter("m", memoryId)
                    .executeUpdate();

            // Act: re-run V29 step 5b in isolation.
            int updated = entityManager.createNativeQuery("""
                    UPDATE t_memory_snapshot s
                       SET status     = m.status,
                           importance = m.importance
                      FROM t_memory m
                     WHERE s.memory_id = m.id
                    """).executeUpdate();
            entityManager.flush();
            entityManager.clear();

            // Assert: exactly the one matched snapshot was updated (Schema-r2-W6:
            // catches a regression where the WHERE silently widens to a no-op).
            assertThat(updated).isEqualTo(1);

            // Assert: snapshot now mirrors the live memory state.
            Object[] row = (Object[]) entityManager.createNativeQuery(
                    "SELECT status, importance FROM t_memory_snapshot WHERE memory_id = :m")
                    .setParameter("m", memoryId)
                    .getSingleResult();
            assertThat(row[0]).isEqualTo("STALE");
            assertThat(row[1]).isEqualTo("high");
        }

        /**
         * Regression for Schema-r2-W5: extraction batches retried after partial failure
         * can produce multiple snapshot rows for the same memory_id. Step 5b must rewrite
         * every such row, not just one — otherwise rollback of the older batch would still
         * de-sync.
         */
        @Test
        @Transactional
        @DisplayName("5b UPDATE rewrites every snapshot row that shares a memory_id")
        void step5b_propagatesToMultipleSnapshotsPerMemory() {
            long memoryId = insertMemory("knowledge", "multi-snap memory", "c", "stale");
            entityManager.createNativeQuery(
                    "UPDATE t_memory SET status = 'STALE', importance = 'high' WHERE id = :id")
                    .setParameter("id", memoryId)
                    .executeUpdate();
            for (String batchSuffix : List.of("a", "b")) {
                entityManager.createNativeQuery("""
                        INSERT INTO t_memory_snapshot
                            (extraction_batch_id, memory_id, user_id, type, title, content, tags,
                             status, importance)
                        VALUES (:b, :m, 999, 'knowledge', 't', 'c', 'stale',
                                'ACTIVE', 'medium')
                        """)
                        .setParameter("b", "multi-batch-" + batchSuffix)
                        .setParameter("m", memoryId)
                        .executeUpdate();
            }

            int updated = entityManager.createNativeQuery("""
                    UPDATE t_memory_snapshot s
                       SET status     = m.status,
                           importance = m.importance
                      FROM t_memory m
                     WHERE s.memory_id = m.id
                    """).executeUpdate();
            entityManager.flush();
            entityManager.clear();

            assertThat(updated).isEqualTo(2);
            List<?> rows = entityManager.createNativeQuery(
                    "SELECT status, importance FROM t_memory_snapshot WHERE memory_id = :m")
                    .setParameter("m", memoryId)
                    .getResultList();
            assertThat(rows).hasSize(2);
            for (Object o : rows) {
                Object[] r = (Object[]) o;
                assertThat(r[0]).isEqualTo("STALE");
                assertThat(r[1]).isEqualTo("high");
            }
        }

        @Test
        @Transactional
        @DisplayName("5b UPDATE leaves snapshot rows with no matching memory untouched")
        void step5b_orphanSnapshotKeepsDefaults() {
            // Snapshot whose memory_id does not correspond to any live t_memory row
            // (e.g. memory was deleted between the batch and the migration). The 5b
            // INNER JOIN must skip it, leaving the V29 defaults in place rather than
            // throwing.
            String batchId = "orphan-batch";
            entityManager.createNativeQuery("""
                    INSERT INTO t_memory_snapshot
                        (extraction_batch_id, memory_id, user_id, type, title, content, tags,
                         status, importance)
                    VALUES (:b, 99999999, 999, 'knowledge', 't', 'c', null,
                            'ACTIVE', 'medium')
                    """)
                    .setParameter("b", batchId)
                    .executeUpdate();

            entityManager.createNativeQuery("""
                    UPDATE t_memory_snapshot s
                       SET status     = m.status,
                           importance = m.importance
                      FROM t_memory m
                     WHERE s.memory_id = m.id
                    """).executeUpdate();
            entityManager.flush();
            entityManager.clear();

            Object[] row = (Object[]) entityManager.createNativeQuery(
                    "SELECT status, importance FROM t_memory_snapshot " +
                            "WHERE extraction_batch_id = :b")
                    .setParameter("b", batchId)
                    .getSingleResult();
            assertThat(row[0]).isEqualTo("ACTIVE");
            assertThat(row[1]).isEqualTo("medium");
        }
    }

    @Nested
    @DisplayName("Domain constraints reject invalid values")
    class Constraints {

        @Test
        @Transactional
        @DisplayName("chk_memory_status rejects unknown status")
        void invalidStatusIsRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
                entityManager.createNativeQuery(
                        "INSERT INTO t_memory (user_id, type, title, content, status) " +
                                "VALUES (:u, 'knowledge', 't', 'c', 'BOGUS')")
                        .setParameter("u", 1L)
                        .executeUpdate();
                entityManager.flush();
            });
        }

        @Test
        @Transactional
        @DisplayName("chk_memory_importance rejects unknown importance")
        void invalidImportanceIsRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
                entityManager.createNativeQuery(
                        "INSERT INTO t_memory (user_id, type, title, content, importance) " +
                                "VALUES (:u, 'knowledge', 't', 'c', 'critical')")
                        .setParameter("u", 1L)
                        .executeUpdate();
                entityManager.flush();
            });
        }
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> describeColumns(String table) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT column_name, data_type, is_nullable, column_default
                  FROM information_schema.columns
                 WHERE table_name = :t
                """)
                .setParameter("t", table)
                .getResultList();
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> (String) r[0],
                r -> Map.of(
                        "data_type", r[1],
                        "is_nullable", r[2],
                        "column_default", r[3] == null ? "" : r[3])));
    }

    private long insertMemory(String type, String title, String content, String tags) {
        Number id = (Number) entityManager.createNativeQuery(
                "INSERT INTO t_memory (user_id, type, title, content, tags) " +
                        "VALUES (:u, :ty, :ti, :c, :tg) RETURNING id")
                .setParameter("u", 999L)
                .setParameter("ty", type)
                .setParameter("ti", title)
                .setParameter("c", content)
                .setParameter("tg", tags)
                .getSingleResult();
        return id.longValue();
    }

    private String statusOf(long id) {
        return (String) entityManager.createNativeQuery(
                "SELECT status FROM t_memory WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    private String importanceOf(long id) {
        return (String) entityManager.createNativeQuery(
                "SELECT importance FROM t_memory WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }
}
