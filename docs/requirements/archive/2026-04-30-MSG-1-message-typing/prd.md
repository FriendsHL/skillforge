# MSG-1 PRD

---
id: MSG-1
status: delivered
delivered: 2026-04-30
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-30
updated: 2026-05-01
---

## 摘要

将 Chat 消息类型正规化，合并解决 BUG-30 和 BUG-31：SubAgent / Team 结果不再被渲染成用户消息；`ask_user` 不再是弹框或临时 WebSocket 状态，而是持久化在消息流中的一次性内联卡片。

阻断式交互卡片统一为非阻塞 waiting + continuation 恢复：`ask_user` 由模型主动触发，install / agent mutation approval 由框架拦截工具触发。触发后当前 loop 结束并释放 worker；用户显式点击卡片后，后端补齐对应 `tool_result` 或工具结果，并开启新的 trace loop 继续。

## 目标

- 为 `t_session_message` 增加 UI 语义型 `message_type`，让前端能区分普通消息、Team/SubAgent 结果、ask 卡片和审批卡片。
- 将 `ask_user` 卡片持久化到消息流，支持刷新、重连和服务重启后的恢复。
- 移除 `ask_user` 和审批卡片的长时间阻塞等待模型，释放 `chatLoopExecutor` worker。
- 用户点击 ask 卡片后，补齐原 ask_user 的 `tool_result`，卡片折叠为“已回答”历史摘要，不能重复提交。
- 用户不点击 ask 卡片、直接发送新消息时，pending ask 标记为 `superseded`，新消息按普通 user turn 继续。
- 审批卡片不能通过自然语言隐式 approve / deny，必须显式点击卡片。

## 非目标

- 不改变 install confirmation / agent mutation confirmation 的安全策略、匹配规则或审批文案。
- 不改变模型是否调用 `ask_user` 的决策逻辑；模型仍通过工具调用决定是否问用户。
- 不做多用户权限模型重构，只延续现有 session ownership 校验。
- 不引入通用 workflow engine 或独立任务队列表。
- 不把所有历史消息完整重写为新结构；历史兼容可以通过轻量回填或前端 marker 兼容实现。

## 功能需求

### 消息类型

新增 UI 语义型 `message_type`，初始值域：

| message_type | 含义 | 展示行为 |
| --- | --- | --- |
| `normal` | 普通 user / assistant 消息 | 按现有气泡渲染 |
| `team_result` | TeamCreate 子 agent 返回 | 按 Team/SubAgent 结果卡片渲染，不当成用户本人输入 |
| `subagent_result` | 非 collab SubAgent 返回 | 按 SubAgent 结果卡片渲染，不当成用户本人输入 |
| `ask_user` | 模型请求 human 输入 | 按消息流内联 ask 卡片渲染 |
| `confirmation` | 框架拦截危险/变更类工具后请求审批 | 按消息流内联审批卡片渲染 |

`message_type` 是 UI / 控制流语义，不替代现有 `msg_type`（NORMAL / SUMMARY / COMPACT_BOUNDARY 等存储层类型）。

### BUG-30：Team/SubAgent 结果渲染

- 新产生的 TeamResult / SubAgent Result 消息必须带对应 `message_type`。
- 前端必须按 `message_type` 渲染，不再只依赖 `role` 判断。
- Team/SubAgent 结果可以继续以 user-role 进入 LLM 上下文，但 UI 不应把它显示成用户本人消息。
- 老历史 `[TeamResult ...]` marker 不能渲染崩溃；可通过回填或前端兼容识别。

### BUG-31：ask_user 内联卡片

- 当模型调用 `ask_user` 时，后端写入一条 `message_type='ask_user'` 的消息。
- ask 卡片直接出现在 Chat 消息流，不使用全局弹框。
- ask 卡片至少展示 question、context、options、allowOther 状态。
- ask 卡片支持用户选择 option 或填写自定义答案。
- 用户点击 ask 卡片提交答案后，后端必须补齐对应 `tool_result` 并开启新的 trace loop。
- 用户提交答案后，卡片折叠为“已回答”历史摘要，只读不可再次点击。
- 页面刷新、切换 session、WebSocket 重连后，卡片必须从消息历史恢复。

### 阻断式审批卡片

- install approval、CreateAgent / UpdateAgent approval 与 ask_user 使用同一类持久化卡片和 continuation 恢复机制。
- 审批卡片由框架拦截工具调用后生成，不是模型主动 ask。
- 用户 approve 后，后端执行或继续被拦截的工具，并把结果作为对应 `tool_result` 交给后续 loop。
- 用户 deny 后，后端生成拒绝 `tool_result` 交给后续 loop。
- 用户处理审批后，卡片折叠为“已批准”或“已拒绝”历史摘要，只读不可再次点击。
- 用户自然语言输入不能隐式 approve / deny 审批卡片。

