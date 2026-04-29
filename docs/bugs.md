# SkillForge Bug Queue

> 更新于：2026-04-29
> 规则：这里独立维护体验类小 bug 和待修问题；不等同于 `todo.md` 当前执行队列。

## 使用边界

- `bugs.md` 是 bug 池，优先收集体验问题、可复现异常、展示错误和小的 follow-up。
- `todo.md` 只放已经排进当前执行队列的新需求、严重影响体验的问题、核心协议 / 数据 / 安全问题，或用户明确要求排期的 bug。
- P0、核心不变量、跨前后端协议、持久化、权限、安全问题，应该升级为需求包并在 `todo.md` 排期后实施。
- P1 / P2 体验 bug 默认留在本文件；修复时按实际风险决定 Solo、Lite 或 Full。
- 已修复 bug 只在本文件保留最近摘要；完整历史按修复日期拆分到 `docs/references/bug-history-yyyy-mm-dd.md`。

## 严重度

| 严重度 | 含义 | 默认处理 |
| --- | --- | --- |
| P0 | 阻断主流程、数据损坏、安全 / 权限风险、核心协议错误 | 进入 `todo.md`，建需求包，Full |
| P1 | 明显影响排查、协作、主要体验，但有绕过方式 | 留在 bug 池；需要排期时再进入 `todo.md` |
| P2 | 低优体验、文案、轻量交互、重复确认、可等待的优化 | 留在 bug 池，批量处理 |

## 开放中

| ID | 严重度 | 领域 | 状态 | 标题 | 现象 / 影响 | 下一步 |
| --- | --- | --- | --- | --- | --- | --- |
| BUG-25 | P1 | Traces / 检索 | open | Traces 页面检索接口不支持通过 sessionId 模糊查询 | 只能用完整 sessionId 或其它条件定位 trace；排查时通常只有 sessionId 前缀，导致 trace 检索效率很低 | 后端 trace 列表查询支持 sessionId 前缀 / contains 模糊匹配；前端搜索框提示支持 sessionId 前缀 |
| BUG-26 | P1 | Chat / Session | open | Chat 页面只展示 sessionId 前缀，复制后无法用于查询 session 的 Tool | Chat 页面多处只露出 sessionId 前几个字符；Agent 使用 `GetSessionMessages` 等查询 session 的 Tool 时需要完整 sessionId，前缀无法命中 | 前端提供完整 sessionId 的复制入口 / tooltip；或 Tool 支持同用户范围内唯一前缀解析，并在多命中时返回候选项 |
| BUG-27 | P2 | Agent 配置 / 确认流 | open | 同一 session 内连续更新 agent prompt 时需要重复用户确认 | 用户已在当前 session 确认过一次 agent prompt 更新，再次调整仍会触发确认，影响迭代效率 | 将确认授权按 session + agent + update 类型设置短期有效期，或支持一次确认覆盖同一轮 prompt-only 后续修改 |
| BUG-28 | P2 | 审核卡片 / UX | open | 审核卡片需要优化 | 当前审核卡片难以快速判断来源、变更内容、风险点和影响范围；确认 / 拒绝动作不够突出 | 增加来源信息、变更摘要 / diff、风险提示和更清晰的 Approve / Edit / Discard 操作；长内容默认折叠并支持展开 |
| BUG-29 | P1 | Chat / Team | open | Chat 页面 Team 运行时右侧 Agent 列表不可点击 | Team 运行过程中，右侧 Team 面板能展示当前有几个 agent，但具体 agent 项不能点击，无法快速进入对应子 agent / subagent 详情或查看运行上下文 | 前端 Team 面板将 agent 项做成可交互入口；点击后定位对应 agent 详情、子会话或 trace；不可用状态需给出明确 disabled 原因 |
| BUG-30 | P1 | Chat / Team | open | Subagent 返回消息被渲染成用户消息 | TeamResult / subagent 返回结果在 Chat 消息流中被归类或展示成用户返回的消息，例如 `[TeamResult handle=... collabRunId=...]` 后的结果角色不正确，导致对话归因混乱 | 检查后端消息 role / type 持久化与前端 `normalizeMessages`、消息分组逻辑；TeamResult / subagent output 应以 assistant / agent 结果样式展示，并保留 handle、collabRunId、sessionId 等元数据 |

## 待分流

当前无。新记录如果缺少复现、影响范围或修复方向，先放这里；补齐后再移入“开放中”。

## 暂缓 / Watchlist

| ID | 原因 | 重评触发条件 | 文档 |
| --- | --- | --- | --- |
| BUG-G | 根因已修，剩余 sanitizer / 尾部不变量属于防御性补强 | 再次出现 dangling assistant `tool_use` / 缺失 `tool_result` | [需求包](requirements/deferred/BUG-G-defensive-hardening/index.md) |

## 最近修复

| ID | 修复日期 | 标题 | 交付 / 记录 |
| --- | --- | --- | --- |
| BUG-24 | 2026-04-21 | Sessions 列表页 TOKENS / CONTEXT / COST / LAST 列和 header 错位 | [历史修复记录](references/bug-history-2026-04-21.md#bug-24) |
| BUG-23 | 2026-04-21 | Session 详情 drawer 的 title / tabs / body 三行左缘未对齐 | [历史修复记录](references/bug-history-2026-04-21.md#bug-23) |
| BUG-22 | 2026-04-21 | Session 详情页 Context tab 显示 391K/200K | [历史修复记录](references/bug-history-2026-04-21.md#bug-22) |
| BUG-21 | 2026-04-21 | Chat 右栏 Context budget 卡片常年空白 | [历史修复记录](references/bug-history-2026-04-21.md#bug-21) |
| BUG-20 | 2026-04-21 | Chat 顶部 crumb 里 `depth 0` 对所有顶层 session 冗余显示 | [历史修复记录](references/bug-history-2026-04-21.md#bug-20) |
