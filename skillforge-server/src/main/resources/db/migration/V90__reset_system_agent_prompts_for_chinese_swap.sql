-- SYSTEM-AGENT-TYPING follow-up (2026-05-17 dogfood user 反馈):
-- session-annotator / metrics-collector / attribution-curator 3 个 system agent
-- 的 system_prompt 改成中文版 (跟 memory-curator V69 / user-simulator V85 中文风格统一).
--
-- 实施方式: 改 classpath:*-system-prompt.md file 为中文版 + 本 V90 migration 把
-- DB 里的 system_prompt 重置成 `SEE_FILE:*-system-prompt.md` 占位; BE 重启后
-- SessionAnnotatorBootstrap / MetricsCollectorBootstrap / AttributionCuratorBootstrap
-- 的 ApplicationReadyEvent listener 检测到 `SEE_FILE:` 前缀, 重新 swap 进 file
-- (现在是中文版).
--
-- Bootstrap idempotent guard: 若 operator 已 hand-edit (DB 里 prompt 不以
-- `SEE_FILE:` 开头), bootstrap 会 "leave alone" 不动. 本 V90 migration 强制
-- reset 占位, 是显式让 bootstrap re-swap.

UPDATE t_agent SET system_prompt = 'SEE_FILE:session-annotator-system-prompt.md'
WHERE name = 'session-annotator';

UPDATE t_agent SET system_prompt = 'SEE_FILE:metrics-collector-system-prompt.md'
WHERE name = 'metrics-collector';

UPDATE t_agent SET system_prompt = 'SEE_FILE:attribution-curator-system-prompt.md'
WHERE name = 'attribution-curator';
