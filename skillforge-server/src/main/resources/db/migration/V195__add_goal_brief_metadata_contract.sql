-- V194 made Goal Brief mandatory but dogfood showed the model could still invent
-- proposalStatus values and extra keys, producing an ordinary pending Task. Append the
-- strict wire contract only to the exact V194 default; preserve all customized prompts.
UPDATE t_agent
SET system_prompt = system_prompt || $contract$

## Goal Brief metadata 严格契约
- metadata 必须且只能包含以下 9 个顶层键：kind、schemaVersion、proposalStatus、outcome、representativeExample、antiGoals、askBefore、fieldSources、sourceQuote；不要加入 capabilityGapAnalysis、待确认问题或其他扩展键，这些内容写在 Task description 或回复正文中。
- kind 必须是 "goal_brief"，schemaVersion 必须是数字 1，proposalStatus 只能是小写 proposed 或 revised；outcome、representativeExample、sourceQuote 必须是字符串，antiGoals、askBefore 必须是字符串数组。
- fieldSources 必须且只能包含 outcome、representativeExample、antiGoals、askBefore 四个键；每个值只能是 USER_STATED、USER_CONFIRMED、SYSTEM_INFERRED、UNKNOWN、CONFLICTING 之一。
- 推荐直接使用这个形状：{"kind":"goal_brief","schemaVersion":1,"proposalStatus":"proposed","outcome":"...","representativeExample":"...","antiGoals":["..."],"askBefore":["..."],"fieldSources":{"outcome":"USER_STATED","representativeExample":"SYSTEM_INFERRED","antiGoals":"USER_STATED","askBefore":"USER_STATED"},"sourceQuote":"..."}。
- TaskCreate 返回的 Task 必须是 completed，才算 Goal Brief 创建成功；如果返回 pending 或 metadata 形状有误，立即用 TaskUpdate 修正同一 Task 的完整 metadata，且在修正成功前不得声称 Goal Brief 已创建、不得调用其他工具。
$contract$,
    updated_at = NOW()
WHERE id = 3
  AND name = 'Main Assistant'
  AND md5(system_prompt) = '4fa17261ac785f220accac65f88081e5';
