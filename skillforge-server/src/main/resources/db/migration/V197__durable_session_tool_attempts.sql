-- SESSION-HISTORY-RECOVERY Batch 1: durable Session run state, ordered message writes,
-- Tool attempt generations, unknown-outcome audit and post-action continuation shape.

-- The standard runtime role is deliberately distinct from the Flyway object owner.
-- Existing managed deployments may pre-provision it with LOGIN/password policy; local
-- embedded PostgreSQL promotes this NOLOGIN bootstrap role before opening runtime JDBC.
DO $$
BEGIN
    IF current_user = 'skillforge_app' THEN
        RAISE EXCEPTION 'skillforge_app cannot own or execute SkillForge migrations';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'skillforge_app') THEN
        CREATE ROLE skillforge_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS active_loop_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS loop_fence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS loop_owner_instance_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS loop_lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS restore_preparing BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE t_session
    DROP CONSTRAINT IF EXISTS ck_session_loop_fence;
ALTER TABLE t_session
    ADD CONSTRAINT ck_session_loop_fence CHECK (loop_fence >= 0);

ALTER TABLE t_session
    DROP CONSTRAINT IF EXISTS ck_session_loop_claim_shape;
ALTER TABLE t_session
    ADD CONSTRAINT ck_session_loop_claim_shape CHECK (
        (active_loop_id IS NULL AND loop_owner_instance_id IS NULL AND loop_lease_until IS NULL)
        OR
        (active_loop_id IS NOT NULL AND loop_owner_instance_id IS NOT NULL AND loop_lease_until IS NOT NULL)
    );

ALTER TABLE t_session_message
    ADD COLUMN IF NOT EXISTS write_batch_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS write_batch_ordinal INTEGER;

ALTER TABLE t_session_message
    DROP CONSTRAINT IF EXISTS ck_session_message_write_batch_shape;
ALTER TABLE t_session_message
    ADD CONSTRAINT ck_session_message_write_batch_shape CHECK (
        (write_batch_id IS NULL AND write_batch_ordinal IS NULL)
        OR
        (write_batch_id IS NOT NULL
         AND write_batch_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
         AND write_batch_ordinal IS NOT NULL AND write_batch_ordinal >= 0)
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_session_message_write_batch_ordinal
    ON t_session_message (session_id, write_batch_id, write_batch_ordinal)
    WHERE write_batch_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_session_message_id_session'
          AND conrelid = 't_session_message'::regclass
    ) THEN
        ALTER TABLE t_session_message
            ADD CONSTRAINT uq_session_message_id_session UNIQUE (id, session_id);
    END IF;
END
$$;

CREATE TABLE t_session_message_inbox (
    id           BIGSERIAL    NOT NULL PRIMARY KEY,
    inbox_id     UUID         NOT NULL,
    session_id   VARCHAR(36)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    message_json TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_session_message_inbox_inbox_id UNIQUE (inbox_id),
    CONSTRAINT fk_session_message_inbox_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE,
    CONSTRAINT ck_session_message_inbox_message_json
        CHECK (length(message_json) > 0)
);

CREATE INDEX idx_session_message_inbox_session_id
    ON t_session_message_inbox (session_id, id);

