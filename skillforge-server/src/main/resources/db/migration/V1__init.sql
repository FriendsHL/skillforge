-- Flyway migration V1: initial schema for SkillForge

-- AgentEntity
CREATE TABLE t_agent (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    model_id TEXT,
    system_prompt TEXT,
    skill_ids TEXT,
    tool_ids TEXT,
    config TEXT,
    soul_prompt TEXT,
    tools_prompt TEXT,
    owner_id BIGINT,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT DEFAULT 'active',
    max_loops INTEGER,
    execution_mode VARCHAR(16) DEFAULT 'ask',
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- SessionEntity
CREATE TABLE t_session (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    title TEXT,
    status TEXT DEFAULT 'active',
    message_count INTEGER NOT NULL DEFAULT 0,
    total_input_tokens BIGINT NOT NULL DEFAULT 0,
    total_output_tokens BIGINT NOT NULL DEFAULT 0,
    messages_json TEXT,
    runtime_status VARCHAR(32) DEFAULT 'idle',
    runtime_step VARCHAR(256),
    runtime_error TEXT,
    execution_mode VARCHAR(16) DEFAULT 'ask',
    smart_titled BOOLEAN NOT NULL DEFAULT FALSE,
    parent_session_id VARCHAR(36),
    depth INTEGER NOT NULL DEFAULT 0,
    sub_agent_run_id VARCHAR(36),
    collab_run_id VARCHAR(36),
    max_loops INTEGER,
    light_context BOOLEAN NOT NULL DEFAULT FALSE,
    light_compact_count INTEGER NOT NULL DEFAULT 0,
    full_compact_count INTEGER NOT NULL DEFAULT 0,
    last_compacted_at TIMESTAMPTZ,
    last_compacted_at_message_count INTEGER NOT NULL DEFAULT 0,
    total_tokens_reclaimed BIGINT NOT NULL DEFAULT 0,
    last_user_message_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    digest_extracted_at TIMESTAMPTZ,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- SkillEntity
CREATE TABLE t_skill (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    skill_path TEXT,
    triggers TEXT,
    required_tools TEXT,
    owner_id BIGINT,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    source VARCHAR(32),
    version VARCHAR(64),
    risk_level VARCHAR(16),
    scan_report TEXT,
    created_at TIMESTAMP
);

-- MemoryEntity
CREATE TABLE t_memory (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    type TEXT,
    title TEXT,
    content TEXT,
    tags TEXT,
    recall_count INTEGER NOT NULL DEFAULT 0,
    last_recalled_at TIMESTAMPTZ,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- UserConfigEntity
CREATE TABLE t_user_config (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    claude_md TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- ModelUsageEntity
CREATE TABLE t_model_usage (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT,
    session_id TEXT,
    model_id TEXT,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    tool_calls TEXT,
    created_at TIMESTAMP
);

-- ActivityLogEntity
CREATE TABLE t_activity_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64),
    tool_name VARCHAR(64) NOT NULL,
    input_summary TEXT,
    output_summary TEXT,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP
);
CREATE INDEX idx_activity_log_user_session ON t_activity_log (user_id, session_id);
CREATE INDEX idx_activity_log_user_created ON t_activity_log (user_id, created_at);

-- Indexes for high-frequency query paths identified during review
CREATE INDEX idx_session_user ON t_session (user_id);
CREATE INDEX idx_memory_user ON t_memory (user_id);
CREATE INDEX idx_model_usage_user_created ON t_model_usage (user_id, created_at);

-- TraceSpanEntity
CREATE TABLE t_trace_span (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    parent_span_id VARCHAR(36),
    span_type VARCHAR(16) NOT NULL,
    name VARCHAR(256),
    input TEXT,
    output TEXT,
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    iteration_index INTEGER NOT NULL DEFAULT 0,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    model_id VARCHAR(128),
    success BOOLEAN NOT NULL DEFAULT FALSE,
    error TEXT,
    tool_use_id VARCHAR(64)
);
CREATE INDEX idx_trace_span_session ON t_trace_span (session_id);
CREATE INDEX idx_trace_span_parent ON t_trace_span (parent_span_id);
CREATE INDEX idx_trace_span_start ON t_trace_span (start_time);

-- CompactionEventEntity
CREATE TABLE t_compaction_event (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    level VARCHAR(8) NOT NULL,
    source VARCHAR(16) NOT NULL,
    reason TEXT,
    triggered_at TIMESTAMPTZ NOT NULL,
    before_tokens INTEGER NOT NULL,
    after_tokens INTEGER NOT NULL,
    tokens_reclaimed INTEGER NOT NULL,
    before_message_count INTEGER NOT NULL,
    after_message_count INTEGER NOT NULL,
    strategies_applied VARCHAR(256),
    llm_call_id VARCHAR(36)
);
CREATE INDEX idx_compaction_event_session ON t_compaction_event (session_id);
CREATE INDEX idx_compaction_event_triggered ON t_compaction_event (triggered_at);

-- SubAgentRunEntity
CREATE TABLE t_subagent_run (
    run_id VARCHAR(36) PRIMARY KEY,
    parent_session_id VARCHAR(36),
    child_session_id VARCHAR(36),
    child_agent_id BIGINT,
    child_agent_name VARCHAR(128),
    task TEXT,
    status VARCHAR(16),
    final_message TEXT,
    spawned_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_subagent_run_parent ON t_subagent_run (parent_session_id);

-- SubAgentPendingResultEntity
CREATE TABLE t_subagent_pending_result (
    id BIGSERIAL PRIMARY KEY,
    parent_session_id VARCHAR(36) NOT NULL,
    payload TEXT NOT NULL,
    seq_no BIGINT,
    message_id VARCHAR(36),
    target_session_id VARCHAR(36),
    retry_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16),
    created_at TIMESTAMPTZ
);
CREATE INDEX idx_subagent_pending_parent ON t_subagent_pending_result (parent_session_id);

-- CollabRunEntity
CREATE TABLE t_collab_run (
    collab_run_id VARCHAR(36) PRIMARY KEY,
    leader_session_id VARCHAR(36),
    status VARCHAR(16),
    max_depth INTEGER NOT NULL DEFAULT 2,
    max_total_agents INTEGER NOT NULL DEFAULT 20,
    created_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

-- Note: sequences are reset dynamically by H2DataMigrationRunner after data load,
-- using GREATEST(1000, MAX(id)+1) to avoid PK collisions with migrated data.
