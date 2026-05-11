-- OBS-2 M6: legacy trace table cleanup.
--
-- M4 closed every production write path into t_trace_span. After the single-track
-- observation window, SkillForge reads traces/spans from t_llm_trace + t_llm_span only.
-- Keep this guarded so fresh databases created from newer baselines and partially
-- migrated local environments both succeed.
DROP TABLE IF EXISTS t_trace_span;
