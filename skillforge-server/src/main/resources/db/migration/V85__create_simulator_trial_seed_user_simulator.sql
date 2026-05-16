-- V85__create_simulator_trial_seed_user_simulator.sql — V5 EVAL-DYNAMIC-USER-SIM Phase 1.2.
--
-- Two responsibilities:
--   1) Create t_simulator_trial metadata table — per-trial elaborated outcome
--      (scenario_id × candidate_agent_version_id × candidate_surface_type × persona →
--      session_id + turns_used + termination_reason + observed_failure_signals).
--      Written first time by SimulatorTrialOrchestrator at trial start (PENDING),
--      then updated by RecordSimulationResult tool (UserSim agent self-call) or by
--      orchestrator at outer-loop close.
--   2) Seed user-simulator system agent — owner_id=1, is_public=TRUE (V75/V79/V81
--      ratified pattern). system_prompt is the SEE_FILE: sentinel swapped at boot by
--      UserSimulatorBootstrap. Tool list = ["RecordSimulationResult"] only (Phase 1.2.0
--      orchestrator design decision ratified by team-lead: RunSimulatorTrial is a
--      programmatic entry tool invoked by REST controller / Java services, not a tool
--      consumed inside UserSim agent loop — including it here would create dead-tool
--      confusion. The 4-ratify decision deviates from earlier prd.md draft).
--
-- Conventions (V81 mirror):
--   * owner_id = 1 (admin) + is_public = TRUE.
--   * lifecycle_hooks = NULL — UserSim does not broadcast SESSION_END.
--   * execution_mode = 'auto' — trials must not block on ask_user (eval engine
--     auto-fails ask_user anyway per EvalEngineFactory comment).
--   * Default model is the V5 Phase 1.0 ratify decision: llm_provider='xiaomi-mimo',
--     llm_model='mimo-v2.5-pro'. Reasoning model — maxTokens kept ≥4000 per Phase 1.0
--     footgun discovery. config.temperature=0.6 (persona-style variation; not 0.0 or
--     1.0 — persona feel needs some variance but unique persona traits dominate).

-- ---------- 1. t_simulator_trial metadata table ----------
CREATE TABLE t_simulator_trial (
    trial_id                    VARCHAR(36) PRIMARY KEY,
    scenario_id                 VARCHAR(36) NOT NULL,
    candidate_agent_version_id  VARCHAR(64),
    candidate_surface_type      VARCHAR(32),
    persona                     TEXT        NOT NULL,
    session_id                  VARCHAR(36) NOT NULL,
    turns_used                  INT         NOT NULL DEFAULT 0,
    termination_reason          VARCHAR(64),
    observed_failure_signals    TEXT,
    created_at                  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_simulator_trial_scenario  ON t_simulator_trial(scenario_id);
CREATE INDEX idx_simulator_trial_candidate ON t_simulator_trial(candidate_agent_version_id, candidate_surface_type);
CREATE INDEX idx_simulator_trial_session   ON t_simulator_trial(session_id);

-- ---------- 2. user-simulator system agent (idempotent) ----------
-- system_prompt is a SEE_FILE: sentinel; UserSimulatorBootstrap swaps it at
-- ApplicationReadyEvent from classpath:user-simulator-system-prompt.md.
-- tool_ids = ["RecordSimulationResult"] only — see header comment for rationale.
-- temperature=0.6: persona-style variation desired (kept >0 so 5 personas feel
-- distinct). maxTokens=4096: Phase 1.0 footgun — mimo-v2.5-pro reasoning model
-- needs ≥4000 budget for tool call iteration headroom.
INSERT INTO t_agent (
    name,
    description,
    model_id,
    system_prompt,
    skill_ids,
    tool_ids,
    config,
    lifecycle_hooks,
    owner_id,
    is_public,
    status,
    execution_mode,
    created_at,
    updated_at
)
SELECT
    'user-simulator',
    'System agent: V5 dynamic user simulator. Plays a fixed persona '
        || 'against a candidate AI agent for a scenario, drives multi-turn '
        || 'conversation until business goal met / failure signal triggered / '
        || 'max_turns exceeded. Writes t_simulator_trial elaborated metadata '
        || 'via RecordSimulationResult tool at termination. Drives '
        || 'EVAL-DYNAMIC-USER-SIM V5 candidate evaluation.',
    'mimo-v2.5-pro',
    'SEE_FILE:system-agents/user-simulator.system.md',
    '[]',
    '["RecordSimulationResult"]',
    '{"temperature": 0.6, "maxTokens": 4096, "llm_provider": "xiaomi-mimo"}',
    NULL,
    1,
    TRUE,
    'active',
    'auto',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'user-simulator'
);
