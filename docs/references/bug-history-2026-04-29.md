# SkillForge Bug 历史修复记录 - 2026-04-29

> 整理于：2026-04-29
> 来源：BUG-25 / BUG-26 由 OBS-1（Session × Trace 合并详情视图）+ session-detail 重构落地解决。

本文件保留 2026-04-29 修复完成的 bug 长记录。新 bug 修复后，优先把交付事实写入 `docs/delivery-index.md` 或对应需求包；历史参考文件按修复日期拆分为 `bug-history-yyyy-mm-dd.md`。

## 已修复

### BUG-25

| 字段 | 内容 |
| --- | --- |
| 标题 | Traces 页面检索接口不支持通过 sessionId 模糊查询 |
| 修复日期 | 2026-04-29 |
| 修复方式 | OBS-1 把 trace 合并进 session 详情页：每个 session 进入 `SessionDetail` 后，左侧 `TraceSidebar` 直接列出该 session 全部 trace（按时间倒排，自动选最新条），中间 `SessionWaterfallPanel` 展示 span 瀑布。排查不再需要从 Traces 列表用 sessionId 反查 trace —— 入口已经是 session，原有"sessionId 前缀模糊匹配"需求被消除。Traces 列表页保留作全局总览。 |
| 相关交付 | OBS-1（commit `ffa2248`）+ session-detail 重构（commit `6aa5b98`、`f768a7b`） |
| 验证 | dashboard 实测：Sessions 列表 → 进入 session → 左侧自动加载该 session 全部 trace；中间 waterfall 显示完整 span 时间线 |

### BUG-26

| 字段 | 内容 |
| --- | --- |
| 标题 | Chat 页面只展示 sessionId 前缀，复制后无法用于查询 session 的 Tool |
| 修复日期 | 2026-04-29 |
| 修复方式 | 新 `SessionDetail` 页 header 在标题下直接展示完整 sessionId（`SessionDetail.tsx:303` `<span>{sessionId}</span>`，等宽字体），用户可整段选中复制；后续配合 OBS-1 的 trace 列表，Agent 的 `GetSessionMessages` 等 session 查询 Tool 拿到的就是完整 ID，不再卡在前缀。Chat 顶部 crumb 仍按 BUG-20 修复后的最小化策略保留前缀显示作排版用，完整 ID 通过 Session Detail 入口暴露。 |
| 相关交付 | OBS-1（commit `ffa2248`）+ session-detail 重构（commit `6aa5b98`、`f768a7b`） |
| 验证 | dashboard 实测：进入 SessionDetail → header 完整 sessionId 可复制 → 粘贴进 Tool 调用即可命中 |