CREATE TABLE t_session_tool_attempt (
    id                              BIGSERIAL    NOT NULL PRIMARY KEY,
    session_id                      VARCHAR(36)  NOT NULL,
    step_id                         UUID         NOT NULL,
    history_epoch                   BIGINT       NOT NULL,
    origin_loop_id                  VARCHAR(36)  NOT NULL,
    origin_fence                    BIGINT       NOT NULL,
    assistant_message_id            BIGINT       NOT NULL,
    assistant_payload_hash          CHAR(64)     NOT NULL,
    pre_intent_max_message_id       BIGINT       NOT NULL,
    pre_intent_max_seq              BIGINT       NOT NULL,
    manifest_json                   TEXT         NOT NULL,
    manifest_hash                   CHAR(64)     NOT NULL,
    replay_safety                   VARCHAR(32)  NOT NULL,
    state                           VARCHAR(48)  NOT NULL,
    execution_loop_id               VARCHAR(36),
    execution_fence                 BIGINT,
    execution_owner_instance_id     VARCHAR(128),
    execution_generation            BIGINT       NOT NULL DEFAULT 0,
    claim_request_id                UUID,
    claimed_at                      TIMESTAMPTZ,
    execution_lease_until           TIMESTAMPTZ,
    result_batch_id                 UUID,
    result_execution_generation     BIGINT,
    result_execution_fence          BIGINT,
    archive_preparation_state       VARCHAR(24)  NOT NULL DEFAULT 'NOT_STARTED',
    archive_prepared_count          INTEGER      NOT NULL DEFAULT 0,
    archive_total_count             INTEGER      NOT NULL DEFAULT 0,
    post_action_state               VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    post_action_resolution_request_id UUID,
    post_action_result_batch_id     UUID,
    post_action_kind                VARCHAR(40),
    post_action_claim_request_id    UUID,
    post_action_loop_id             VARCHAR(36),
    post_action_fence               BIGINT,
    -- Internal W-R6-1 progress marker. V197 creates this table, so a populated V195 upgrade has
    -- no pre-existing attempt rows to backfill; the final shape is enforced from the first insert.
    post_action_inbox_handoff_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_session_tool_attempt_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_session_tool_attempt_assistant_message
        FOREIGN KEY (assistant_message_id, session_id)
        REFERENCES t_session_message(id, session_id) ON DELETE CASCADE,
    CONSTRAINT uq_session_tool_attempt_session_step UNIQUE (session_id, step_id),
    CONSTRAINT uq_session_tool_attempt_assistant_message UNIQUE (assistant_message_id),
    CONSTRAINT ck_session_tool_attempt_history_epoch CHECK (history_epoch >= 0),
    CONSTRAINT ck_session_tool_attempt_origin_fence CHECK (origin_fence >= 0),
    CONSTRAINT ck_session_tool_attempt_frontier CHECK (
        (pre_intent_max_message_id = -1 AND pre_intent_max_seq = -1)
        OR
        (pre_intent_max_message_id > 0 AND pre_intent_max_seq >= 0)
    ),
    CONSTRAINT ck_session_tool_attempt_assistant_hash CHECK (
        assistant_payload_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_session_tool_attempt_manifest CHECK (
        length(manifest_json) > 0 AND manifest_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_session_tool_attempt_replay_safety CHECK (replay_safety IN (
        'READ_ONLY_REPLAYABLE', 'IDEMPOTENT_KEYED', 'MUTATING', 'UNKNOWN'
    )),
    CONSTRAINT ck_session_tool_attempt_state CHECK (state IN (
        'INTENT_COMMITTED', 'EXECUTING', 'WAITING_USER', 'RESULTS_COMMITTED',
        'UNCERTAIN_PENDING_RESOLUTION', 'RESOLVED_UNKNOWN'
    )),
    CONSTRAINT ck_session_tool_attempt_execution_generation CHECK (execution_generation >= 0),
    CONSTRAINT ck_session_tool_attempt_execution_shape CHECK (
        (
            state IN ('INTENT_COMMITTED', 'WAITING_USER')
            AND execution_loop_id IS NULL
            AND execution_fence IS NULL
            AND execution_owner_instance_id IS NULL
            AND execution_generation = 0
            AND claim_request_id IS NULL
            AND claimed_at IS NULL
            AND execution_lease_until IS NULL
        )
        OR
        (
            state IN ('EXECUTING', 'RESULTS_COMMITTED',
                      'UNCERTAIN_PENDING_RESOLUTION', 'RESOLVED_UNKNOWN')
            AND execution_loop_id IS NOT NULL
            AND execution_fence IS NOT NULL AND execution_fence >= 0
            AND execution_owner_instance_id IS NOT NULL
            AND execution_generation > 0
            AND claim_request_id IS NOT NULL
            AND claimed_at IS NOT NULL
            AND execution_lease_until IS NOT NULL
        )
    ),
    CONSTRAINT ck_session_tool_attempt_result_shape CHECK (
        (
            state IN ('RESULTS_COMMITTED', 'RESOLVED_UNKNOWN')
            AND result_batch_id IS NOT NULL
            AND result_execution_generation IS NOT NULL AND result_execution_generation > 0
            AND result_execution_fence IS NOT NULL AND result_execution_fence >= 0
        )
        OR
        (
            state NOT IN ('RESULTS_COMMITTED', 'RESOLVED_UNKNOWN')
            AND result_batch_id IS NULL
            AND result_execution_generation IS NULL
            AND result_execution_fence IS NULL
        )
    ),
    CONSTRAINT ck_session_tool_attempt_archive_state CHECK (
        archive_preparation_state IN ('NOT_STARTED', 'PENDING', 'PREPARED', 'RAW_FALLBACK')
        AND archive_prepared_count >= 0
        AND archive_total_count >= 0
        AND archive_prepared_count <= archive_total_count
        AND (archive_preparation_state <> 'PREPARED'
             OR archive_prepared_count = archive_total_count)
        AND (state IN ('RESULTS_COMMITTED', 'RESOLVED_UNKNOWN')
             OR (archive_preparation_state = 'NOT_STARTED'
                 AND archive_prepared_count = 0 AND archive_total_count = 0))
    ),
    CONSTRAINT ck_session_tool_attempt_post_action_state CHECK (
        post_action_state IN ('NONE', 'PENDING', 'CLAIMED', 'COMPLETED')
    ),
    CONSTRAINT ck_session_tool_attempt_post_action_shape CHECK (
        (
            post_action_state = 'NONE'
            AND post_action_resolution_request_id IS NULL
            AND post_action_result_batch_id IS NULL
            AND post_action_kind IS NULL
            AND post_action_claim_request_id IS NULL
            AND post_action_loop_id IS NULL
            AND post_action_fence IS NULL
            AND NOT post_action_inbox_handoff_accepted
        )
        OR
        (
            state = 'RESOLVED_UNKNOWN'
            AND post_action_state = 'PENDING'
            AND post_action_resolution_request_id IS NOT NULL
            AND post_action_result_batch_id = result_batch_id
            AND post_action_kind = 'CONTINUE_CURRENT_TIMELINE'
            AND post_action_claim_request_id IS NULL
            AND post_action_loop_id IS NULL
            AND post_action_fence IS NULL
            AND NOT post_action_inbox_handoff_accepted
        )
        OR
        (
            state = 'RESOLVED_UNKNOWN'
            AND post_action_state = 'CLAIMED'
            AND post_action_resolution_request_id IS NOT NULL
            AND post_action_result_batch_id = result_batch_id
            AND post_action_kind = 'CONTINUE_CURRENT_TIMELINE'
            AND post_action_claim_request_id IS NOT NULL
            AND post_action_loop_id IS NOT NULL
            AND post_action_fence IS NOT NULL AND post_action_fence >= 0
        )
        OR
        (
            state = 'RESOLVED_UNKNOWN'
            AND post_action_state = 'COMPLETED'
            AND post_action_resolution_request_id IS NOT NULL
            AND post_action_result_batch_id = result_batch_id
            AND post_action_kind = 'CONTINUE_CURRENT_TIMELINE'
            AND post_action_claim_request_id IS NOT NULL
            AND post_action_loop_id IS NOT NULL
            AND post_action_fence IS NOT NULL AND post_action_fence >= 0
            AND post_action_inbox_handoff_accepted
        )
    )
);

CREATE INDEX idx_session_tool_attempt_session_state
    ON t_session_tool_attempt (session_id, state);
CREATE INDEX idx_session_tool_attempt_recovery_lease
    ON t_session_tool_attempt (state, execution_lease_until);
CREATE UNIQUE INDEX uq_session_tool_attempt_one_unresolved
    ON t_session_tool_attempt (session_id)
    WHERE state IN (
        'INTENT_COMMITTED', 'EXECUTING', 'WAITING_USER', 'UNCERTAIN_PENDING_RESOLUTION'
    );
CREATE UNIQUE INDEX uq_session_tool_attempt_post_action_resolution
    ON t_session_tool_attempt (post_action_resolution_request_id)
    WHERE post_action_resolution_request_id IS NOT NULL;

CREATE TABLE t_session_tool_attempt_resolution_audit (
    id                        BIGSERIAL    NOT NULL PRIMARY KEY,
    resolution_request_id     UUID         NOT NULL,
    session_id                VARCHAR(36)  NOT NULL,
    attempt_id                BIGINT       NOT NULL,
    step_id                   UUID         NOT NULL,
    history_epoch             BIGINT       NOT NULL,
    execution_generation      BIGINT       NOT NULL,
    execution_fence           BIGINT       NOT NULL,
    actor_id                  BIGINT       NOT NULL,
    actor_authority           VARCHAR(24)  NOT NULL,
    reason_hash               CHAR(64)     NOT NULL,
    action                    VARCHAR(40)  NOT NULL,
    inbox_dispositions_json   TEXT         NOT NULL,
    result_batch_id           UUID         NOT NULL,
    outcome_state             VARCHAR(32)  NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_session_tool_attempt_resolution_request UNIQUE (resolution_request_id),
    CONSTRAINT fk_session_tool_attempt_audit_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE,
    CONSTRAINT ck_session_tool_attempt_audit_epoch_generation CHECK (
        history_epoch >= 0 AND execution_generation > 0 AND execution_fence >= 0
    ),
    CONSTRAINT ck_session_tool_attempt_audit_actor CHECK (
        actor_authority IN ('OWNER', 'ADMIN')
    ),
    CONSTRAINT ck_session_tool_attempt_audit_reason_hash CHECK (
        reason_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_session_tool_attempt_audit_action CHECK (action IN (
        'CONTINUE_CURRENT_TIMELINE', 'PREPARE_RESTORE', 'PREPARE_RESTORE_INBOX'
    )),
    CONSTRAINT ck_session_tool_attempt_audit_inbox_dispositions CHECK (
        length(inbox_dispositions_json) > 0
    ),
    CONSTRAINT ck_session_tool_attempt_audit_outcome CHECK (
        outcome_state = 'RESOLVED_UNKNOWN'
    )
);

CREATE INDEX idx_session_tool_attempt_audit_session_created
    ON t_session_tool_attempt_resolution_audit (session_id, created_at);
CREATE INDEX idx_session_tool_attempt_audit_attempt_created
    ON t_session_tool_attempt_resolution_audit (attempt_id, created_at);

-- Append-only acknowledgement receipts for synchronous user cancellation.
-- Receipts contain execution identity only; no transcript, Tool input/result,
-- or user-provided text is stored here.
CREATE TABLE t_session_cancel_receipt (
    request_id                 UUID         NOT NULL PRIMARY KEY,
    session_id                 VARCHAR(36)  NOT NULL,
    user_id                    BIGINT       NOT NULL,
    history_epoch              BIGINT       NOT NULL,
    target_loop_id             VARCHAR(36)  NOT NULL,
    target_loop_fence          BIGINT       NOT NULL,
    target_owner_instance_id   VARCHAR(128) NOT NULL,
    attempt_id                 BIGINT,
    execution_generation       BIGINT,
    outcome                    VARCHAR(48)  NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_session_cancel_receipt_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE,
    CONSTRAINT uq_session_cancel_receipt_target UNIQUE (
        session_id, history_epoch, target_loop_id, target_loop_fence,
        target_owner_instance_id
    ),
    CONSTRAINT ck_session_cancel_receipt_epoch_fence CHECK (
        history_epoch >= 0 AND target_loop_fence > 0
    ),
    CONSTRAINT ck_session_cancel_receipt_target CHECK (
        length(target_loop_id) > 0 AND length(target_owner_instance_id) > 0
    ),
    CONSTRAINT ck_session_cancel_receipt_attempt_generation CHECK (
        (attempt_id IS NULL AND execution_generation IS NULL)
        OR (attempt_id > 0 AND execution_generation > 0)
    ),
    CONSTRAINT ck_session_cancel_receipt_outcome CHECK (outcome IN (
        'CANCELLED_BEFORE_EXECUTION', 'CANCELLED_AFTER_RESULTS_COMMITTED',
        'EXECUTION_OUTCOME_UNCERTAIN'
    )),
    CONSTRAINT ck_session_cancel_receipt_uncertain_attempt CHECK (
        outcome <> 'EXECUTION_OUTCOME_UNCERTAIN'
        OR (attempt_id IS NOT NULL AND execution_generation IS NOT NULL)
    )
);

CREATE INDEX idx_session_cancel_receipt_session_created
    ON t_session_cancel_receipt (session_id, created_at, request_id);

-- Give the runtime role ordinary application DML, then make the audit table the
-- deliberate exception. Table ownership remains with the Flyway/migrator role.
GRANT USAGE ON SCHEMA public TO skillforge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO skillforge_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO skillforge_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO skillforge_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO skillforge_app;

REVOKE ALL ON flyway_schema_history FROM skillforge_app;
REVOKE ALL ON t_session_tool_attempt_resolution_audit FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE
    ON t_session_tool_attempt_resolution_audit FROM skillforge_app;
GRANT SELECT, INSERT
    ON t_session_tool_attempt_resolution_audit TO skillforge_app;
REVOKE ALL ON SEQUENCE t_session_tool_attempt_resolution_audit_id_seq FROM PUBLIC;
GRANT USAGE, SELECT
    ON SEQUENCE t_session_tool_attempt_resolution_audit_id_seq TO skillforge_app;

-- Cancellation receipts are protocol acknowledgements, so runtime callers may
-- insert/read them but never rewrite them. Whole-Session FK cascade remains the
-- only deletion path.
REVOKE ALL ON t_session_cancel_receipt FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON t_session_cancel_receipt FROM skillforge_app;
GRANT SELECT, INSERT ON t_session_cancel_receipt TO skillforge_app;

COMMENT ON TABLE t_session_tool_attempt_resolution_audit IS
    'Append-only unknown-outcome acknowledgement; only whole-Session FK cascade may remove rows';
COMMENT ON COLUMN t_session_tool_attempt_resolution_audit.attempt_id IS
    'Scalar attempt identity without FK so attempt pruning and restore preserve the audit';
COMMENT ON TABLE t_session_cancel_receipt IS
    'Append-only durable user-cancellation acknowledgement; contains identity only';
