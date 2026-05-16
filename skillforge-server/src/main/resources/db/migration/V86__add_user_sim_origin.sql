-- V86__add_user_sim_origin.sql — V5 EVAL-DYNAMIC-USER-SIM Phase 1.3.
--
-- 1) First-time CHECK constraint on t_session.origin enumerating the closed
--    set {production, eval, user_sim}. Phase 1.0 grep校正 confirmed: V50 added
--    origin column with no CHECK; current rows are 'production' (default) or
--    'eval' (EvalOrchestrator). V5 adds 'user_sim' for UserSimulatorAgent trial
--    transcripts.
--
-- 2) V50 partial indexes (e.g. "WHERE origin != 'production'") keep working —
--    they don't reference 'user_sim' explicitly, so new user_sim rows match
--    them automatically (good — keeps eval-list dashboards generic).

ALTER TABLE t_session
    DROP CONSTRAINT IF EXISTS chk_session_origin;

ALTER TABLE t_session
    ADD CONSTRAINT chk_session_origin
    CHECK (origin IN ('production', 'eval', 'user_sim'));
