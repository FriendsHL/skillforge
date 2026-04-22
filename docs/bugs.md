# SkillForge 已知 Bug / 待修问题

> 更新于：2026-04-22
> 格式：严重度 = P0（阻断）/ P1（影响体验）/ P2（低优）

---

## 开放中

（当前无开放 bug）

---

## 已修复

| #   | 标题                                          | 修复日期       | 修复方式                                                                                                              |
| --- | ------------------------------------------- | ---------- | ----------------------------------------------------------------------------------------------------------------- |
| 1   | Session 自动 title 在 eval/技术 prompt 下生成无意义标题  | 2026-04-16 | `applyImmediateTitle` 新增启发式过滤：消息以 `*`/`#`/`{`/`[` 开头或长度 < 10 时跳过立即命名，等 smart rename 接管 |
| 2   | Eval session 的 smartTitle LLM 被技术 prompt 误导 | 2026-04-16 | `doSmartRename` system prompt 补充指引：技术任务类对话描述任务类型而非复述指令原文 |
| 3   | 多轮对话后 session 展示页面用户 query 消失、agent 中间内容像用户输入 | 2026-04-16 | `normalizeMessages` 识别 `[Context summary from ...]` compaction 前缀：独立摘要消息直接跳过；合并形态提取 `---` 分隔符之后的原始用户文本 |
| 4   | 评测 run 失败之后没有任何提示原因 | 2026-04-16 | ① `handleTrigger` catch 块展示 axios 错误详情；② `EvalDetailDrawer` 在 FAILED 状态下显示 `run.errorMessage` Alert |
| 5   | chat 输入框只有一行，不能自动换行                         | 2026-04-16 | `ChatWindow.tsx` 将 `Input` 替换为 `Input.TextArea`，加 `autoSize={{ minRows: 1, maxRows: 6 }}`；Enter 发送，Shift+Enter 换行 |
| 6   | Chat 详情多轮会话较长时，最早的消息丢失、不可见                  | 2026-04-20 | `ChatWindow.tsx` 将 `scrollIntoView({ behavior: 'smooth' })` 改为直接 `el.scrollTop = el.scrollHeight`，消除 streaming 高频 delta 下的 smooth scroll 动画冲突 |
| 7   | light / full compact 触发偏频繁                   | 2026-04-20 | `AgentLoopEngine.java` B1 阈值 0.40→0.60，B2 阈值 0.70→0.80；preemptive 0.85 保持不变 |
| 8   | Agent 应能获知用户当前上下文（如 userId）                  | 2026-04-20 | `AgentLoopEngine.java` 在 system prompt 末尾追加 `## Session Context`（userId + sessionId），值经 `sanitizePromptValue()` 净化防 prompt injection |
| 9   | Chat 输入框左侧按钮图标与输入文字未垂直对齐                    | 2026-04-20 | `index.css` `.comp-left-tools` 的 `align-self: flex-end` 改为 `center`，删除补偿用的 `padding-bottom: 4px` |
| 10  | Evals 运行中选择 Agent 的下拉框与设计稿不一致               | 2026-04-20 | `Eval.tsx` 将原生 `<select>` 替换为 Ant Design `<Select>`，跟随主题样式 |
| 11  | Trace 详情中各工具时间条纵向间距偏近                       | 2026-04-20 | `traces.css` `.tr-span-row` padding 从 `6px 12px` 改为 `10px 12px` |
| 12  | Trace 详情整体耗时文案被截断，仅能看见首段数字                  | 2026-04-20 | `traces.css` 给 `.tr-stat-v` 加 `white-space: nowrap`，`.tr-stats-bar` 加 `flex-wrap: wrap`，`.tr-stat` 加 `min-width: 0` |
| 13  | Session 列表中的 tokens、context、cost 等字段没有数据 | 2026-04-21 | `SessionList.tsx` `normalizeSession` 将 `raw.totalTokens` 改为 `totalInputTokens + totalOutputTokens`，对齐后端实际字段名 |
| 14  | Evals 页面看不到评测数据集，失败原因与待优化点信息缺失 | 2026-04-21 | `Eval.tsx` EvalDrawer Cases 标签补充：run 级 errorMessage alert、scenario 级 errorMessage/attribution 列、improvementSuggestions 区块 |
| 15  | Evals 测试集中缺少"将 session 信息加入"入口按钮 | 2026-04-21 | `Eval.tsx` EvalDrawer 新增 Scenarios 标签，集成已有 `ScenarioDraftPanel` 组件（含"Extract from Sessions"入口） |
| 16  | 飞书聊天中仅展示"要做的事情"一句，未返回最终结果 | 2026-04-21 | `ChatWebSocketHandler.java` 将 channel 回复从 `assistantStreamEnd` 延迟到 `sessionStatus("idle")`；`ChannelTurnContext` 改为在每次 streamEnd 时快照 `finalText`，idle 时发送最后一次 LLM stream 的完整文本 |
| 17  | 飞书消息路由崩溃（HibernateAssertionFailure）导致用户发消息永远无回复 | 2026-04-21 | 根因：Hibernate ActionQueue 先 INSERT 后 UPDATE，partial unique index 未释放引发约束冲突，catch 块在同一中毒 session 里重试触发 HibernateAssertionFailure；改用 `@Modifying @Query closeById` 直接执行 JPQL UPDATE，绕过 ActionQueue 顺序；`ChannelSessionRouter` 在非事务上下文里重试 resolveSession |
| 18  | 飞书收到消息无任何即时反馈 | 2026-04-21 | 收到消息后调用飞书 reaction API 加 Typing 表情（`sendAck`），回复发出前自动移除（`removeAck`）；通过 `ChannelSessionOutputEvent.ackReactionId` 跨异步边界传递 reaction ID |
| 19  | Chat 右栏 CONTEXT tab 名称被截断（只能看到 "CONTE…"） | 2026-04-21 | `index.css` `.chat-redesign .rail-tabs` padding 10→6、`.rail-tab` padding 12→8、字号 12→11、letter-spacing 0.08→0.04，让 4 个 tab 都能完整显示 |
| 20  | Chat 顶部 crumb 里 `depth 0` 对所有顶层 session 冗余显示 | 2026-04-21 | `Chat.tsx` 只在 `sessionDepth > 0` 时渲染；同时把"depth"换成 channel badge 指示会话来源渠道（web / 飞书 / telegram …） |
| 21  | Chat 右栏 Context budget 卡片常年空白 | 2026-04-21 | `Chat.tsx` tokenUsage 读的是不存在的 `totalTokens` 字段；改为 `totalInputTokens + totalOutputTokens`，同时附带 input/output 明细 |
| 22  | Session 详情页 Context tab 显示 391K/200K（≈195% 被 clamp 到 100%） | 2026-04-21 | 原 panel 把"累计 LLM 调用 token"当"当前窗口占用"显示 + 硬编码 200K 分母。改为调用新的 `/api/chat/sessions/{id}/context-breakdown`：分子=真实当前 context 占用（走 SystemPromptBuilder + ToolSchemas + 当前 context messages）、分母=agent 实际模型的 window（`ModelConfig.lookupKnownContextWindow`，qwen=32K / claude=200K / gpt-4o=128K …），lifetime 累计另起一行不再混淆 |
| 23  | Session 详情 drawer 的 title / tabs / body 三行左缘未对齐 | 2026-04-21 | `skills.css` `.sf-drawer-tabs` padding-left 20→12，配合每个 tab 自身的 12px 内边距刚好落在 24px，与 `.sf-drawer-head` / `.sf-drawer-body` 对齐 |
| 24  | Sessions 列表页 TOKENS / CONTEXT / COST / LAST 列和 header 错位 | 2026-04-21 | `.sess-row` 声明 `width: 100%` 但 box-sizing 仍是 content-box，`padding: 12px 16px` 让 row 实际渲染比 container 宽 32px；统一 `.sess-row` + `.sess-table-h` 为 `box-sizing: border-box` + `width: 100%`，fr 轨道一致，所有 cell `left` 对齐 |
