# SkillForge 已知 Bug / 待修问题

> 更新于：2026-04-16
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
