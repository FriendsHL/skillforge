-- AUTOEVOLVE-CLOSE-LOOP Phase BC-M1 (2026-06-04): persist per-scenario fixture
-- files for session-derived (harvested) bad-case scenarios. Benchmark scenarios
-- keep loading fixtures from disk JSON (EvalScenario.setup.files); session_derived
-- scenarios have no disk JSON, so the fixture must live in the DB row.
--
-- Map<relativePath, fileContent>. nullable — every pre-existing scenario leaves
-- this NULL and falls back to setup.files at run time (AbEvalPipeline.runSingleScenario).
-- Content measured <40KB per file (proposal-badcase spike), so a single JSONB
-- column comfortably holds multi-file fixtures.

ALTER TABLE t_eval_scenario
    ADD COLUMN IF NOT EXISTS fixture_files_json JSONB;
