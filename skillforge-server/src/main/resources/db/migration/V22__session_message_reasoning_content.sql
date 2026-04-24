-- V22__session_message_reasoning_content.sql
-- 持久化 OpenAI 兼容 provider（DashScope/Qwen 等）thinking 模式的 reasoning_content；
-- 下一轮请求必须原样回传，否则 API 返回 HTTP 400。跨 turn / 重启 / compaction rewrite 后
-- in-memory 字段会丢，因此必须落库。

ALTER TABLE t_session_message
    ADD COLUMN reasoning_content TEXT NULL;
