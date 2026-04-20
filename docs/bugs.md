# SkillForge 已知 Bug / 待修问题

> 更新于：2026-04-20
> 格式：严重度 = P0（阻断）/ P1（影响体验）/ P2（低优）

---

## 开放中

_无_

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
