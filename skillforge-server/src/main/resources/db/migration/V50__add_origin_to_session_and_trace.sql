-- EVAL-V2 M3a (b1): origin field on t_session + t_llm_trace
--
-- 引入 origin 字段区分 production 流量与 eval 流量，避免 eval 调用污染：
--   * production 可观测数据（dashboard 用量 / cost / token）
--   * production 列表视图（OBS sessions / traces）
--   * production 维护任务（compaction、startup recovery）
--
-- 数值集合（应用层常量，未在 schema 强制 CHECK）：
--   * 'production'（默认；老行回填）
--   * 'eval'（EvalOrchestrator 派出的子 session + 其 trace）
-- 后续可拓展（'preview' / 'shadow'），保留 16 字符宽度。
--
-- 索引使用 partial index：
--   * 仅索引 origin != 'production' 的少数行，几乎所有查询都不触碰索引（默认 production）
--   * eval task UI / OBS toggle 查询 origin='eval' 时命中 partial index
--   * 跟绝大多数查询不冲突，写入开销近似 0
--
-- t_session 由 skillforge-server 拥有，t_llm_trace 由 skillforge-observability 拥有，
-- 但 Spring Boot Flyway 在统一 classpath:db/migration 下扫描两模块资源，所以 V50
-- 统一放 skillforge-server 即可。observability 模块的 standalone Flyway 测试不引用
-- t_llm_trace.origin，无需该 migration。

-- IF NOT EXISTS guards on both ALTER TABLE and CREATE INDEX align with project convention
-- (V40 / V41 / V43 / V45 / V48 all use IF NOT EXISTS) and make this migration safe to re-run
-- on environments that may already have the column / index from a partially completed earlier
-- attempt — Flyway will still register the migration as applied and skip re-running on success.
ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS origin VARCHAR(16) NOT NULL DEFAULT 'production';

CREATE INDEX IF NOT EXISTS idx_session_origin ON t_session (origin)
    WHERE origin != 'production';

ALTER TABLE t_llm_trace
    ADD COLUMN IF NOT EXISTS origin VARCHAR(16) NOT NULL DEFAULT 'production';

CREATE INDEX IF NOT EXISTS idx_trace_origin ON t_llm_trace (origin)
    WHERE origin != 'production';
