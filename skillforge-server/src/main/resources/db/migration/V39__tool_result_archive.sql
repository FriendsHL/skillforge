-- V39__tool_result_archive.sql — P9-2 持久化归档：单消息 tool_result 聚合超预算时归档原文，
-- 上下文中只保留 2KB preview + archive_id，避免长任务被巨型工具输出挤爆 LLM context。
--
-- 不变量：归档决策对同一 (session_id, tool_use_id) 在整个 session 生命周期内幂等不翻转。
-- DB UNIQUE 约束 + service 层 lookup-then-insert 共同兜底，避免并发归档同一 tool_use_id。

CREATE TABLE t_tool_result_archive (
    id                    BIGSERIAL    NOT NULL PRIMARY KEY,
    archive_id            VARCHAR(36)  NOT NULL,
    session_id            VARCHAR(36)  NOT NULL,
    session_message_id    BIGINT,
    tool_use_id           VARCHAR(255) NOT NULL,
    tool_name             VARCHAR(128),
    original_chars        INTEGER      NOT NULL,
    preview               TEXT,
    content               TEXT         NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_tool_result_archive_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE,
    CONSTRAINT uq_tool_result_archive_archive_id UNIQUE (archive_id),
    CONSTRAINT uq_tool_result_archive_session_tooluse UNIQUE (session_id, tool_use_id)
);

CREATE INDEX idx_tool_result_archive_session_created
    ON t_tool_result_archive (session_id, created_at);
