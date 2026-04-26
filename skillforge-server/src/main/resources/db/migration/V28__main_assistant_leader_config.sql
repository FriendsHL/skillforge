-- V28: Backfill Main Assistant as the default leader / coordinator agent.
-- Keep updates conservative: only fill empty fields or the legacy default config.

UPDATE t_agent
SET role = 'leader'
WHERE name = 'Main Assistant'
  AND (role IS NULL OR role = '');

UPDATE t_agent
SET max_loops = 50
WHERE name = 'Main Assistant'
  AND max_loops IS NULL;

UPDATE t_agent
SET config = '{"temperature":0.7,"maxTokens":8192,"agentType":"main","coordinationMode":"leader"}'
WHERE name = 'Main Assistant'
  AND (
      config IS NULL
      OR config = ''
      OR config = '{"temperature":0.7,"maxTokens":4096}'
      OR config = '{"temperature": 0.7, "maxTokens": 4096}'
  );

UPDATE t_agent
SET behavior_rules = '{"builtinRuleIds":["confirm-destructive-ops","no-secret-in-output","sandbox-file-scope","no-force-push-main","validate-input","read-before-edit","minimal-change","prefer-edit-over-write","no-mock-in-prod","explain-before-act","test-after-change","ask-when-ambiguous","state-assumptions-explicit","simplicity-first-no-speculation","clean-only-own-orphans","goal-driven-verify-loop"],"customRules":[{"severity":"MUST","text":"你是主 Agent：负责澄清目标、拆解计划、选择是否委派、整合结果并给出最终回复。"},{"severity":"SHOULD","text":"当任务需要专业设计、代码实现、会话分析或可并行执行时，优先用 AgentDiscovery 查找合适 Agent，再用 SubAgent 或 TeamCreate 派发任务。"},{"severity":"SHOULD","text":"委派后持续跟踪子任务结果；最终回复必须合并关键发现、已执行动作、验证结果和剩余风险。"}]}'
WHERE name = 'Main Assistant'
  AND behavior_rules IS NULL;
