-- EVAL-V2 M3a (b1, r2 fix #4): origin column on t_llm_trace, observability-side copy.
--
-- Why two V## files for the same column:
--   * skillforge-server V50 alters both t_session AND t_llm_trace, intended for
--     production server runtime where Flyway sees a merged classpath:db/migration
--     from BOTH modules and runs every migration once in version order.
--   * observability standalone IT (PgLlmTraceUpsertTest, PgLlmTraceLifecycleIT,
--     RootTraceIdImmutableIT, PgLlmTraceSpanMigrationIT) configures Flyway with
--     classpath:db/migration that only sees observability's resources — server's
--     V50 is not on their classpath, so without this V51 their schema would lack
--     t_llm_trace.origin and any LlmTraceEntity-mediated read would fail.
--
-- Why version 51 (not 47–50):
--   * Server already owns V47/V48/V49/V50 — using them in observability would
--     duplicate-version on the merged production classpath. Observability's
--     local highest is V46; the next unique number across the merged space is V51
--     (server's V50 + 1). Future server migrations must skip 51 and start at V52.
--
-- Why ALTER ... IF NOT EXISTS / CREATE INDEX IF NOT EXISTS:
--   * At server runtime both V50 (adds origin) and V51 (this file) run in order;
--     IF NOT EXISTS makes V51 a guaranteed no-op once V50 has run.
--   * At observability standalone test runtime only V51 runs — it actually adds
--     the column.
--   * The 'production' DEFAULT matches V50's exactly so there's no schema drift.
--
-- t_session 部分故意不在此 migration —— observability schema 不该有该表。

ALTER TABLE t_llm_trace
    ADD COLUMN IF NOT EXISTS origin VARCHAR(16) NOT NULL DEFAULT 'production';

CREATE INDEX IF NOT EXISTS idx_trace_origin ON t_llm_trace (origin)
    WHERE origin != 'production';
