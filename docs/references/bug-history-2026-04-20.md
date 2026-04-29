# SkillForge Bug 历史修复记录 - 2026-04-20

> 整理于：2026-04-29
> 来源：`docs/bugs.md` 重整前的“已修复”列表。

本文件保留 2026-04-20 修复完成的 bug 长记录。新 bug 修复后，优先把交付事实写入 `docs/delivery-index.md` 或对应需求包；历史参考文件按修复日期拆分为 `bug-history-yyyy-mm-dd.md`。

## 已修复

### BUG-6

| 字段 | 内容 |
| --- | --- |
| 标题 | Chat 详情多轮会话较长时，最早的消息丢失、不可见 |
| 修复日期 | 2026-04-20 |
| 修复方式 | `ChatWindow.tsx` 将 `scrollIntoView({ behavior: 'smooth' })` 改为直接 `el.scrollTop = el.scrollHeight`，消除 streaming 高频 delta 下的 smooth scroll 动画冲突 |

### BUG-7

| 字段 | 内容 |
| --- | --- |
| 标题 | light / full compact 触发偏频繁 |
| 修复日期 | 2026-04-20 |
| 修复方式 | `AgentLoopEngine.java` B1 阈值 0.40→0.60，B2 阈值 0.70→0.80；preemptive 0.85 保持不变。Follow-up 2026-04-23 (ENG-1)：`detectWaste` 把 LLM 入参错误（VALIDATION 类）从 consecutive-error 计数和 identical-tool-use 计数中剔除，切断 validation error 到 compaction 的正反馈 |

### BUG-8

| 字段 | 内容 |
| --- | --- |
| 标题 | Agent 应能获知用户当前上下文（如 userId） |
| 修复日期 | 2026-04-20 |
| 修复方式 | `AgentLoopEngine.java` 在 system prompt 末尾追加 `## Session Context`（userId + sessionId），值经 `sanitizePromptValue()` 净化防 prompt injection |

### BUG-9

| 字段 | 内容 |
| --- | --- |
| 标题 | Chat 输入框左侧按钮图标与输入文字未垂直对齐 |
| 修复日期 | 2026-04-20 |
| 修复方式 | `index.css` `.comp-left-tools` 的 `align-self: flex-end` 改为 `center`，删除补偿用的 `padding-bottom: 4px` |

### BUG-10

| 字段 | 内容 |
| --- | --- |
| 标题 | Evals 运行中选择 Agent 的下拉框与设计稿不一致 |
| 修复日期 | 2026-04-20 |
| 修复方式 | `Eval.tsx` 将原生 `<select>` 替换为 Ant Design `<Select>`，跟随主题样式 |

### BUG-11

| 字段 | 内容 |
| --- | --- |
| 标题 | Trace 详情中各工具时间条纵向间距偏近 |
| 修复日期 | 2026-04-20 |
| 修复方式 | `traces.css` `.tr-span-row` padding 从 `6px 12px` 改为 `10px 12px` |

### BUG-12

| 字段 | 内容 |
| --- | --- |
| 标题 | Trace 详情整体耗时文案被截断，仅能看见首段数字 |
| 修复日期 | 2026-04-20 |
| 修复方式 | `traces.css` 给 `.tr-stat-v` 加 `white-space: nowrap`，`.tr-stats-bar` 加 `flex-wrap: wrap`，`.tr-stat` 加 `min-width: 0` |
