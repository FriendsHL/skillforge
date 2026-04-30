# P9-2 Tool Result 归档

---
id: P9-2
mode: full
status: done
priority: P0
risk: Full
created: 2026-04-28
updated: 2026-04-30
delivered: 2026-04-30
---

## 摘要

归档超大的单轮 tool result，并在每次 LLM 请求前执行 tool_result 聚合预算裁剪，避免长 session 持续把巨量工具输出带进活跃 LLM context。

本需求当前合并处理 [BUG-32](../../../bugs.md)：`Token budget exceeded` 硬停、`max_tokens` 截断恢复失败、以及 session `919c2bda-1867-40eb-8c7c-4bb49d341ee2` 暴露的 30+ 次工具结果完整回放问题。

## 阅读顺序

1. [MRD](mrd.md)
2. [PRD](prd.md)
3. [技术方案](tech-design.md)

## 当前状态

**done，2026-04-30 交付**。Full Pipeline 走完：Phase 1 Backend Dev 实现 → Phase 2 双 Reviewer 对抗 1 轮（A: 0 blocker / 6 warning / 建议 PASS；B: 0 blocker / 4 warning / 建议 FIX）→ Phase 3 Judge Opus 重判（0 blocker / 10 warning，3 项必修 / 7 项 follow-up）→ Phase 3.5 Dev 一次性 fix（FIX-1 per-iteration reset / FIX-2 PG-safe ON CONFLICT DO NOTHING / FIX-3 compaction interaction 测试）→ Phase Final 主会话 mvn 全量回归通过 + 范围目检无 scope creep。详见 [delivery-index 2026-04-30 行](../../../delivery-index.md)。

**Pipeline 关键决策**：
- "turn" 语义在 PRD 中确认对应**单次 LLM 调用 / 单次 agent loop iteration**（与 Claude Code `query.ts:1104,1157` 的 `hasAttemptedReactiveCompact` per-413-event 一致），不是 per-`run()`。Judge round 1 抓出 dev round 1 实现的 per-run 语义违背 PRD，落 FIX-1。
- Judge round 1 抓出 round 1 实现 `applyArchive_uniqueViolation_reusesWinner` 测试**用 Mockito 走通了一条 PostgreSQL 上跑不通的代码路径**（catch-and-relookup 在 PG TX aborted 后必失败）→ 改为 PG-safe `INSERT ... ON CONFLICT DO NOTHING`（FIX-2）。这是本轮 Pipeline 最有价值的 catch，Sonnet reviewer 标 warning，Opus Judge 升判识别出接近"静默失败"边界。

**已确认非目标**：`ToolResultRetrieveSkill`（让模型按 archive ID 取回原文）—— 与 Claude Code `applyToolResultBudget` 设计一致，preview 对模型即是最终视图；上线后若有明确诉求再开 P9-2-follow-up 单独评估（[mrd.md](mrd.md) 决议记录）。

**遗留 follow-up**（不阻塞，Judge 标记 J-4 至 J-10）：retainedAggregateChars 遥测低估 <2% / Map-variant List content char 计数 / continuation `assistantStreamEnd` 双触发 / 默认 200K budget 不覆盖 ~99K BUG-32 量级（设计意图，已在 budgeter 注释说明）/ continuation empty-partial 守卫测试 / `findBySessionId` 全量加载 content / SessionService setter injection 偏离构造器规范。详见 `/tmp/nits-followup-p9-2-tool-result-archive.md`（保留作审计）。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 相关历史方案 | [P9 归档方案](../../../requirements/archive/2026-04-22-P9-tool-result-compaction/tech-design.md) |
