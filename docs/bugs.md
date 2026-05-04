# SkillForge Bug Queue

> 更新于：2026-05-02（MSG-1 交付 → BUG-30/BUG-31 closed；docs governance 补回写）
> 规则：这里独立维护体验类小 bug 和待修问题；不等同于 `todo.md` 当前执行队列。

## 使用边界

- `bugs.md` 是 bug 池，优先收集体验问题、可复现异常、展示错误和小的 follow-up。
- `todo.md` 只放已经排进当前执行队列的新需求、严重影响体验的问题、核心协议 / 数据 / 安全问题，或用户明确要求排期的 bug。
- P0、核心不变量、跨前后端协议、持久化、权限、安全问题，应该升级为需求包并在 `todo.md` 排期后实施。
- P1 / P2 体验 bug 默认留在本文件；修复时按实际风险决定 Solo、Lite 或 Full。
- 已修复 bug 只在本文件保留最近摘要；完整历史按修复日期拆分到 `docs/references/bug-history-yyyy-mm-dd.md`。

## 严重度

| 严重度 | 含义                          | 默认处理                        |
| --- | --------------------------- | --------------------------- |
| P0  | 阻断主流程、数据损坏、安全 / 权限风险、核心协议错误 | 进入 `todo.md`，建需求包，Full      |
| P1  | 明显影响排查、协作、主要体验，但有绕过方式       | 留在 bug 池；需要排期时再进入 `todo.md` |
| P2  | 低优体验、文案、轻量交互、重复确认、可等待的优化    | 留在 bug 池，批量处理               |

## 开放中

| ID | 严重度 | 领域 | 状态 | 标题 | 现象 / 影响 | 下一步 |
| --- | --- | --- | --- | --- | --- | --- |
| BUG-27 | P2 | Agent 配置 / 确认流 | open | 同一 session 内连续更新 agent prompt 时需要重复用户确认 | 用户已在当前 session 确认过一次 agent prompt 更新，再次调整仍会触发确认，影响迭代效率 | 将确认授权按 session + agent + update 类型设置短期有效期，或支持一次确认覆盖同一轮 prompt-only 后续修改 |
| BUG-28 | P2 | 审核卡片 / UX | open | 审核卡片需要优化 | 当前审核卡片难以快速判断来源、变更内容、风险点和影响范围；确认 / 拒绝动作不够突出 | 增加来源信息、变更摘要 / diff、风险提示和更清晰的 Approve / Edit / Discard 操作；长内容默认折叠并支持展开 |
## 待分流

当前无。新记录如果缺少复现、影响范围或修复方向，先放这里；补齐后再移入“开放中”。

## 暂缓 / Watchlist

| ID | 原因 | 重评触发条件 | 文档 |
| --- | --- | --- | --- |
| BUG-G | 根因已修，剩余 sanitizer / 尾部不变量属于防御性补强 | 再次出现 dangling assistant `tool_use` / 缺失 `tool_result` | [需求包](requirements/deferred/BUG-G-defensive-hardening/index.md) |
| BUG-29 | Chat 主流 subagent 卡片已有跳子 session 入口；右侧 Team 面板 `RightRail.tsx:872-873` 在 child 已生成时也可点击（`sa-item--clickable` + onClick navigate）；child 未生成时不可点击是合理设计。冗余入口诉求未明确 | 用户报"右侧 Team 面板"差异化诉求 / 多次反馈想从 Team 面板直接跳子 session 而非走 subagent 卡片 | 2026-05-04 audit 确认现状：`RightRail.tsx:870-876` sa-item 已支持点击（仅 hasChild=true 时）|

## 最近修复

| ID | 修复日期 | 标题 | 交付 / 记录 |
| --- | --- | --- | --- |
| **BUG-30** | 2026-04-30 | Subagent 返回消息被渲染成用户消息 | 并入 [MSG-1 消息类型化](requirements/archive/2026-04-30-MSG-1-message-typing/index.md) 交付（`t_session_message.message_type='team_result'/'subagent_result'` 区分 + 前端按 type 分发渲染）；commit `543a60e` |
| **BUG-31** | 2026-04-30 | ask_user 卡片丢失 + 等待期间占用 chatLoop 线程 | 并入 [MSG-1 消息类型化](requirements/archive/2026-04-30-MSG-1-message-typing/index.md) 交付（ask_user 卡片持久化 `message_type='ask_user'` + 非阻塞 `waiting_user` + worker 释放 + continuation 恢复）；commit `543a60e` |
| **BUG-32** | 2026-04-30 | 长任务触发 token budget / `max_tokens` 截断，tool_result 缺少请求前裁剪 | 并入 [P9-2 Tool Result 归档](requirements/archive/2026-04-30-P9-2-tool-result-archive/index.md) 一同交付（per-message 200K archive + request-time aggregate trim + max_tokens continuation 单 turn 1 次防护 + 砍默认 max_input_tokens 累计硬停）；commit `fe0404c`；详见 [delivery-index 2026-04-30 行](delivery-index.md) |
| BUG-26 | 2026-04-29 | Chat 页面只展示 sessionId 前缀，复制后无法用于查询 session 的 Tool | [历史修复记录](references/bug-history-2026-04-29.md#bug-26) |
| BUG-25 | 2026-04-29 | Traces 页面检索接口不支持通过 sessionId 模糊查询 | [历史修复记录](references/bug-history-2026-04-29.md#bug-25) |
| BUG-24 | 2026-04-21 | Sessions 列表页 TOKENS / CONTEXT / COST / LAST 列和 header 错位 | [历史修复记录](references/bug-history-2026-04-21.md#bug-24) |
| BUG-23 | 2026-04-21 | Session 详情 drawer 的 title / tabs / body 三行左缘未对齐 | [历史修复记录](references/bug-history-2026-04-21.md#bug-23) |
| BUG-22 | 2026-04-21 | Session 详情页 Context tab 显示 391K/200K | [历史修复记录](references/bug-history-2026-04-21.md#bug-22) |
| BUG-21 | 2026-04-21 | Chat 右栏 Context budget 卡片常年空白 | [历史修复记录](references/bug-history-2026-04-21.md#bug-21) |
| BUG-20 | 2026-04-21 | Chat 顶部 crumb 里 `depth 0` 对所有顶层 session 冗余显示 | [历史修复记录](references/bug-history-2026-04-21.md#bug-20) |
