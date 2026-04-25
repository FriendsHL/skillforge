-- SEC-2: hooks proposed by agents and approved before dispatch.
CREATE TABLE t_agent_authored_hook (
    id                    BIGSERIAL PRIMARY KEY,
    target_agent_id       BIGINT NOT NULL,
    author_agent_id       BIGINT NOT NULL,
    author_session_id     VARCHAR(36),
    event                 VARCHAR(64) NOT NULL,
    method_kind           VARCHAR(32) NOT NULL,
    method_id             BIGINT,
    method_ref            VARCHAR(128) NOT NULL,
    method_version_hash   VARCHAR(128),
    args_json             TEXT,
    timeout_seconds       INT NOT NULL DEFAULT 30,
    failure_policy        VARCHAR(32) NOT NULL DEFAULT 'CONTINUE',
    async                 BOOLEAN NOT NULL DEFAULT FALSE,
    display_name          VARCHAR(256),
    description           TEXT,
    review_state          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    review_note           TEXT,
    reviewed_by_user_id   BIGINT,
    reviewed_at           TIMESTAMPTZ,
    parent_hook_id        BIGINT,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    usage_count           BIGINT NOT NULL DEFAULT 0,
    success_count         BIGINT NOT NULL DEFAULT 0,
    failure_count         BIGINT NOT NULL DEFAULT 0,
    last_executed_at      TIMESTAMPTZ,
    last_error            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_aah_review_state CHECK (review_state IN ('PENDING', 'APPROVED', 'REJECTED', 'RETIRED')),
    CONSTRAINT chk_aah_method_kind CHECK (method_kind IN ('COMPILED', 'BUILTIN')),
    CONSTRAINT chk_aah_failure_policy CHECK (failure_policy IN ('CONTINUE', 'ABORT', 'SKIP_CHAIN')),
    CONSTRAINT fk_aah_parent_hook FOREIGN KEY (parent_hook_id) REFERENCES t_agent_authored_hook(id)
);

CREATE INDEX idx_aah_target_event_dispatch
    ON t_agent_authored_hook(target_agent_id, event, review_state, enabled);

CREATE INDEX idx_aah_target_review
    ON t_agent_authored_hook(target_agent_id, review_state);

CREATE INDEX idx_aah_author
    ON t_agent_authored_hook(author_agent_id);

COMMENT ON TABLE t_agent_authored_hook IS
    'Agent-authored lifecycle hook binding proposals. Only APPROVED + enabled rows participate in dispatch.';
COMMENT ON COLUMN t_agent_authored_hook.method_version_hash IS
    'Immutable method target guard, e.g. SHA-256 of compiled method bytes. Display method_ref is not the trust anchor.';
