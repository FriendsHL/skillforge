-- V137__create_agent_evolve_ab_run.sql — AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1.
--
-- One table backing the whole-agent A/B run (路 B / 指针元组 / 一个整体分). A
-- bundle is a tuple of per-surface version pointers ({promptVersionId?,
-- behaviorRuleVersionId?}); the candidate/baseline "content" still lives in the
-- existing per-surface version tables (t_prompt_version / t_behavior_rule_version).
-- This row records ONE whole-agent A/B run so the evolve loop can read it back
-- (tech-design.md §1).
--
-- Schema decisions (tech-design §7 W6):
--   * TIMESTAMPTZ for time columns (V70+ convention; Hibernate Instant round-trips).
--   * NO created_at column — mirror BehaviorRuleAbRunEntity: only started_at
--     (NOT NULL, @PrePersist default) + completed_at. Avoids the half-wired
--     @CreatedDate / audit-listener pattern flagged in W6.
--   * VARCHAR(36) PK (UUID) — mirrors PromptAbRun / BehaviorRuleAbRun.
--   * agent_id VARCHAR(36) — String for surface symmetry (agents store
--     Long.toString(id)); ownership guard compares this against targetAgentId.
--   * candidate_bundle_json / baseline_bundle_json: the pointer tuples, TEXT.
--   * prior_winner_ab_run_id (§7 W1): when skip_baseline=true, the service
--     asserts the incoming baseline_bundle_json structurally equals THIS prior
--     winner run's candidate_bundle_json before trusting cached_baseline_rate.
--   * target_delta_pp / regression_delta_pp: Phase 2 non-regression gate; Phase 1
--     leaves them NULL.
--   * dataset_version_id: REAL FK to t_eval_dataset_version (matches V111/V113/V115
--     convention). ON DELETE RESTRICT — deleting a dataset version mid-run would
--     strand the pointer; force operators to retire runs first. Nullable.
--
-- Idempotency: brand-new table. Flyway version uniqueness handles re-runs.

CREATE TABLE t_agent_evolve_ab_run (
    id                       VARCHAR(36)  PRIMARY KEY,
    agent_id                 VARCHAR(36)  NOT NULL,
    -- Pointer tuples: {"promptVersionId":..,"behaviorRuleVersionId":..} (null = agent's active version)
    candidate_bundle_json    TEXT         NOT NULL,
    baseline_bundle_json     TEXT         NOT NULL,
    dataset_version_id       VARCHAR(36),
    skip_baseline            BOOLEAN          NOT NULL DEFAULT FALSE,
    cached_baseline_rate     DOUBLE PRECISION,
    baseline_pass_rate       DOUBLE PRECISION,
    candidate_pass_rate      DOUBLE PRECISION,
    delta_pass_rate          DOUBLE PRECISION,
    target_delta_pp          DOUBLE PRECISION,     -- Phase 2; Phase 1 NULL
    regression_delta_pp      DOUBLE PRECISION,     -- Phase 2; Phase 1 NULL
    ab_scenario_results_json TEXT,
    status                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    prior_winner_ab_run_id   VARCHAR(36),          -- §7 W1 back-reference (winner-carry-forward)
    failure_reason           TEXT,
    triggered_by_user_id     BIGINT,
    started_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at             TIMESTAMPTZ
);

CREATE INDEX idx_aear_agent_status ON t_agent_evolve_ab_run(agent_id, status);

-- Backs the prior-winner lookup (§7 W1): most-recent COMPLETED run for an agent.
CREATE INDEX idx_aear_agent_completed ON t_agent_evolve_ab_run(agent_id, completed_at);

-- Real FK to the immutable dataset snapshot (matches V111/V113/V115). RESTRICT so
-- a dataset version can't be deleted while an A/B run still references it.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_aear_dataset_version') THEN
    ALTER TABLE t_agent_evolve_ab_run
        ADD CONSTRAINT fk_aear_dataset_version
        FOREIGN KEY (dataset_version_id) REFERENCES t_eval_dataset_version(id)
        ON DELETE RESTRICT;
  END IF;
END $$;

CREATE INDEX idx_aear_dataset_version
    ON t_agent_evolve_ab_run(dataset_version_id)
    WHERE dataset_version_id IS NOT NULL;
