-- V20: Backfill behavior_rules for Design Agent with cautious preset
-- cautious preset = rules tagged ["cautious", "full"] or ["autonomous", "cautious", "full"]

UPDATE t_agent
SET behavior_rules = '{"builtinRuleIds":["confirm-destructive-ops","no-secret-in-output","sandbox-file-scope","no-force-push-main","validate-input","read-before-edit","minimal-change","prefer-edit-over-write","no-mock-in-prod","explain-before-act","test-after-change","ask-when-ambiguous","state-assumptions-explicit","simplicity-first-no-speculation","clean-only-own-orphans","goal-driven-verify-loop"],"customRules":[]}'
WHERE name = 'Design Agent'
  AND behavior_rules IS NULL;
