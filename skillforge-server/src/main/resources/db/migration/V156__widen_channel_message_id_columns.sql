-- WECHAT-CHANNEL: the weixin adapter encodes from_user_id + context_token into the
-- platformMessageId (so the reply pipeline, which drops rawFields, can echo context_token).
-- context_token has no documented max length, so the encoded id can exceed VARCHAR(256).
-- Widen the two channel id columns to VARCHAR(1024) to prevent INSERT overflow → silent
-- message drop. Widening a PK / unique-index varchar is a safe metadata-only change in PG.

ALTER TABLE t_channel_message_dedup
    ALTER COLUMN platform_message_id TYPE VARCHAR(1024);

ALTER TABLE t_channel_delivery
    ALTER COLUMN inbound_message_id TYPE VARCHAR(1024);
