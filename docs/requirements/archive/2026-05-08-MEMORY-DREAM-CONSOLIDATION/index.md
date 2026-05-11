# MEMORY-DREAM-CONSOLIDATION

---
id: MEMORY-DREAM-CONSOLIDATION
mode: lite
status: done
priority: P1
risk: Mid
created: 2026-05-08
updated: 2026-05-11
---

## 背景

调研报告 `/tmp/memory-dream-research.md` 关键发现：

> **MemoryConsolidator 已存在**！包括 scoring + status transitions + capacity enforcement 全套逻辑都写好了，**只是没 @Scheduled cron 调**。基础设施 80% 已 ready。

跟随 SkillForge 现有 `t_memory` SQL 架构（不走 claude code 的 .md 文件方案），加 cron + embedding-based dedup 增量。**纯 SQL + embedding，零 LLM 调用**。

## 范围

### G1. MemoryConsolidationScheduler（新建）

`skillforge-server/src/main/java/com/skillforge/server/memory/MemoryConsolidationScheduler.java`

- `@Component`
- `@Scheduled(cron = "0 30 3 * * *")` 凌晨 3:30（错开 3 点 SkillDraftScheduledExtractor / 4 点 Monday SkillScheduledEvaluator / 5 点 Tuesday SkillSelfImproveLoop）
- yaml gate: `skillforge.memory.consolidation.scheduled-enabled: true`（默认 true 可关）
- public `runOnce()` 方法（test + admin manual trigger）
- 流程：
  - 取所有 active user（最近 7 天有 session/message —— grep `SessionRepository.findDistinctUserIdsWithRecentMessage` 之类，没有就加 native query）
  - 循环每 user 调 `memoryConsolidator.consolidate(userId)`
  - per-user try/catch + log.warn 继续不阻塞 cron（INV-2 from prior crons）

### G2. MemoryConsolidator 加 embedding dedup（改造）

`MemoryConsolidator.java` 的 `consolidate(userId)` 现有流程：
1. 删除过期 archived
2. 算 score
3. 状态迁移 (ACTIVE→STALE / non-ARCHIVED→ARCHIVED)
4. capacity 强制 (count > maxActivePerUser → demote)

**新增 step 0**（在 score 之前）—— **embedding-based dedup**：
- 取 user 所有 ACTIVE memory（已有 embedding 字段）
- 对每对 `(memA, memB)` 计算 cosine 相似度
- 若 cosine > `cosineMergeThreshold = 0.85` → 取 score 高那条保留，弱者 `status='ARCHIVED'` + `archived_reason='dedup-merge-with-{winnerId}'`
- 实现：用 `EmbeddingService` 现有 cosine util；`O(n²)` 遍历可接受（capacity 限 1500，最多 1.1M pair）；优化路径 V2 用 pgvector 索引扫
- 若 user 没 embedding 字段（旧数据）→ 跳过 dedup 直接走原 score / transition 路径

### G3. archived_reason 字段（可选 V66 migration）

`t_memory` 加 `archived_reason VARCHAR(128)` nullable —— 让 archive 时知道是 expire / capacity demote / dedup-merge 哪种。

V66 migration:
```sql
ALTER TABLE t_memory ADD COLUMN IF NOT EXISTS archived_reason VARCHAR(128);
COMMENT ON COLUMN t_memory.archived_reason IS 'MEMORY-DREAM-CONSOLIDATION: tracking the reason memory was archived (expired_ttl / capacity_demote / dedup_merge_with_X)';
```

`MemoryEntity` 加字段 + getter/setter。`MemoryConsolidator` 现有 archive 路径补设置 reason。

### G4. Admin trigger endpoint（临时）

类似 SKILL-EVOLVE-LOOP 的 admin endpoints：

`POST /api/admin/memory/consolidation/run-once?userId=X` （userId 可选，缺省扫所有）—— 手动触发 + Phase Final 测试用。

## 设计决策（已基于 SkillForge 现有约定回答，不需 user 拍）

| 问题 | 决策 |
|---|---|
| Idle 定义 | "最近 7 天 user 有 session/message" |
| Granularity | per-user（不细分 agent / session） |
| Batch size | 整 user memory 一锅端（capacity 1500 限制下没问题） |
| Archive vs delete | **Archive**（status=ARCHIVED 90 天后才真删，跟 V29 transition 一致） |
| User 可见 | dashboard 不需要看，admin endpoint 触发即可（V2 加 dashboard 概览卡时再加 metric） |
| History tracking | `archived_reason` 字段记录原因，不另建 history table |
| LLM-driven vs rule-based | **rule-based + embedding cosine** dedup（零 LLM 成本） |
| Embedding model | 复用现有 `EmbeddingService` 默认 model（不引入新 model） |

## 交付状态

已交付并归档。交付事实以 [delivery-index.md](../../../delivery-index.md) 的 `MEMORY-DREAM-CONSOLIDATION` 行为准。

核心提交：

- `05d0593` — Memory v2 backend/dashboard 更新，包含 `MemoryConsolidator`、Memory 页面手动整理入口、API client 等基础改造。
- `6468fe3` — `MemoryConsolidationScheduler` / admin trigger summary 细化，返回各阶段整理计数。
- `118f887` — 补齐 V66 `archived_reason` migration 与需求包落盘。

## 验收

- [x] `MemoryConsolidationScheduler` cron 0 30 3 * * * 跑通
- [x] yaml `skillforge.memory.consolidation.scheduled-enabled` 关掉后不跑
- [x] `MemoryConsolidator.consolidate(userId)` 加 cosine dedup 步骤，>0.85 的 ARCHIVED 弱者
- [x] V66 migration `archived_reason` 字段落地
- [x] Admin endpoint `POST /api/admin/memory/consolidation/run-once` 可用
- [x] `mvn -pl skillforge-server test` 全套绿（保 1136+）
- [x] cron e2e：手动触发 → 看 t_memory ARCHIVED 增加 + log 输出 dedup 决策

## V2 推迟

- LLM-driven weekly synthesis review（Option 3 hybrid 的高级模式，等 Option 2 跑稳后加）
- pgvector index 优化 cosine 扫描（capacity 远超 1500 时再加）
- Dashboard 概览卡显示 "本周整理 X 条 memory"
- `derivedFromMemoryIds[]` array 字段（V3 真做 synthesis 时加）

## 实施

- BE 1 dev (Mid 单一 fix 轮，无 FE)
- 完成后主会话目检 + commit
