# SkillForge Bug 历史修复记录 - 2026-04-16

> 整理于：2026-04-29
> 来源：`docs/bugs.md` 重整前的“已修复”列表。

本文件保留 2026-04-16 修复完成的 bug 长记录。新 bug 修复后，优先把交付事实写入 `docs/delivery-index.md` 或对应需求包；历史参考文件按修复日期拆分为 `bug-history-yyyy-mm-dd.md`。

## 已修复

### BUG-1

| 字段 | 内容 |
| --- | --- |
| 标题 | Session 自动 title 在 eval/技术 prompt 下生成无意义标题 |
| 修复日期 | 2026-04-16 |
| 修复方式 | `applyImmediateTitle` 新增启发式过滤：消息以 `*`/`#`/`{`/`[` 开头或长度 < 10 时跳过立即命名，等 smart rename 接管 |

### BUG-2

| 字段 | 内容 |
| --- | --- |
| 标题 | Eval session 的 smartTitle LLM 被技术 prompt 误导 |
| 修复日期 | 2026-04-16 |
| 修复方式 | `doSmartRename` system prompt 补充指引：技术任务类对话描述任务类型而非复述指令原文 |

### BUG-3

| 字段 | 内容 |
| --- | --- |
| 标题 | 多轮对话后 session 展示页面用户 query 消失、agent 中间内容像用户输入 |
| 修复日期 | 2026-04-16 |
| 修复方式 | `normalizeMessages` 识别 `[Context summary from ...]` compaction 前缀：独立摘要消息直接跳过；合并形态提取 `---` 分隔符之后的原始用户文本 |

### BUG-4

| 字段 | 内容 |
| --- | --- |
| 标题 | 评测 run 失败之后没有任何提示原因 |
| 修复日期 | 2026-04-16 |
| 修复方式 | `handleTrigger` catch 块展示 axios 错误详情；`EvalDetailDrawer` 在 FAILED 状态下显示 `run.errorMessage` Alert |

### BUG-5

| 字段 | 内容 |
| --- | --- |
| 标题 | chat 输入框只有一行，不能自动换行 |
| 修复日期 | 2026-04-16 |
| 修复方式 | `ChatWindow.tsx` 将 `Input` 替换为 `Input.TextArea`，加 `autoSize={{ minRows: 1, maxRows: 6 }}`；Enter 发送，Shift+Enter 换行 |
