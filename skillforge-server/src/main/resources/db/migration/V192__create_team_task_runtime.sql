CREATE TABLE t_session_task_attempt (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    graph_session_id VARCHAR(36) NOT NULL,
    collab_run_id VARCHAR(36) NOT NULL,
    attempt_no INTEGER NOT NULL,
    worker_session_id VARCHAR(36) NOT NULL,
    worker_agent_id BIGINT NOT NULL,
    lease_token VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    leased_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    reason_code VARCHAR(64),
    CONSTRAINT fk_task_attempt_task FOREIGN KEY (task_id, graph_session_id)
        REFERENCES t_session_task(id, session_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_attempt_collab FOREIGN KEY (collab_run_id)
        REFERENCES t_collab_run(collab_run_id),
    CONSTRAINT uq_task_attempt_number UNIQUE (task_id, attempt_no),
    CONSTRAINT uq_task_attempt_lease_token UNIQUE (lease_token),
    CONSTRAINT chk_task_attempt_number CHECK (attempt_no > 0),
    CONSTRAINT chk_task_attempt_status CHECK (
        status IN ('ACTIVE', 'COMPLETED', 'FAILED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT chk_task_attempt_expiry CHECK (expires_at >= heartbeat_at)
);

CREATE UNIQUE INDEX uq_task_attempt_one_active
    ON t_session_task_attempt(task_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_task_attempt_active_expiry
    ON t_session_task_attempt(expires_at)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_task_attempt_worker_status
    ON t_session_task_attempt(worker_session_id, status);

CREATE TABLE t_session_task_event (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    graph_session_id VARCHAR(36) NOT NULL,
    collab_run_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36),
    event_type VARCHAR(32) NOT NULL,
    actor_session_id VARCHAR(36),
    actor_agent_id BIGINT,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    reason_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_task_event_task FOREIGN KEY (task_id, graph_session_id)
        REFERENCES t_session_task(id, session_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_event_collab FOREIGN KEY (collab_run_id)
        REFERENCES t_collab_run(collab_run_id),
    CONSTRAINT fk_task_event_attempt FOREIGN KEY (attempt_id)
        REFERENCES t_session_task_attempt(id) ON DELETE SET NULL,
    CONSTRAINT chk_task_event_type CHECK (
        event_type IN ('TASK_CREATED', 'TASK_CLAIMED', 'TASK_UPDATED',
                       'TASK_BLOCKED', 'TASK_UNBLOCKED', 'TASK_COMPLETED',
                       'TASK_FAILED', 'TASK_RELEASED'))
);

CREATE INDEX idx_task_event_task_created
    ON t_session_task_event(task_id, created_at, id);

CREATE INDEX idx_task_event_graph_created
    ON t_session_task_event(graph_session_id, created_at, id);
