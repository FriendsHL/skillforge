# GOAL-BRIEF-P0A 技术方案

---
id: GOAL-BRIEF-P0A
status: implemented
risk: Full
created: 2026-08-26
updated: 2026-08-30
---

## TL;DR

复用 `SessionTaskEntity.metadata` 携带 `goal_brief` 提议；后端把 metadata 加入现有 Task snapshot/WS DTO，并将 Goal Brief Task 确定性保存为 completed、从普通进度统计排除。前端严格解析并只展示最新 GoalBriefCard。按钮调用 Chat 现有 send 路径发送普通用户消息。使用 V193–V195 逐步更新精确匹配的 Main Assistant 默认 Prompt，不建表、不改 Agent Loop、ChatService、Message 或 Eval。

## 数据载体

不修改 schema。Task metadata 示例：

```json
{
  "kind": "goal_brief",
  "schemaVersion": 1,
  "proposalStatus": "proposed",
  "outcome": "把完整需求实现、验证并发布",
  "representativeExample": "从需求澄清到可访问的线上版本",
  "antiGoals": ["未经确认不发布", "不泄露凭据"],
  "askBefore": ["新增外部服务", "执行发布"],
  "fieldSources": {
    "outcome": "USER_STATED",
    "representativeExample": "SYSTEM_INFERRED",
    "antiGoals": "USER_STATED",
    "askBefore": "SYSTEM_INFERRED"
  },
  "sourceQuote": "把一个完整需求做到上线发布"
}
```

约束：

- 复用现有 16 KB metadata 限制。
- UI 将 metadata 视为不可信 `unknown`，严格校验版本、字符串长度、数组数量和枚举。
- 不读取 metadata 中的 HTML，不使用 `dangerouslySetInnerHTML`。
- TaskUpdate 可以修改提议，但不能产生服务端授权；因此模型可写 metadata 不构成提权。
- 原始用户消息仍在 Session history；`sourceQuote` 只是帮助用户复核的模型摘录，不是权威来源 ID。
- Compact/Message rewrite 不影响 Task row；Task snapshot/WS 继续承担恢复。
- `SessionTaskResponse` 增加只读 `metadata`，`SessionTaskService.response()` 返回 defensive copy；HTTP snapshot 与 Task WS 使用同一 DTO。
- `SessionTaskService.create()` 检测顶层 `kind=goal_brief` 后强制初始 status 为 completed；summary 排除 Goal Brief，普通 Task 图语义不变。
- Goal Brief 不存在 metadata 权威 approved 状态。目标修改时创建新记录，前端按任务顺序只展示最新一张。

## 前端

### 新增

- `components/chat/GoalBriefCard.tsx`
- `components/chat/__tests__/GoalBriefCard.test.tsx`

### 修改

- `api/sessionTasks.ts`：增加 `parseGoalBrief(metadata: unknown)` 和强类型。
- `SessionTaskProgress.tsx`：识别有效 Goal Brief；无效时走现有普通 Task 渲染；最新卡按 `createdAt`、`taskId` 稳定排序。
- `Chat.tsx`：传入 `onGoalBriefAction(message)`，复用现有发送函数。

Parser 上限：outcome/example/sourceQuote 各 2,000 字符；antiGoals/askBefore 各最多 10 项、每项 500 字符；只读取一层已知键，总展示字符不超过 8,000。

交互消息：

```text
确认并继续：`我确认你对目标的理解；这不批准任何需要单独确认的权限、外部代码执行或发布操作，请按这个目标继续。`
需要修改：`我需要修改你对目标的理解；这不批准任何高影响操作。请先询问我需要调整的内容。`
先做一次：`先只完成当前这一次，不要把它当作长期目标或创建常驻能力；这不批准任何需要单独确认的高影响操作。`
```

三个动作都发送精确普通用户消息；不引入 ChatWindow prefill contract，也不覆盖用户已有草稿。发送失败保持卡片和现有错误行为；GoalBriefCard 使用本地同步锁并消费现有 runtime disabled 状态，快速双击只能发送一次。

## Main Assistant Prompt

V193 仅在 Main Assistant 当前 Prompt 精确匹配 V189 版本时追加初始 Goal Brief 行为。首次 dogfood 暴露“提出”可能被理解为可选后，V194 只对完整内容精确匹配 V193 默认值的 Prompt 强化等待语义。第二次 dogfood 暴露模型会发明 `DRAFT` 和扩展键后，V195 只对精确 V194 默认值追加 metadata 严格契约和 completed 自检；三次迁移都不覆盖用户自定义 Prompt：

- 仅在 PRD 触发条件成立时使用。
- 命中触发条件时必须创建；创建或修订后结束当前回复，等待用户选择或自由文本反馈。
- 等待反馈期间不继续搜索、安装、导入或启用新能力，不创建、更新或调度常驻 Agent。
- 最多追问 1–2 个具体问题。
- 使用 TaskCreate/TaskUpdate metadata 生成 Goal Brief。
- Goal Brief 是提议，不代表用户授权。
- Goal Brief 按钮消息不能满足 CreateAgent、外部代码、权限、发布或其他现有 confirmation gate。
- 普通任务直接执行。
- 用户最新消息覆盖 metadata。

## 测试计划

### RED/GREEN

- parser：完整、缺字段、错误版本、错误枚举、超长、恶意对象。
- GoalBriefCard：四项、来源标签、三个操作、无 HTML 执行。
- 后端 DTO/mapper：metadata defensive copy；HTTP/WS production shape；Goal Brief 自动 completed、summary 排除；普通 Task 回归。
- SessionTaskProgress：只显示最新 Goal Brief、Goal Brief 不进入普通列表、普通 Task 回归。
- Chat：三个动作进入现有 send；发送期间沿用现有禁用规则。

### Backend/Migration

- V193 migration IT 验证初始默认 Prompt 更新、自定义 Prompt 不变、行为只追加一次。
- V194 migration IT 验证精确 V193 默认值被替换、自定义 Prompt 不变、旧措辞被移除且重复执行不产生重复段落。
- V195 migration IT 验证精确 V194 默认值追加严格 metadata 契约、自定义 Prompt 不变、重复执行不产生重复段落。
- 修改 `SessionTaskResponse` 与 `SessionTaskService`；不新增 Entity/Repository/Controller。
- 现有 SessionTaskService/Controller/WS 测试保持通过。

### Final

- `mvn -pl skillforge-server -am test`
- Dashboard `npx tsc --noEmit`、focused Vitest、`npm run build`
- 浏览器 DOM 断言：普通任务无卡；两类长任务有卡；刷新恢复；三个动作正确。

## 风险

| 风险 | 缓解 |
| --- | --- |
| 模型过度创建 Goal Brief | Prompt 负例 + dogfood；不在 P0a 增加自动 classifier |
| metadata 被误认为授权 | UI/Prompt 明确提议；所有高影响动作继续现有确认 Gate |
| TaskProgress 变复杂 | GoalBriefCard 独立组件，parser 独立纯函数 |
| V192 未提交 | V193/V194/V195 只新增迁移文件，不修改 V192；最终迁移测试包含 V192→V193→V194→V195 |
| Chat.tsx 是核心红灯 | 只接现有 send 回调和 disabled 状态，Full review + 浏览器验证 |
