CREATE TABLE t_session_task (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    subject VARCHAR(256) NOT NULL,
    description TEXT NOT NULL,
    active_form VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    owner VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_session_task_session FOREIGN KEY (session_id)
        REFERENCES t_session(id) ON DELETE CASCADE,
    CONSTRAINT uq_session_task_id_session UNIQUE (id, session_id),
    CONSTRAINT chk_session_task_status CHECK (
        status IN ('pending', 'in_progress', 'completed', 'deleted')),
    CONSTRAINT chk_session_task_subject CHECK (BTRIM(subject) <> ''),
    CONSTRAINT chk_session_task_description CHECK (BTRIM(description) <> ''),
    CONSTRAINT chk_session_task_active_form CHECK (BTRIM(active_form) <> '')
);

CREATE INDEX idx_session_task_session_status_created
    ON t_session_task(session_id, status, created_at, id);

CREATE UNIQUE INDEX uq_session_task_one_in_progress_owner
    ON t_session_task(session_id, COALESCE(owner, ''))
    WHERE status = 'in_progress';

CREATE TABLE t_session_task_dependency (
    session_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    blocked_by_task_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (task_id, blocked_by_task_id),
    CONSTRAINT chk_session_task_dependency_not_self CHECK (task_id <> blocked_by_task_id),
    CONSTRAINT fk_session_task_dependency_task FOREIGN KEY (task_id, session_id)
        REFERENCES t_session_task(id, session_id) ON DELETE CASCADE,
    CONSTRAINT fk_session_task_dependency_blocker FOREIGN KEY (blocked_by_task_id, session_id)
        REFERENCES t_session_task(id, session_id) ON DELETE CASCADE
);

CREATE INDEX idx_session_task_dependency_blocker
    ON t_session_task_dependency(session_id, blocked_by_task_id);

-- Add Task lifecycle guidance only to the exact V188 Main Assistant default.
-- User-authored prompts are intentionally left untouched.
UPDATE t_agent
SET system_prompt = '你是 SkillForge 的 Main Assistant，负责理解用户目标并协调完成当前 Session 的任务。

## 核心职责
1. 明确目标和成功标准，必要时拆分为可验证的步骤。
2. 自己完成一般任务；只有子任务边界清晰且确有并行或专业收益时才委派。
3. 整合工具与子 Agent 的结果，向用户给出结论、验证证据和剩余风险。
4. 需要历史信息时按需使用 Memory 检索，不把短期会话摘要当作长期事实。

## 决策原则
- 用户要求分析或评估时，先报告判断，不擅自修改。
- 信息足够就推进；存在会显著改变结果的歧义时再确认。
- 遵循用户最新指令；已确认且未被后续反馈改变的决定不重复推导。需要权衡时给出明确推荐。
- 以当前 Session 的目标为中心，不重复平台级规则或工具手册。

## Task 行为矩阵
- 普通追问、解释或状态询问：直接回答，不为了记录而修改 Task。
- 修改当前交付物、范围或验收标准：增量更新对应 Task，保留仍然有效的既有信息。
- 新增独立功能或新交付物：创建新的 pending Task，不覆盖当前 Task。
- 用户明确改变优先级：将新目标设为 in_progress；平台会把同一 owner 的旧 in_progress 自动退回 pending。
- 用户拒绝、指出缺陷或说明交付不满足要求：把对应 completed Task 重新设为 pending，再继续修复。
- Task 已完成后用户提出新的范围：新建 Task，不篡改已完成事项的审计状态。
- 用户说继续：优先继续当前 in_progress；没有当前任务时，从未阻塞的 pending Task 中选择下一项。
- 用户要求暂停、停止或暂缓：停止执行并保留真实状态，不把未完成 Task 伪装成 completed。
- 最终回复必须围绕用户最新目标和验收标准，不得把最后一个 Tool error 当成任务结论。',
    updated_at = NOW()
WHERE id = 3
  AND name = 'Main Assistant'
  AND system_prompt = '你是 SkillForge 的 Main Assistant，负责理解用户目标并协调完成当前 Session 的任务。

## 核心职责
1. 明确目标和成功标准，必要时拆分为可验证的步骤。
2. 自己完成一般任务；只有子任务边界清晰且确有并行或专业收益时才委派。
3. 整合工具与子 Agent 的结果，向用户给出结论、验证证据和剩余风险。
4. 需要历史信息时按需使用 Memory 检索，不把短期会话摘要当作长期事实。

## 决策原则
- 用户要求分析或评估时，先报告判断，不擅自修改。
- 信息足够就推进；存在会显著改变结果的歧义时再确认。
- 遵循用户最新指令；已确认且未被后续反馈改变的决定不重复推导。需要权衡时给出明确推荐。
- 以当前 Session 的目标为中心，不重复平台级规则或工具手册。';

WITH replacement AS (
    SELECT id,
           (((((tool_ids::jsonb - 'TodoWrite')
               || CASE WHEN tool_ids::jsonb ? 'TaskCreate' THEN '[]'::jsonb ELSE '["TaskCreate"]'::jsonb END)
               || CASE WHEN tool_ids::jsonb ? 'TaskUpdate' THEN '[]'::jsonb ELSE '["TaskUpdate"]'::jsonb END)
               || CASE WHEN tool_ids::jsonb ? 'TaskGet' THEN '[]'::jsonb ELSE '["TaskGet"]'::jsonb END)
               || CASE WHEN tool_ids::jsonb ? 'TaskList' THEN '[]'::jsonb ELSE '["TaskList"]'::jsonb END) AS next_tool_ids
    FROM t_agent
    WHERE id = 3
      AND name = 'Main Assistant'
      AND NULLIF(BTRIM(tool_ids), '') IS NOT NULL
      AND jsonb_typeof(tool_ids::jsonb) = 'array'
      AND tool_ids::jsonb ? 'TodoWrite'
)
UPDATE t_agent a
SET tool_ids = replacement.next_tool_ids::text,
    config = jsonb_set(
        COALESCE(NULLIF(a.config, '')::jsonb, '{}'::jsonb),
        '{tool_ids}', replacement.next_tool_ids, true)::text,
    updated_at = NOW()
FROM replacement
WHERE a.id = replacement.id;
