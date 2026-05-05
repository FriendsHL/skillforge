-- EVAL-V2 M2: t_eval_scenario 加 conversation_turns —— 多轮对话 case 的 turns 数组
--
-- 语义：
--   • NULL = 单轮 case（兼容旧路径，走 task / oracleExpected）
--   • 非 NULL = JSON-encoded array of {role, content}，role ∈ {user|assistant|system|tool}
--   • assistant turns 的 content 是 "<placeholder>" 字面量；runtime 由 agent 实际响应替换（in-memory）
--   • 列保留 TEXT 而非 JSONB —— EvalScenarioEntity 使用 String 持有原始 JSON，序列化层负责 parse
--
-- 示例：
--   [
--     {"role": "user",      "content": "I'm getting NPE in OrderServiceTest.create."},
--     {"role": "assistant", "content": "<placeholder>"},
--     {"role": "user",      "content": "Yes I tried that."}
--   ]
--
-- 索引：暂不加。多轮 case 占少数且查询路径都先按 agentId / id 过滤，
-- 单独索引 conversation_turns IS NOT NULL 收益不明显。

ALTER TABLE t_eval_scenario
    ADD COLUMN IF NOT EXISTS conversation_turns TEXT NULL;

COMMENT ON COLUMN t_eval_scenario.conversation_turns IS
    'EVAL-V2 M2: JSON array of {role, content} multi-turn conversation. '
    'NULL = single-turn case (use task / oracleExpected). '
    'role: user|assistant|system|tool. content: turn text (assistant placeholder will be replaced at runtime).';
