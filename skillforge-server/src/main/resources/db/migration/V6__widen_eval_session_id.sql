-- Widen session_id column: "eval_" prefix + UUID = 41 chars, exceeds varchar(36)
ALTER TABLE t_eval_session ALTER COLUMN session_id TYPE VARCHAR(64);
