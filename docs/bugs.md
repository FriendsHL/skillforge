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
| BUG-33 | P1 | MCP / 安全 | partial | MCP 写接口无 admin-role gate（SSRF 面已缓解，分权仍缺） | `/api/mcp-servers` POST/PUT/DELETE 无 admin-role gate（controller 自承认 no-op TODO）。**SSRF 面已缓解（2026-06-16）**：`McpServerService.validateHttpUrl` 加内网/私网/link-local（含 169.254.169.254 元数据）/ IPv6 ULA / CGNAT IP 拦截 + 强制 https（loopback dev 例外），http MCP server url 不能再指向内网/元数据。**仍 open**：单 token 全权模型无真正分权（userId param 不可信、无 role 表），真 admin gate 需 RBAC（多用户/公网前再做）| RBAC（多用户/公网时）；见 [MCP-HTTP-ANYSEARCH](requirements/archive/2026-06-15-MCP-HTTP-ANYSEARCH/index.md) |
| BUG-34 | P1 | MCP / 前端契约 | open | MCP "测试连接" 按钮 success 字段不匹配致永远 falsy | FE `TestConnectionResponse.success: boolean` vs BE 返回 `{"status":"ok",...}` 字段不匹配 → dashboard "测试连接" 按钮的 `if(body.success)` 永远 falsy。pre-existing，非 MCP-HTTP-ANYSEARCH 引入 | 对齐 FE/BE 契约（FE 改判 `status==='ok'` 或 BE 补 `success` 字段）|
| BUG-35 | P2 | MCP / 重构 | open | `McpServerLifecycle` 里 `new OkHttpClient()` 宜抽 @Bean | 当前在 `McpServerLifecycle` 直接 `new OkHttpClient()`，每处各建一个，未共享连接池 / dispatcher | 抽成 `@Bean` 共享连接池 / dispatcher |
| BUG-36 | P2 | MCP / 重构 | open | `McpServerRequest` 宜改 record | `McpServerRequest` 是可变 getter/setter 类，项目惯例 DTO 用 record（pre-existing）| 改为 record（注意 Jackson 反序列化兼容）|
| BUG-37 | P2 | MCP / DB | open | V152 CHECK 未分阶段 + url 无索引 + transport 隐式枚举（db nit）| V152 的 CHECK 约束未用 NOT VALID 分阶段（本项目数据量小可忽略）；`url` 无索引；`transport` 是隐式枚举（靠 CHECK + 应用层 allow-list，无 DB enum 类型）| 数据量增大或需要 transport 维度查询时再评估；当前可忽略 |
| BUG-38 | P2 | Channel / 飞书 | open | channel 配置保存后不热启动 WS，需重启 server 才生效 | `ChannelConfigService.save()` 只落库，不触发 `ChannelPushManager` (重)连；飞书等 websocket 长连仅在 boot 时由 `ChannelPushManager.start()` 扫 active channel 启动。→ 在 server 运行中通过 dashboard 新建/激活飞书 channel 后，WS 不会自动连上，消息收不到，必须重启 server（2026-06-16 实测踩坑）。对比：MCP server 的 `McpServerLifecycle` 有 `onUpserted` AFTER_COMMIT 热重连，channel 没做 | 给 channel CRUD 加 AFTER_COMMIT 事件 → `ChannelPushManager` reload/restart 对应 connector（参考 `McpServerLifecycle.onUpserted` 模式）|
| BUG-39 | P3 | MCP / 安全 | open | http MCP transport 无 connect-time SSRF guard（DNS rebinding）| BUG-33 的 `validateHttpUrl` 是 config-time 检查，host 解析时干净但 connect 时 rebind 到私网/元数据 IP 仍可绕过。彻底防需在 `McpHttpTransport` connect 时再校验解析到的 IP（或用自定义 SocketFactory 拦截）| 真要防 rebinding 时在 transport connect 路径加 IP 校验；当前 config-time 拦截已覆盖大部分场景 |
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