### 非阻塞等待与恢复

- 模型调用 `ask_user` 或框架触发 approval 后，当前 agent loop 结束，session 状态变为 `waiting_user`。
- 后端不能在 `chatLoopExecutor` worker 内长时间等待用户回答或审批。
- 用户点击 ask 卡片提交答案后，后端补齐上一次 ask_user 的 `tool_result`，并启动新的 trace loop。
- 用户直接在输入框发送新消息时，如果 session 存在未回答 ask 卡片，该输入视为新 user turn；后端标记该 ask 为 `superseded`，并重新启动 loop。
- 如果 session 存在 pending 审批卡片，主输入框不能绕过审批直接 approve / deny；用户需要先处理审批卡片。
- 如果用户重复点击已完成卡片，后端必须拒绝或幂等返回不可再次提交状态；前端也必须禁用重复操作。

### 状态与可恢复性

- 阻断式卡片需要记录创建时间和完成时间。
- 已完成卡片必须折叠为历史摘要保留，便于排查为什么恢复了 loop。
- session 处于 `waiting_user` 时，Chat 页面应展示清晰状态。
- 服务重启后，未完成卡片仍应可见且可恢复，不得留下可点击但必失败的卡片。

## 用户流程

### 用户点击 ask 卡片回答

1. Agent 正常运行。
2. 模型调用 `ask_user`。
3. Chat 消息流出现 ask 卡片，session 显示等待用户。
4. 当前 loop 结束，worker 释放。
5. 用户点击一个选项或填写自定义答案。
6. 卡片折叠为“已回答”历史摘要。
7. 后端补齐上一次 ask_user 的 `tool_result` 并开启新的 trace loop。
8. Agent 基于用户答案继续回复。

### 用户直接输入继续

1. Agent 调用 `ask_user` 后，Chat 中出现 ask 卡片。
2. 用户不点击卡片，直接在输入框输入补充说明或新问题。
3. 后端将该输入视为新的 user turn，而不是卡片点击。
4. ask 卡片标记为 `superseded`，只读不可点击。
5. 新 trace loop 启动，Agent 基于用户新输入继续。

### 用户点击审批卡片

1. 模型发出 install / agent mutation 类工具调用。
2. 框架拦截该工具调用，写入 `message_type='confirmation'` 的审批卡片。
3. 当前 loop 结束，worker 释放。
4. 用户点击 approve 或 deny。
5. approve 时，后端执行或继续被拦截工具，并产生对应 `tool_result`；deny 时产生拒绝 `tool_result`。
6. 卡片折叠为“已批准”或“已拒绝”历史摘要。
7. 新 trace loop 基于该工具结果继续。

## 验收标准

- [ ] TeamResult / SubAgent Result 在 Chat 消息流中不再显示为用户本人消息。
- [ ] 模型调用 `ask_user` 后，Chat 消息流出现内联 ask 卡片。
- [ ] ask 卡片刷新页面或 WebSocket 重连后仍能恢复。
- [ ] ask 卡片提交一次后折叠为“已回答”历史摘要，不能重复点击提交。
- [ ] 用户点击 ask 卡片后，后端补齐对应 `tool_result` 并开启新 trace loop。
- [ ] 用户直接输入新消息可以继续 waiting_user session，并使 pending ask 标记为 `superseded`。
- [ ] 审批卡片不能通过自然语言隐式 approve / deny。
- [ ] `ask_user` 和审批等待期间不占用 `chatLoopExecutor` worker。
- [ ] 老 session 中缺少 `message_type` 的普通消息仍正常渲染。
- [ ] 老 `[TeamResult ...]` marker 至少有兼容展示或回填方案，不出现归因混乱的新回归。

## 验证预期

- 后端单测覆盖 `message_type` 持久化、Team/SubAgent 结果类型、ask 卡片创建、回答一次性、点击卡片补 `tool_result`、直接输入 supersede waiting ask、审批卡片不能被自然语言 approve。
- 后端集成或服务层测试覆盖刷新/重载消息后阻断式卡片仍可恢复。
- 前端单测覆盖 `normalizeMessages` 按 `messageType` 分发、ask 卡片 answered 后折叠摘要、TeamResult 不渲染为 user bubble。
- 浏览器检查 Chat 页面：ask 卡片内联展示、点击后折叠摘要、直接输入 supersede、审批卡片必须点击处理且完成后折叠摘要、重连后恢复。

## 风险

- 非阻塞卡片恢复必须保护 `tool_use` / `tool_result` 配对不变量。
- `message_type` 不能和现有 `msg_type` 混淆，否则 compaction / summary / row store 逻辑容易被误改。
- 直接输入恢复 waiting_user session 时要避免重复 append 用户消息或重复启动 loop。
- 自然语言不能绕过审批卡片执行危险工具。
- 历史 TeamResult marker 兼容不能误判普通用户文本。
