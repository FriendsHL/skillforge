-- V17__channel_gateway.sql — P2 消息网关
-- 5 张表：平台配置、对话映射、幂等去重、用户身份、投递记录

-- 1. 平台配置
CREATE TABLE t_channel_config (
    id               BIGSERIAL     NOT NULL PRIMARY KEY,
    platform         VARCHAR(64)   NOT NULL,
    display_name     VARCHAR(128),
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    webhook_secret   TEXT          NOT NULL,
    credentials_json TEXT          NOT NULL,
    config_json      TEXT,
    default_agent_id BIGINT        NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_channel_config_platform UNIQUE (platform)
);

-- 2. 对话 → Session 映射
CREATE TABLE t_channel_conversation (
    id                BIGSERIAL    NOT NULL PRIMARY KEY,
    platform          VARCHAR(64)  NOT NULL,
    conversation_id   VARCHAR(256) NOT NULL,
    session_id        VARCHAR(36)  NOT NULL,
    channel_config_id BIGINT       NOT NULL
        REFERENCES t_channel_config(id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at         TIMESTAMPTZ
);
CREATE INDEX idx_ch_conv_platform_conv ON t_channel_conversation (platform, conversation_id);
CREATE INDEX idx_ch_conv_session_id    ON t_channel_conversation (session_id);
-- H4：部分唯一索引防并发重复建立活跃映射
CREATE UNIQUE INDEX uq_ch_conv_active
    ON t_channel_conversation (platform, conversation_id)
    WHERE closed_at IS NULL;

-- 3. 消息幂等去重（H3）
CREATE TABLE t_channel_message_dedup (
    platform_message_id VARCHAR(256) NOT NULL PRIMARY KEY,
    platform            VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ch_dedup_created ON t_channel_message_dedup (created_at);

-- 4. 平台用户身份映射
CREATE TABLE t_user_identity_mapping (
    id                    BIGSERIAL    NOT NULL PRIMARY KEY,
    platform              VARCHAR(64)  NOT NULL,
    platform_user_id      VARCHAR(256) NOT NULL,
    skillforge_user_id    BIGINT       NOT NULL,
    platform_display_name VARCHAR(256),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_identity_platform_user UNIQUE (platform, platform_user_id)
);
CREATE INDEX idx_identity_skillforge_user ON t_user_identity_mapping (skillforge_user_id);

-- 5. 投递记录（含 IN_FLIGHT 状态）
CREATE TABLE t_channel_delivery (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    platform           VARCHAR(64)  NOT NULL,
    conversation_id    VARCHAR(256) NOT NULL,
    inbound_message_id VARCHAR(256) NOT NULL,
    session_id         VARCHAR(36),
    reply_text         TEXT         NOT NULL,
    status             VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    retry_count        INT          NOT NULL DEFAULT 0,
    last_error         TEXT,
    scheduled_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ch_delivery_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'RETRY', 'DELIVERED', 'FAILED'))
);
CREATE INDEX idx_ch_delivery_status_sched ON t_channel_delivery (status, scheduled_at)
    WHERE status IN ('PENDING', 'RETRY');
-- 同一入站消息只有一条投递记录
CREATE UNIQUE INDEX uq_ch_delivery_inbound ON t_channel_delivery (inbound_message_id);
