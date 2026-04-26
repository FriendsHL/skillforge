-- V27: Seed the default "Main Assistant" via Flyway.
-- Tool access remains unrestricted because tool_ids is NULL, matching the legacy initializer.

INSERT INTO t_agent (
    name,
    description,
    model_id,
    system_prompt,
    skill_ids,
    config,
    owner_id,
    is_public,
    status,
    execution_mode,
    created_at,
    updated_at
)
SELECT
    'Main Assistant',
    '默认通用助手 Agent,开箱即用,绑定全部内置工具。',
    'qwen3.5-plus',
    '你是 SkillForge 平台的默认通用助手 (Main Assistant),擅长理解需求、规划步骤、调用工具完成任务。

【最重要的规则 / 必读】
在调用任何工具之前,你必须先用一两句中文向用户说明:你打算做什么、为什么要这么做。
前端 UI 依赖你输出的 text 来向用户显示"思考过程",如果你直接调用工具不开口,用户会以为程序卡住了。

正确示范:
  我需要先看一下当前目录有哪些文件,我用 bash 跑个 ls。
  [tool_use: bash]

错误示范:
  [tool_use: bash]   ← 一句话不说就动手,禁止!

工作准则:
1. 先想清楚再动手。复杂任务先用 1-2 句话拆解步骤,再开始执行。
2. 调用工具前先开口,简短说明意图(见上)。
3. 工具结果回来后,简要复述关键信息再决定下一步。
4. 文件路径优先用绝对路径。修改文件前先 read。
5. 完成任务后给用户一个清晰的总结。

【最后再强调一遍】不要不说话直接调工具。先说,再做。',
    '["AgentDiscovery","Bash","CodeReview","CodeSandbox","CreateAgent","FileEdit","FileRead","FileWrite","GetAgentHooks","GetSessionMessages","GetTrace","Glob","Grep","Memory","ProposeHookBinding","RegisterCompiledMethod","RegisterScriptMethod","TeamCreate","TeamKill","TeamList","TeamSend","TodoWrite","WebFetch","WebSearch","memory_detail","memory_search"]',
    '{"temperature":0.7,"maxTokens":4096}',
    NULL,
    TRUE,
    'active',
    'ask',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'Main Assistant'
);
