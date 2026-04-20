-- V18__session_message_store.sql — P6 消息行存储 + compaction checkpoint

-- 1) 会话消息行存储（append-only）
CREATE TABLE t_session_message (
    id            BIGSERIAL    NOT NULL PRIMARY KEY,
    session_id    VARCHAR(36)  NOT NULL,
    seq_no        BIGINT       NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    msg_type      VARCHAR(32)  NOT NULL,
    content_json  TEXT,
    metadata_json TEXT,
    pruned_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_session_message_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE,
    CONSTRAINT uq_session_message_session_seq UNIQUE (session_id, seq_no)
);

CREATE INDEX idx_session_message_session_created
    ON t_session_message (session_id, created_at);

CREATE INDEX idx_session_message_session_type_seq
    ON t_session_message (session_id, msg_type, seq_no);

CREATE INDEX idx_session_message_session_pruned
    ON t_session_message (session_id, pruned_at)
    WHERE pruned_at IS NOT NULL;

-- 2) compaction checkpoint 元数据（为后续 branch/restore 预留）
CREATE TABLE t_session_compaction_checkpoint (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    session_id            VARCHAR(36)  NOT NULL,
    boundary_seq_no       BIGINT       NOT NULL,
    summary_seq_no        BIGINT,
    reason                VARCHAR(32)  NOT NULL,
    pre_range_start_seq_no BIGINT,
    pre_range_end_seq_no   BIGINT,
    post_range_start_seq_no BIGINT,
    post_range_end_seq_no   BIGINT,
    snapshot_ref          TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_scc_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE
);

CREATE INDEX idx_scc_session_created
    ON t_session_compaction_checkpoint (session_id, created_at);

CREATE INDEX idx_scc_session_boundary
    ON t_session_compaction_checkpoint (session_id, boundary_seq_no);
