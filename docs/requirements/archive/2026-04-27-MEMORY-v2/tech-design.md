# Memory v2: 写入 / 召回 / 淘汰一体化重构

> 状态: **决策已锁定，准备开发**（2026-04-27）
> 创建于: 2026-04-27
> 范围: 不仅是退出/淘汰，包含写入触发时机 + 抽取去重 + 召回路径 + 状态机 + 评分模型 + 前端 UI 全栈
> 关联:
> - [N1 Memory 向量检索技术方案](../../../requirements/archive/2026-04-16-N1-memory-vector-search/tech-design.md) — N1 FTS+pgvector 现状（保留）
> - [docs/todo.md](../../../todo.md) — 排期入口
>
> **本 doc 是 self-contained 方案。读完 §0 + §3 + §6 即可决策。**

---

## TL;DR

当前 SkillForge 记忆系统**不仅缺退出机制**，写入触发时机和召回链路也有结构性缺陷：

1. **每个 session 终生只抽 1 次** —— 长 session 后段、隔天回流的对话永远进不去 memory（v2 用保守事件触发解决：session 关闭 / idle 30min / 累计 30 turn 三事件触发，不做 turn-batch 高频抽）
2. **system prompt 注入永远走 fallback 时间序** —— FTS+Vector hybrid search 在自动注入路径上是死代码
3. **stale 标签下游零引用** —— 标了等于没标
4. **add-time 仅按 title 字面去重** —— 同一事实换个 title 就重复入库
5. **无容量上限 / 无物理淘汰** —— DB 永久膨胀

Memory v2 一次性治理上述 5 个问题，通过 1 个 Flyway migration（V29）+ 5 个 PR 落地，工期 12-15 天。

**源码校准后的修订点**:

- 增量抽取不需要新造消息序号，复用现有 `t_session_message.seq_no`；`last_extracted_message_seq` 必须用 `BIGINT/long`，不能用 `INTEGER`
- `digestExtractedAt` 现在既是终身锁，又会在 `<3 activities` 的短 session 上被置位；v2 必须改为"最后抽取尝试/成功时间"，不能继续当 boolean lock
- 新增 `status/importance/last_score/archived_at` 后，`MemorySnapshotEntity` 和 rollback 也必须同步这些字段，否则 batch rollback 会丢状态
- `memoryProvider` 的 task context 应直接使用 `AgentLoopEngine.run(...)` 已有的 `userMessage` 参数，不需要从 `messages` 里反推最新用户消息
- 评分模型建议用 OpenClaw 风格的加权和，而不是纯乘法，避免新记忆 `recallCount=0` 时分数塌成 0

---

## 0. 现状全景

### 0.1 写入路径（5 步）

```
session loop 完成
  ↓ (ChatService.java:434, 460)
session.setCompletedAt(now) + session.setRuntimeStatus("idle")
  ↓
sessionDigestExtractor.triggerExtractionAsync(sessionId)   @Async
  ↓
SessionDigestExtractor.triggerExtractionAsync()    SessionDigestExtractor.java:57-75
  ├─ if session.parentSessionId != null    → return        (只处理顶级 session)
  ├─ if session.digestExtractedAt != null  → return        ⚠️ 终身锁
  ├─ if activities.size() < 3              → return        (内容门槛)
  ├─ memoryService.beginExtractionBatch()                  (创建 snapshot)
  ├─ llmMemoryExtractor.extract()                          (LLM 抽 1-8 条 JSON)
  │    ├─ system prompt 强约束 5 类 type + 3 级 importance
  │    ├─ user 消息含 existing memory titles 列表 (LLM 自律去重)
  │    ├─ temperature=0.3, max_tokens=2000
  │    └─ 失败降级 rule-based 单条 digest
  ├─ memoryService.createMemoryIfNotDuplicate(每条)        ⚠️ 仅 title 字面去重
  │    └─ scheduleEmbeddingAfterCommit (afterCommit @Async)
  ├─ session.setDigestExtractedAt(now)   ⚠️ 终身锁触发
  └─ memoryConsolidator.consolidate(userId)
       ├─ 同 title 重复 → 删旧
       └─ 30 天没召回 + recallCount<3 → tags += "stale"   ⚠️ 标了不用

兜底: SessionDigestExtractor.extractDailyMemories @Scheduled cron="0 0 3 * * *"
  扫 completedAt!=null AND digestExtractedAt==null → 同流程
  ⚠️ 因为 triggerExtractionAsync 第 1 轮就抽完，cron 几乎扫不到东西，是冗余备份
```

### 0.2 召回路径（4 个入口）

| 入口 | 触发 | 检索 | 上限 | stale 过滤 |
|---|---|---|---|---|
| **A. system prompt 注入** | 每轮 loop 起始 | **当前 taskContext=null → fallback 时间序**（`appendSemanticallyRankedMemories` 是死代码） | preference 10 + feedback 10 + knowledge/project/reference 10 = 30 条；MAX_TOTAL_CHARS=8000 | ❌ |
| **B. `memory_search` Tool** | agent 主动调 | FTS(20) + Vector(20) → RRF(K=60) → topK | 默认 5 / 最多 20，返回 100 字 snippet | ❌ |
| **C. `memory_detail` Tool** | agent 拿到 ID 后查全文 | `findById` | 单条全文 | ❌ |
| **D. 内部估算** | ContextBreakdown / Compaction | `previewMemoriesForPrompt` 同 A | 同 A | ❌ |

### 0.3 淘汰路径

- `MemoryConsolidator.consolidate()` 在 daily cron + 每次 extract 后调用，会标 `stale` tag
- **但 `stale` 在 `MemoryService` / `MemoryRepository` / `MemorySearchTool` / `findByFts` / `findByVector` 全部 grep 零引用**
- 唯一硬删入口：`MemoryService.deleteMemory(id)`（手动 DELETE API）+ `rollbackExtractionBatch`（按 batch 回滚）
- **DB 无任何容量上限，永久膨胀**

### 0.4 五个隐性问题汇总

| # | 问题 | 严重度 | 影响 |
|---|---|---|---|
| **H-1** | 每 session 终生只抽 1 次（首轮 loop 完成时）| 🟡 中 | 长 session 后段、隔天回流的高价值对话进不去 memory。SkillForge 工作型 session 后段新事实密度低，严重度从 🔴 下调；但隔天回流场景仍是真痛点 |
| **H-2** | `memoryProvider` 调用方永远不传 taskContext（`SkillForgeConfig.java:512`），semantic search 死代码；A 路径每次按时间序硬塞 30 条 + 8KB | 🔴 高 | token 浪费 + 召回与 task 完全无关 |
| **H-3** | A 强制塞 + B 主动查双轨重复，同一条 memory 一次 loop 可能进 prompt 两次 | 🟡 中 | token 重复消耗 |
| **H-4** | stale 标签四个入口零过滤 | 🔴 高 | 淘汰机制等于不存在 |
| **H-5** | add-time 仅按 title 字面去重 | 🟡 中 | LLM 输出 title 措辞稍变就重复入库；半年后 1500+ 条/用户 |

### 0.5 源码校准补充（2026-04-27）

这部分是基于当前 SkillForge 源码的二次校准，修正 draft v1 中几个实现假设。

1. **消息序号已存在**: `t_session_message` 有 `(session_id, seq_no)` 唯一约束，`SessionMessageRepository` 已提供 `findBySessionIdAndPrunedAtIsNullAndSeqNoGreaterThanOrderBySeqNoAsc` 等按 seq 增量读取方法。PR-3 不应新建消息序号体系，只需要在 `t_session` 上增加最后抽取游标。
2. **游标类型必须是 long**: `SessionMessageEntity.seqNo` 是 `long`，因此 `last_extracted_message_seq` 应为 `BIGINT DEFAULT 0`，Java 字段用 `long`/`Long`。draft v1 的 `INTEGER` 有溢出和类型不一致风险。
3. **短 session 锁死问题**: `triggerExtractionAsync()` 调 `extractSessionMemories(session)` 后无论是否因为 `<3 activities` 提前 return，都会 `setDigestExtractedAt(now)`。这意味着短 session 后续继续聊天时也可能被永久跳过。v2 的触发逻辑必须以 `last_extracted_message_seq < latestSeq` 为准，`digestExtractedAt` 只能作为节流/观测时间。
4. **taskContext 来源已明确**: `AgentLoopEngine.run(agentDef, userMessage, ...)` 已经拿到当前用户输入。`memoryProvider` 改造时直接传 `userMessage`，比从 `messages` 中抽取更稳。
5. **工具排重要跨 core/server 边界**: `MemorySearchTool` 想排除 L0/L1 已注入的 memoryIds，需要把 `injectedMemoryIds` 从 `LoopContext` 传进 `SkillContext` 或新增 tool execution metadata。当前 `SkillContext` 没有该字段，PR-2 必须包含 core 层改造。

---

## 1. 业界对标（写入抽取维度聚焦）

### 1.1 OpenClaw（即时记录 + cron 升级）

- **写入触发**:
  1. **即时短期记录**: 每次 `memory_search` 返回结果时，异步把 search 结果记录到 short-term store（`tools.ts:59-75` `recordShortTermRecalls`）
  2. **dreaming 定时升级**: cron 触发 `dreaming` 任务（`dreaming.ts:156-177`），从 short-term 候选里按多维分数升级到长期 `MEMORY.md`
- **抽取**: dreaming 用 LLM 排序 + 决策追加到 `MEMORY.md`；rankShortTermPromotionCandidates 走六维加权（frequency 0.24 / relevance 0.3 / diversity 0.15 / recency 0.15 / consolidation 0.1 / conceptual 0.06）
- **门槛**: minScore 0.75 / minRecallCount 3 / minUniqueQueries 2
- **长 session 处理**: 每次 search 都即时记录，不需等 cron；daily MD 由 cron 生成

### 1.2 Claude Code（用户手动 + 自律）

- **写入触发**: 完全用户手动 `/memory` 编辑（写入只走 `writeFile flag='wx'`）
- **抽取**: 不做。用户写什么就是什么
- **去重**: 系统提示模型"不要写重复 memory，先看现有的"靠 LLM 自律
- **长 session 处理**: 不适用
- **召回**: MEMORY.md 全量进 system prompt（200 行硬截断 + 25KB 字节截断 + warning），按需用 Sonnet 选 top-5 相关文件

### 1.3 Hermes-agent（per-turn 抽取）

- **写入触发**: 每个 turn 完成立即 `memory_manager.sync_all()` → 各 provider `sync_turn(user, assistant, session_id)` (`memory_manager.py:210-219`)
- **batch 控制**: `retain_every_n_turns` 默认 1，可配；累计 N turn 触发一次 retain (`hindsight/__init__.py:734-740`)
- **抽取**: holographic 用正则提取实体（无 LLM）；hindsight 用 backend LLM 异步抽取
- **抽取输入**: **滚动窗口**——把整个 session 的所有 turn 拼成 JSON 数组发给 backend（`hindsight/__init__.py:727-746`）
- **去重**: SQLite UNIQUE(content) 严格相同才去重
- **长 session 处理**: 每 N turn 自然 batch；不检测重复内容

### 1.4 MemPalace（verbatim + chunking + scope filter）

- **写入触发**: MCP `add_drawer` agent 主动调用 + 项目文件 mining 一次性扫描
- **抽取**: **不抽取，verbatim 存原文**。chunking 800 char + 100 char overlap + 段落边界
- **去重**: `add_drawer` / `check_duplicate` 做确定性去重 + 语义近邻阈值（公开实现默认约 0.85-0.87），命中时返回 existing drawer 而不是盲写
- **召回**: L0 identity / L1 高重要性 top memories / L2 wing-room scoped search / L3 full search，官方文档给出的 L0+L1 唤醒预算约 600-900 tokens
- **scope narrowing**: wing(人/项目) / room(话题) / drawer(chunk) / hall(类别) — 多维 metadata 标签做检索前过滤
- **删除/淘汰**: 有显式 `delete_drawer` 这类手动删除能力，但未看到与 SkillForge 需要的 ACTIVE/STALE/ARCHIVED 自动状态机等价的机制

### 1.5 关键洞察对比（聚焦"长 session 持续对话"和"抽取派 vs verbatim 派"）

| 维度 | OpenClaw | Claude Code | Hermes | MemPalace | SkillForge 现状 |
|---|---|---|---|---|---|
| **长 session 触发** | 即时记录 search + cron 升级 | N/A | 每 N turn batch | N/A | ❌ 终生 1 次 |
| **抽取 vs verbatim** | LLM 抽（dreaming 决策）| 用户手写 | 正则/backend 抽 | **verbatim** | LLM 抽 |
| **滚动窗口** | 短期 daily MD | N/A | 整个 session 累积 | N/A | ❌ 单次首轮 |
| **召回分层** | MEMORY.md root + daily | MEMORY.md + Sonnet 选 | RAG Tool | L0/L1/L2/L3 | ❌ 平铺 30 条暴塞 |
| **scope filter** | wing/room (隐含 path) | type label | category | wing/room/hall | type 但不用于过滤 |
| **add-time dedup** | embedding ON CONFLICT upsert | LLM 自律 | UNIQUE(content) | 文件级 | title 字面 |
| **淘汰** | 半衰期降权不删 | ❌ 200 行截断 warning | ❌ | ❌ | tags=stale 标了不用 |
| **容量上限** | 无 | 200 行 | 无 | 无 | 无 |

### 1.6 抽取派 vs verbatim 派的根本分歧

- **抽取派**（OpenClaw / Hermes / Claude Code / SkillForge 现状）: LLM/规则把对话压成"事实条目"，存摘要
- **verbatim 派**（MemPalace）: 存原文，靠 chunking + scope filter + RAG 召回原话；号称 LongMemEval R@5 高 12.4pp

**SkillForge 当前是抽取派**，已建成 LLM extractor。verbatim 改造工程量极大（涉及消息行存储、chunking 策略、prompt 长度爆炸），**Memory v2 不切换派别**，但**借鉴 verbatim 派的 scope filter + 分层召回思想**。verbatim turn 引用展开为完整 retrieval 列入 §7 Future Work。

### 1.7 对 SkillForge 的取舍

外部项目可以拆成 4 类启发，但不能直接照搬。

| 来源 | 值得借鉴 | 不建议照搬 |
|---|---|---|
| **OpenClaw** | 短期 recall 信号、promotion threshold、加权和评分、dreaming 后台整理 | 文件型 `MEMORY.md` / daily note 结构不适合当前 DB schema |
| **Claude Code** | `lastMemoryMessageUuid` 游标、in-progress coalescing、trailing extraction、直接写 memory 时跳过后台抽取 | 文件读写 forked agent 不适合 SkillForge 当前 DB 抽取服务 |
| **Hermes-agent** | bounded curated memory、flush-before-compress、per-turn/provider lifecycle、context fencing | 整个 session JSON 重传给外部 provider 成本高，且不解决 SkillForge DB 状态淘汰 |
| **MemPalace** | L0/L1/L2/L3 progressive loading、wing/room scope filter、add-time semantic duplicate check | verbatim + ChromaDB 全量改造超出本期，且自动淘汰能力不足 |

因此 v2 的核心路线保持: **DB 抽取派 + `seq_no` 增量抽取 + L0/L1 分层召回 + embedding 去重 + 状态机淘汰**。

---

## 2. 设计目标与原则

### 2.1 目标

1. **修写入**: 长 session 持续触发抽取，不再终身锁
2. **修召回**: 修死代码 + 分层 progressive loading，按 task context 召回
3. **修淘汰**: stale 标签真过滤 + 状态机 + 物理删
4. **修去重**: add-time embedding 近邻判定，治本
5. **加 UI**: 用户可见 archived 列表 + 批量操作

### 2.2 原则

- **复用 schema 不重起炉灶**: `t_memory` 加列就够，不分多表
- **复用 LLM 调用不引入新依赖**: 已有 extractor 的 LLM call 一次，不加 add-time LLM judge（用 embedding 余弦替代）
- **渐进式 5 PR**: 每个 PR 独立可发布、独立可回滚
- **双轨保留**: A（被动塞）+ B/C（主动查）保留，但 A 改成轻量分层、B 加去重交集
- **Score 模型单点**: 召回排序 + 淘汰判定 + 容量驱逐共用同一套分数

### 2.3 反目标（不在本期）

- 不做 multi-user 隔离（`MemoryEntity.userId` 已有，多用户改造另说）
- 不做 verbatim 改造
- 不做 LLM-as-judge eviction（成本/复杂度过高，单机阶段过度）
- 不做 OpenClaw 六维加权（先三维够用，留升级口）
- 不做 cross-session memory linking（单条 memory 不引用其他 memory）

---

## 3. Memory v2 端到端方案

### 3.1 数据模型 v2 + V29 Flyway migration

```sql
-- V29__memory_v2_status_importance.sql

-- 1. 新增 status 状态机列
ALTER TABLE t_memory ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE t_memory ADD COLUMN archived_at TIMESTAMPTZ NULL;

-- 2. importance 从 tags 字符串抽出独立列
ALTER TABLE t_memory ADD COLUMN importance VARCHAR(8) NOT NULL DEFAULT 'medium';

-- 3. 缓存最近一次评分（用于淘汰排序，避免每次重算）
ALTER TABLE t_memory ADD COLUMN last_score DOUBLE PRECISION NULL;
ALTER TABLE t_memory ADD COLUMN last_scored_at TIMESTAMPTZ NULL;

-- 4. session 表加增量游标，治理 H-1
-- 复用 t_session_message.seq_no，类型必须与 SessionMessageEntity.seqNo(long) 对齐
ALTER TABLE t_session ADD COLUMN last_extracted_message_seq BIGINT NOT NULL DEFAULT 0;
-- digest_extracted_at 字段保留，但语义改为"最后一次抽取时间"
COMMENT ON COLUMN t_session.digest_extracted_at IS 'last extraction timestamp; null means never extracted';

-- 4.5 snapshot 表同步 v2 字段，保证 extraction batch rollback 不丢状态
ALTER TABLE t_memory_snapshot ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE t_memory_snapshot ADD COLUMN archived_at TIMESTAMPTZ NULL;
ALTER TABLE t_memory_snapshot ADD COLUMN importance VARCHAR(8) NOT NULL DEFAULT 'medium';
ALTER TABLE t_memory_snapshot ADD COLUMN last_score DOUBLE PRECISION NULL;
ALTER TABLE t_memory_snapshot ADD COLUMN last_scored_at TIMESTAMPTZ NULL;

-- 5. 复合索引：召回时按 user + status + score 排序
CREATE INDEX idx_memory_user_status_score
  ON t_memory(user_id, status, last_score DESC NULLS LAST);
CREATE INDEX idx_memory_archived_at
  ON t_memory(archived_at) WHERE status = 'ARCHIVED';

-- 6. 数据迁移：tags 里的状态/重要性升级到独立列
UPDATE t_memory SET status = 'STALE' WHERE tags LIKE '%stale%';
UPDATE t_memory SET importance = 'high' WHERE tags LIKE '%importance:high%';
UPDATE t_memory SET importance = 'medium' WHERE tags LIKE '%importance:medium%';
UPDATE t_memory SET importance = 'low' WHERE tags LIKE '%importance:low%';

-- 7. 状态值约束（PG + H2 兼容）
ALTER TABLE t_memory ADD CONSTRAINT chk_memory_status
  CHECK (status IN ('ACTIVE', 'STALE', 'ARCHIVED'));
ALTER TABLE t_memory ADD CONSTRAINT chk_memory_importance
  CHECK (importance IN ('high', 'medium', 'low'));
```

**MemoryEntity Java 字段补充**:
```java
@Column(length = 16, nullable = false)
private String status = "ACTIVE";

@Column(name = "archived_at")
private Instant archivedAt;

@Column(length = 8, nullable = false)
private String importance = "medium";

@Column(name = "last_score")
private Double lastScore;

@Column(name = "last_scored_at")
private Instant lastScoredAt;
```

**SessionEntity Java 字段补充**:
```java
@Column(name = "last_extracted_message_seq")
private long lastExtractedMessageSeq = 0L;
// digestExtractedAt 字段语义改：null=未抽过，非null=最后一次抽取时间，可重复更新
```

### 3.2 写入触发时机（治理 H-1）

**设计前提复盘**: SkillForge 主流用法是工作型 session（写代码 / 跑 agent 任务），头几轮交代上下文，后段绝大多数是技术执行，新偏好/新事实密度极低。"边聊边抽"（每 N 轮触发）会大量空跑 LLM。所以 v2 不做 turn-batch 高频抽，改用**保守的事件触发**：解决"终生锁"和"增量"，但不追求实时性。

**新模型: 三事件触发 + 增量游标 + 抽取冷却**

```
触发条件 (满足任一即考虑触发增量抽取):

1. session 关闭        ChatService loop 完成时 + runtime_status 切回 idle
                       (主路径，等同现在的 loop-end，但允许重复触发)

2. idle 静默 30 分钟    IdleSessionMemoryScanner @Scheduled(每 10 分钟扫一次)
                       条件: lastUserMessageAt < now - 30min
                            AND last_extracted_message_seq < latestSeq

3. 累计未抽 ≥ 30 turn   保险丝，防"一直聊一个 session 不关也不停" 的极端场景
                       loop-end 时检查: latestSeq - last_extracted_message_seq >= 30
                       触发后游标推进，再聊 30 轮再触发

外加守卫:
- 抽取冷却: 距上次成功抽取 < 5 分钟则跳过本次 (按 session 加 lock)
- 抽取空结果延长: LLM 返回 0 条 entry → 推进游标 + 把冷却拉长到 15 分钟
                  (说明这段没新事实，别再频繁试探)
- 增量游标: last_extracted_message_seq 单调递增，每次抽完同事务推进；LLM 失败不推进

抽取后:
  session.lastExtractedMessageSeq = latestSeq              (推进游标)
  session.digestExtractedAt = now                          (最后一次抽取时间，可重复更新)

兜底 daily cron (保留作为最后防线)
  扫 last_extracted_message_seq < latestSeq AND digestExtractedAt < now - 24h 的 session
```

**配置项**（`MemoryProperties` 扩展）:
```yaml
skillforge:
  memory:
    extraction:
      idle-window-minutes: 30                   # 静默 N 分钟兜底触发
      idle-scanner-interval-minutes: 10         # idle scanner 扫描频率
      max-unextracted-turns: 30                 # 累计未抽 turn 上限，防极长 session
      cooldown-minutes: 5                       # 两次抽取最小间隔（防 idle 与 close 抢跑）
      empty-result-cooldown-minutes: 15         # 抽取空结果时延长冷却
```

**抽取增量化**: `SessionDigestExtractor` 根据 `fromSeq` 从 `SessionMessageRepository` 读 `seq_no >= fromSeq` 的 NORMAL / 未 pruned 消息，组装 `List<Message>` 后传给 `LlmMemoryExtractor.extract`。conversation history prompt 只含增量部分，metadata 里额外写入 `fromSeq/toSeq` 便于排障。PR-3 需要补一个按 `sessionId + role=user + msgType=NORMAL + seqNo>cursor` 计数的 repository 方法，用于 max-unextracted-turns 判定。

**增量抽取的语境漏抽防护**: LLM 只看 fromSeq 之后的消息，可能漏掉前段交代过的偏好（如"我用 Python"在 turn 1，turn 20 抽取时看不到）。抽取 prompt 里把**该用户已存在的 ACTIVE memory（title + content 前 100 字）作为 existing context** 一并注入，让 LLM 能"接力判断"，避免上下文漂移。

**并发与幂等**:
- `triggerExtractionAsync(sessionId)` 内部按 sessionId 加 pessimistic lock（`findByIdForUpdate`），防 idle scanner 与 session-close 抢跑
- 游标推进必须在 memory 写入成功后同事务更新；LLM 失败时不推进，下一轮可重试
- 抽取结果为空时推进游标 + 拉长冷却，避免同一段空结果无限重试
- 冷却判定基于 `digestExtractedAt`：`digestExtractedAt + cooldownMinutes > now` 直接 return

**对长 session 的效果**（vs 原 turn-batch=5 方案 vs 现状）:

| 场景 | 现状 | turn-batch=5 (已废弃) | 朴素方案 (v2 final) |
|---|---|---|---|
| 短聊 3 轮关掉 | 抽 1 次 | 抽 1 次 | 抽 1 次 |
| 中聊 30 轮关掉 | 抽 1 次 (前段) | 抽 6 次 | **抽 1 次 (session 关闭)** |
| 一直聊 100 轮不关 | **抽 1 次,后 95 轮全废** | 抽 20 次 | **抽 3 次** (累计 30 轮触发 × 3) |
| 聊 4 轮去吃饭 30min 再回 | 抽 1 次 | idle 抽 1 次 | idle 抽 1 次 |
| 隔天回来同 session 再聊 10 轮再关 | **不抽** | 抽 2-3 次 | 抽 1 次 (close 触发) |

LLM 调用次数比原 turn-batch 方案砍 70-80%，比现状仅在"一直不关同一 session"和"重新打开 session"两个场景多调用，且都是真有新内容才调用。

### 3.3 写入抽取与去重

**抽取策略**: 保留 `LlmMemoryExtractor`（已成型，质量 OK），**不切换 verbatim 派**。

**add-time embedding 余弦去重（治理 H-5）**: `createMemoryIfNotDuplicate` 改造

```java
public DedupDecision createOrMerge(Long userId, ExtractedEntry entry) {
  // 1. 字面 title 去重保留（最快的 short-circuit）
  List<MemoryEntity> sameTitle = memoryRepository.findByUserIdAndTitle(userId, entry.title());
  if (!sameTitle.isEmpty()) return updateExisting(sameTitle.get(0), entry);

  // 2. 计算新条目 embedding（同步，~50ms）
  Optional<float[]> vec = embeddingService.embed(entry.title() + "\n" + entry.content());
  if (vec.isEmpty()) {
    // embedding 不可用，降级为字面去重 + 直接 ADD
    return createNew(userId, entry);
  }

  // 3. 找 ACTIVE 状态、同 type 的 top-3 近邻
  List<MemorySearchResult> neighbors = memoryService.searchByVector(userId, type, vec.get(), 3);

  // 4. 三档判定（无 LLM）
  if (!neighbors.isEmpty()) {
    double topSim = 1.0 - neighbors.get(0).distance();  // cosine similarity
    if (topSim >= 0.95) {
      return updateExisting(neighbors.get(0), entry);   // 视为同一事实
    }
    if (topSim >= 0.85) {
      return mergeInto(neighbors.get(0), entry);        // 内容追加 + importance 取 max
    }
  }
  return createNew(userId, entry);  // ADD
}
```

**MERGE 语义**:
- content 追加 `\n---\n[merged from "<old title>" at <ts>]\n<new content>`
- importance 取 max(old, new)
- recallCount / lastRecalledAt 保留旧值
- updatedAt = now，重新计算 embedding

**边界约束**:
- 近邻候选默认只在同 `type` 内比较；`preference` / `feedback` 不与 `knowledge` / `project` 合并
- `MERGE` 不删除新事实原文，只把双方内容保留在同一条 memory 中，降低误合并损失
- embedding 不可用时只执行 title 字面去重，行为退化为当前实现

**预算**: 每条新 entry 多 1 次 embedding API call，无 LLM call。每个 session 抽 1-8 条 → 多 1-8 次 embedding，可控。

### 3.4 召回路径分层（治理 H-2 / H-3）

**新模型: 借鉴 MemPalace L0/L1/L3，但落到 SkillForge 的 type 维度**

```
L0 (恒定塞，强约束行为):
  - preference 全量 (cap 10) + feedback 全量 (cap 10)
  - 这两类影响 agent 行为，不应该按 task 召回，强制塞
  - 预算: ~2KB (20 条 × 100 字)

L1 (task-context-aware 塞):
  - knowledge / project / reference 按 hybrid score 取 top-K
  - 修 H-2: memoryProvider 改 BiFunction(userId, taskContext)，
           用当前 user message 做 task context
  - hybrid: FTS(15) + Vector(15) → RRF → score-based filter → top-K (default 8)
  - 预算: ~4KB (8 条 × 500 字)

L3 (按需主动查):
  - agent 调 memory_search Tool（已有，加 status='ACTIVE' 过滤）
  - 修 H-3: 排除 L0 + L1 已塞的 memoryIds，避免重复返回

总 prompt 注入预算: L0 + L1 = 6KB (vs 当前 8KB)，更精准
```

**`AgentLoopEngine` 改造**:
```java
// 旧: java.util.function.Function<Long, String> memoryProvider;
// 新: BiFunction<Long, String, MemoryInjection> memoryProvider;

// 调用点（旧 :276-281）改造:
String taskContext = userMessage; // AgentLoopEngine.run(...) 入参已是当前用户消息
MemoryInjection mi = memoryProvider.apply(userId, taskContext);
if (mi != null && mi.text() != null && !mi.text().isBlank()) {
  systemPrompt = systemPrompt + "\n\n## User Memories\n\n" + mi.text();
  loopCtx.setInjectedMemoryIds(mi.injectedIds());  // L3 排重用
}
```

**`MemoryService` 改造**:
```java
public record MemoryInjection(String text, Set<Long> injectedIds) {}

public MemoryInjection getMemoriesForPrompt(Long userId, String taskContext) {
  // L0: preference + feedback 全量（不依赖 taskContext）
  // L1: knowledge/project/reference 按 hybrid score
  // 全程 status='ACTIVE' 过滤（治理 H-4）
  // 返回文本 + 注入 ids
}
```

**`MemorySearchTool` 改造**（治理 H-3 重复 + H-4 stale）:
```java
public SkillResult execute(Map<String, Object> input, SkillContext context) {
  Set<Long> excludeIds = context.getInjectedMemoryIds();  // 排除 L0+L1 已塞
  // searchByFts 和 searchByVector 都加 status='ACTIVE' WHERE 子句
  // RRF 后过滤掉 excludeIds
}
```

**core/server 边界注意**: 当前 `SkillContext` 只有 workingDirectory / sessionId / userId，尚无 `injectedMemoryIds`。PR-2 需要同步改 `LoopContext -> SkillContext` 的 tool execution 上下文传递；如果为了降低首个 PR 风险，也可以先只做 status='ACTIVE' + taskContext-aware 召回，把 excludeIds 排重放到 PR-2b。

**Repository SQL 加状态过滤**:
```java
@Query(value = """
    SELECT id, type, title, content, tags, recall_count,
           ts_rank(search_vector, plainto_tsquery('simple', :query)) AS rank
    FROM t_memory
    WHERE user_id = :userId
      AND status = 'ACTIVE'                         -- 加这一行
      AND search_vector @@ plainto_tsquery('simple', :query)
    ORDER BY rank DESC
    LIMIT :limit
    """, nativeQuery = true)
List<Object[]> findByFts(...);

// findByVector 同款加 status='ACTIVE'
```

### 3.5 状态机与淘汰（治理 H-4）

**三状态 + 三段时间线**:

```
ACTIVE (默认)
  ↓ 30 天没召回 + recallCount<3 + score < 0.3
STALE   (不再进 prompt 注入和 memory_search 结果，但 DB 保留)
  ↓ 60 天还没复活
ARCHIVED   (前端显示在 archived tab，可一键 unarchive 复活)
  ↓ 90 天
物理删   (snapshot 已存在，可 batch rollback 在 90 天内复活)
```

**状态切换**:

```java
@Component
public class MemoryEvictionService {

  // 现有 MemoryConsolidator.consolidate 改造为这套逻辑
  @Transactional
  public void runEviction(Long userId) {
    Instant now = Instant.now();
    List<MemoryEntity> all = memoryRepository.findByUserId(userId);

    for (MemoryEntity m : all) {
      double score = scoreMemory(m, now);
      m.setLastScore(score);
      m.setLastScoredAt(now);

      switch (m.getStatus()) {
        case "ACTIVE":
          if (isStaleCandidate(m, now, score)) {
            m.setStatus("STALE");
          }
          break;
        case "STALE":
          if (wasRecentlyRecalled(m, now)) {
            m.setStatus("ACTIVE");  // 复活
          } else if (isArchiveCandidate(m, now)) {
            m.setStatus("ARCHIVED");
            m.setArchivedAt(now);
          }
          break;
        case "ARCHIVED":
          if (m.getArchivedAt().isBefore(now.minus(90, DAYS))) {
            memoryRepository.delete(m);  // 物理删，snapshot 表已留
          }
          break;
      }
    }
    memoryRepository.saveAll(/* changed */);
  }

  // 容量上限驱逐
  @Transactional
  public void enforceCapacity(Long userId) {
    long activeCount = memoryRepository.countByUserIdAndStatus(userId, "ACTIVE");
    if (activeCount > MAX_ACTIVE_PER_USER /* 1500 */) {
      int excess = (int) (activeCount - MAX_ACTIVE_PER_USER);
      // 按 score 升序取最低 excess 条 → 标 STALE
      List<MemoryEntity> losers = memoryRepository.findLowestScoreActive(userId, excess);
      losers.forEach(m -> m.setStatus("STALE"));
      memoryRepository.saveAll(losers);
    }
  }
}
```

**触发时机**:
1. `MemoryConsolidator.consolidate(userId)` 已经在 daily 3am cron + 每次 extract 后调用 → 改造为 `evictionService.runEviction()` + `enforceCapacity()`
2. 不增加新的 @Scheduled

### 3.6 评分模型

**三维加权和（借鉴 OpenClaw 的 additive scoring，但先保留三维）**:

```java
double scoreMemory(MemoryEntity m, Instant now) {
  // 1. importance 基础分
  double importanceScore = switch (m.getImportance()) {
    case "high"   -> 1.0;
    case "medium" -> 0.6;
    case "low"    -> 0.3;
    default       -> 0.6;
  };

  // 2. recency decay (半衰期 30 天)
  long ageDays = ChronoUnit.DAYS.between(
    m.getLastRecalledAt() != null
      ? m.getLastRecalledAt()
      : m.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
    now);
  double recencyScore = Math.exp(-ageDays / 30.0);  // 30 天后降到 ~0.37

  // 3. recall frequency (log scale 防爆)
  double recallScore = Math.log(1 + m.getRecallCount()) / Math.log(11);  // 0-1 normalize at recallCount=10

  double freshnessScore = 0.5 + 0.5 * recencyScore;       // 0.5-1.0
  double usageScore = 0.3 + 0.7 * recallScore;            // 0.3-1.0，冷启动不归零

  return 0.45 * importanceScore
       + 0.35 * freshnessScore
       + 0.20 * usageScore;
}
```

**为什么不用纯乘法**: 纯 `importance × recency × recall` 会让新记忆在 `recallCount=0` 时直接变成 0，刚写入就可能被低分淘汰。加权和与 OpenClaw promotion score 更一致，也更容易解释和调参。

**用途**:
- L1 召回排序（hybrid score 后再乘 lastScore，让高价值条目优先）
- 淘汰判定（score < 0.3 + 30 天没召回 → STALE）
- 容量驱逐（按 lastScore 升序淘汰）
- 前端 /memories 排序

### 3.7 前端 UI 增强

**MemoryList.tsx 扩展**:
- 顶部 tab: `Active` (default) / `Stale` / `Archived`
- 列表头加 sort by score / recallCount / updatedAt
- 行内显示 status 徽章 + importance 徽章 + score
- 批量勾选 + 批量操作: archive / unarchive / delete / mark stale / mark active
- archived tab 行内 "Restore" 按钮（status → ACTIVE，archived_at → null）

**MemoryController 新增 endpoint**:
```
PATCH /api/memories/{id}/status       body: {status: "STALE|ACTIVE|ARCHIVED"}
POST  /api/memories/batch-archive     body: {ids: [1,2,3]}
POST  /api/memories/batch-restore     body: {ids: [1,2,3]}
DELETE /api/memories/batch            body: {ids: [1,2,3]}
GET   /api/memories?status=ARCHIVED   filter
GET   /api/memories/stats             返回 {active: N, stale: M, archived: K, capacityCap: 1500}
```

**容量"快满"提示**（学 ChatGPT）: dashboard 顶部 banner，active 数 > 80% capacity 时显示 "Memory near full (X / 1500). [清理]"

---

## 4. 与现状的 Diff

| 模块 | 现状 | Memory v2 | 类型 |
|---|---|---|---|
| **t_memory schema** | id, userId, type, title, content, tags, extractionBatchId, recallCount, lastRecalledAt, embedding, search_vector | 加 status / archived_at / importance / last_score / last_scored_at | 🟢 补充 |
| **t_session schema** | digest_extracted_at (boolean lock 用) | 加 `last_extracted_message_seq BIGINT`；digest_extracted_at 语义改时间戳 | 🟡 改 |
| **写入触发** | session 终生 1 次（首轮 loop 完成）| 三事件触发 (session 关闭 / idle 30min / 累计 30 turn) + 5min 抽取冷却 + 空结果 15min 延长冷却 + 增量游标 | 🔴 替换 |
| **LlmMemoryExtractor** | 输入完整对话 8KB | 输入增量对话（fromSeq 之后）| 🟡 改 |
| **createMemoryIfNotDuplicate** | 仅 title 字面去重 | embedding 余弦三档（≥0.95 UPDATE / 0.85-0.95 MERGE / <0.85 ADD）| 🔴 替换 |
| **MemoryConsolidator** | tags += "stale" 标了不用 | 改造为 MemoryEvictionService，操作 status 列 + 容量驱逐 | 🔴 替换 |
| **memoryProvider injection** | Function<Long,String>，taskContext=null | BiFunction<Long,String,MemoryInjection>，传 latest user message | 🔴 替换 |
| **getMemoriesForPrompt** | preference 10 + feedback 10 + KPR 10 时间序硬塞 | L0(preference+feedback 全量) + L1(KPR top-K by hybrid+score, taskContext-aware) | 🟡 改 |
| **memory_search Tool** | FTS+Vector RRF 无 status 过滤 | + status='ACTIVE' + 排除已注入 ids | 🟡 改 |
| **memory_detail Tool** | findById | 同（status 过滤可加可不加，detail 是显式查）| ✅ 不动 |
| **MemoryRepository.findByFts/findByVector** | 无 status WHERE | + AND status='ACTIVE' | 🟡 改 |
| **importance 字段** | 塞 tags 字符串 `importance:high` | 独立列，3 enum | 🟡 改 |
| **状态机** | 无 | ACTIVE → STALE → ARCHIVED → 物理删 | 🟢 补充 |
| **容量上限** | 无 | 1500 条/用户（可配）| 🟢 补充 |
| **MemorySnapshotEntity** | 仅 batch rollback 用，字段少于 MemoryEntity | 同步新增 status / archived_at / importance / last_score / last_scored_at，保证 rollback 不丢状态 | 🟡 改 |
| **前端 MemoryList.tsx** | 列表 + 单删 + 编辑 | + status tabs + 批量操作 + restore + 容量 banner | 🟡 改 |
| **MemoryController** | CRUD + rollback + refresh | + batch-archive / batch-restore / batch-delete / stats | 🟢 补充 |
| **EmbeddingService** | OpenAI embeddings | 不动（add-time dedup 复用现有）| ✅ 不动 |
| **daily cron** | extractDailyMemories 兜底 | 改为 idle-window scanner 替代（保留兼容期）| 🟡 改 |
| **测试** | MemoryServiceTest 覆盖 batch/rollback；renderMemoriesForPrompt / FTS / Vector / mergeWithRrf 全无单测 | 补 MemoryConsolidatorTest / MemoryEvictionServiceTest / DedupDecisionTest / hybrid+score IT | 🔴 必补 |

**汇总**:
- 🔴 替换 5 处（写入触发、dedup、consolidator、memoryProvider、prompt 注入）
- 🟡 改 8 处（schema、extractor、search/repo SQL、importance、UI、controller、cron）
- 🟢 补充 4 处（status 列、状态机、容量上限、batch API）
- ✅ 不动 2 处（detail tool、embedding）

---

## 5. 工程拆分（5 个 PR）

> 每个 PR 独立可发布、独立可回滚。**强制走 Full Pipeline**（触碰 `MemoryService` / `AgentLoopEngine` / `LlmMemoryExtractor` / `CompactionService` 都在核心文件清单上下游）。

### PR-1: schema + 测试基线（前置必做，2 天）

- V29 migration 上线
- `MemoryEntity` 加字段（status / importance / archived_at / last_score / last_scored_at）
- `SessionEntity` 加 `last_extracted_message_seq BIGINT/long`，复用 `t_session_message.seq_no`
- `MemorySnapshotEntity` / `t_memory_snapshot` 同步新增 v2 字段，`rollbackExtractionBatch` restore 时一起恢复
- 数据迁移 SQL：tags 里的 stale → status='STALE'，importance:* → importance 列
- **补测试**: `MemoryConsolidatorTest`（去重 + stale 标记完整覆盖）、`MemoryServiceTest` 扩展 `renderMemoriesForPromptTest` / `mergeWithRrfTest` / `searchByFts/Vector` 集成测试
- **不动业务逻辑**，只加列 + 加测试 + 数据迁移
- 验证：现有功能完全不变（status 默认 ACTIVE，所有路径行为不变）

### PR-2: 召回路径修复（治理 H-2 / H-3 / H-4，3 天）

- `AgentLoopEngine.memoryProvider` 改 `BiFunction<Long, String, MemoryInjection>`，第二参直接传当前 `userMessage`
- `MemoryService.getMemoriesForPrompt(userId, taskContext)` 实现 L0/L1 分层
- 所有 Repository SQL 加 `AND status='ACTIVE'`
- `LoopContext` 加 `injectedMemoryIds` 字段，并将其带入 `SkillContext` 或 tool execution metadata
- `MemorySearchTool` 加 `excludeIds` + status 过滤；如 core 层改造风险过大，可先落 status/taskContext，排重作为 PR-2b
- 验证：browser e2e 真实 chat，看 prompt token 是否变化（应降）；调 memory_search 看是否排除已塞条目

### PR-3: 写入触发改造（治理 H-1，3 天）

- `SessionDigestExtractor.triggerExtractionAsync` 改增量：增加 `fromSeq` 参数，使用 `SessionMessageRepository` 按 `seq_no` 读增量消息
- `LlmMemoryExtractor.extract` 接受增量 messages + `fromSeq/toSeq` metadata；prompt 里把 existing ACTIVE memory（title + content 前 100 字）作为 context 注入，防语境漂移
- `digestExtractedAt` 语义改：不再是终生锁，每次抽完更新时间戳；冷却判定基于 `digestExtractedAt + cooldownMinutes > now`
- `ChatService` loop 完成时只判断 cooldown 后直接触发；新增 `max-unextracted-turns` 检查作为极长 session 保险丝
- 同 session 加 pessimistic lock，避免 idle scanner 与 session close 抢跑
- 新增 `IdleSessionMemoryScanner` @Scheduled（10 分钟扫一次，30 分钟 idle 阈值）
- `MemoryProperties` 加配置项: idle-window-minutes / idle-scanner-interval-minutes / max-unextracted-turns / cooldown-minutes / empty-result-cooldown-minutes
- 抽取空结果时推进游标 + 延长冷却到 15 分钟，避免同段空结果反复试探
- 验证：单元测试覆盖游标推进 / 冷却拦截 / 空结果延长；集成测试模拟"持续聊不关同 session" 验证只在累计 30 轮时触发；隔天回流场景验证 close 重新触发抽取

### PR-4: add-time dedup（治理 H-5，2 天）

- `MemoryService.createMemoryIfNotDuplicate` 改造为 `createOrMerge` 三档判定，近邻候选默认限定同 type + ACTIVE
- 实现 MERGE 语义（content append + importance 取 max + 重新 embedding）
- 配置项 `memory.dedup.cosine-update-threshold` (0.95) / `cosine-merge-threshold` (0.85)
- 验证：单测覆盖三档分支；冷启动（无 embedding）降级到 ADD 路径

### PR-5: 状态机 + 淘汰 + UI（3 天）

- `MemoryConsolidator` 改造为 `MemoryEvictionService`：实现 `runEviction` + `enforceCapacity`
- 评分模型（三维加权 + 冷启动起步分）
- `MemoryController` 新增 batch-archive / batch-restore / batch-delete / stats / status filter
- `MemoryList.tsx` 加 tabs + 批量操作 + restore + 容量 banner
- 验证：单测覆盖状态切换；集成测试验证 30/60/90 时间线；前端 e2e 验证 archive → restore 闭环

**总工期**: 13 天纯开发 + Full Pipeline overhead ≈ **15-18 天**

---

## 6. Final Decisions（已锁定 2026-04-27）

| ID | 决策 | 选项 | 配置/参数 |
|---|---|---|---|
| D1 | max-unextracted-turns（极长 session 保险丝）| **A** | 30 turn |
| D2 | idle-window 静默触发 | **A** | 30 分钟 |
| D2b | 抽取冷却 / 空结果延长 | **A** | 5 分钟 / 15 分钟 |
| D3 | dedup 余弦阈值 | **A** | UPDATE ≥ 0.95 / MERGE ≥ 0.85 |
| D4 | 评分模型 | **A** | 三维加权和（importance + recency + recallCount） |
| D5 | 状态时间线 | **A** | 30 天 STALE / 60 天 ARCHIVED / 90 天物理删 |
| D6 | active 容量上限 | **A** | 1500 条/用户 |
| D7 | L0+L1 prompt 注入预算 | **A** | L0 2KB + L1 4KB = 6KB total |
| D8 | 前端 archived tab | **A** | 暴露 + 一键 restore |
| D9 | PR 顺序 | **B** | PR-1 schema → PR-2 召回 → PR-3 触发 → PR-4 dedup → PR-5 状态机+UI |
| D10 | H-2 死代码 80 行 | **A** | PR-2 顺手删除 |

**最终配置块**（合并写入 `application.yml` 的 `skillforge.memory.*`）:
```yaml
skillforge:
  memory:
    extraction:
      idle-window-minutes: 30
      idle-scanner-interval-minutes: 10
      max-unextracted-turns: 30
      cooldown-minutes: 5
      empty-result-cooldown-minutes: 15
    dedup:
      cosine-update-threshold: 0.95
      cosine-merge-threshold: 0.85
    eviction:
      stale-after-days: 30
      archive-after-days: 60
      delete-after-days: 90
      max-active-per-user: 1500
    injection:
      l0-budget-bytes: 2048
      l1-budget-bytes: 4096
      l1-top-k: 8
```

**PR 执行顺序**（D9=B）:

```
PR-1 schema + 测试基线（前置必做）
   ↓
PR-2 召回路径修复（H-2/H-3/H-4，用户感知最强，先改）+ 顺手删 80 行死代码（D10）
   ↓
PR-3 写入触发改造（H-1，朴素事件触发）
   ↓
PR-4 add-time dedup（H-5）
   ↓
PR-5 状态机 + 淘汰 + UI
```

---

## 7. Future Work（不在本期，决策完再评估）

| 项 | 触发条件 |
|---|---|
| **verbatim turn 引用展开**（学 MemPalace） | 实测 R@5 召回率劣化 ≥10pp 时启动 |
| **OpenClaw 六维加权评分** | active memory 数 > 5000 / 用户时升级 |
| **LLM-as-judge eviction**（学 Mem0） | trust_score 反馈环不够时 |
| **multi-user 隔离 redesign** | 多用户上线 design doc 落地后 |
| **memory linking / knowledge graph** | 业务需要追溯链时 |
| **trust_score 不对称更新**（学 Hermes） | 用户主动 thumbs up/down 反馈渠道建好后 |
| **跨 session memory dedup**（不同 session 抽出相同事实）| add-time embedding dedup 仍漏掉 ≥30% 重复时 |
| **/memories 页面性能优化**（>1000 条时虚拟滚动）| 容量上限提到 5000 时 |

---

## 8. 验证策略

**功能验证**:
1. browser e2e: 模拟一个长 session 聊 20 轮，确认每 5 轮触发一次抽取，且每次抽取都是增量
2. browser e2e: 隔天再聊同一 session，确认能继续抽
3. curl 测试: `/api/memories/stats` 返回 active/stale/archived 计数
4. curl 测试: 手动调 batch-archive 一批，再 batch-restore
5. browser e2e: archived tab 能看到归档的 memory + 一键 restore

**性能验证**:
- prompt 注入 token 数对比（v1: ~8KB / v2: ~6KB），用 ContextBreakdownService 量
- 抽取频率成本：每 5 turn 1 次 vs 每 session 1 次，LLM 调用次数估算
- DB 查询性能：`findByFts` 加 status WHERE 后 EXPLAIN ANALYZE

**回归验证**:
- 现有 397 non-IT server tests 全绿
- 新增测试 ≥ 30 个（dedup / eviction / scoring / cursor / capacity）

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 增量抽取漏消息（cursor 推进 bug）| 部分对话进不去 memory | PR-1 测试基线先把 cursor 单测覆盖到 100% |
| dedup 误合并（cosine 0.85-0.95 区间假阳性）| 不同事实被合并丢失 | MERGE 语义保留双方原文（content append），不删除原条；前端 UI 让用户手动拆分 |
| 长 session 抽取频率过高烧 token | LLM 成本上升 | 朴素事件触发已规避（仅 close / idle 30min / 累计 30 turn 三事件）；空结果延长冷却到 15 分钟 |
| dedup 漏网导致"近似重复变体"长期堆积 | cosine <0.85 但语义高度相似的事实会被判 ADD，半年后同一偏好可能存在多个变体 | 容量上限 + 状态机淘汰兜底；前端 archived tab 让用户手动合并；后续可按需引入 LLM-as-judge dedup（Future Work）|
| 增量抽取看不到前段交代的偏好 | LLM 误判语境绑定的发言为独立事实 | 抽取 prompt 注入 existing ACTIVE memory 作为 context；现有 dedup 把"重复抽出的旧偏好"挡在 cosine ≥0.95 UPDATE 路径 |
| status 迁移把活跃记忆误标 STALE | 召回率短期下降 | V29 migration 只迁移 tags 已有 stale 的；不重新评估所有记忆 |
| 前端 batch 操作误删 | 用户记忆丢失 | 物理删走 90 天 ARCHIVED 缓冲；snapshot 表支持 batch rollback |
| BiFunction memoryProvider 改造破坏现有调用方 | 编译失败 | 保留 Function 兼容签名，BiFunction 是新接口 |
| snapshot 未同步 v2 字段 | batch rollback 后 status / importance / score 丢失 | PR-1 同步改 `MemorySnapshotEntity`、snapshot SQL、restoreFromSnapshot |
| `MemorySearchTool` 排重跨 core/server 边界 | PR-2 diff 扩大，可能影响所有 tool 执行 | `LoopContext` 字段只读透传到 `SkillContext`；必要时拆成 PR-2b |
| idle scanner 与 loop-end 抽取并发 | 同一段消息重复抽或游标倒退 | 按 session 加 pessimistic lock / extraction lock；游标只允许单调递增 |

---

## 10. 决策已锁定 → 进入开发（2026-04-27）

决策已落 §6 Final Decisions。开发顺序：PR-1 → PR-2 → PR-3 → PR-4 → PR-5（D9=B）。
所有 PR 强制 Full Pipeline（触碰核心文件清单成员：`MemoryService` / `AgentLoopEngine` / `LlmMemoryExtractor` / `SessionService` / Flyway migration）。
排期入口在 [todo.md](../../../todo.md) 的 Sprint 池。

---

## 附录 A: 改动文件清单（猜测）

### 后端

- `skillforge-server/src/main/resources/db/migration/V29__memory_v2_status_importance.sql` (新增)
- `skillforge-server/src/main/java/com/skillforge/server/entity/MemoryEntity.java` (改)
- `skillforge-server/src/main/java/com/skillforge/server/entity/SessionEntity.java` (改)
- `skillforge-server/src/main/java/com/skillforge/server/repository/MemoryRepository.java` (改 SQL + 加 query)
- `skillforge-server/src/main/java/com/skillforge/server/service/MemoryService.java` (改 dedup + getMemoriesForPrompt)
- `skillforge-server/src/main/java/com/skillforge/server/service/MemoryEvictionService.java` (新增，承接 MemoryConsolidator 角色)
- `skillforge-server/src/main/java/com/skillforge/server/memory/MemoryConsolidator.java` (改造或删除)
- `skillforge-server/src/main/java/com/skillforge/server/memory/SessionDigestExtractor.java` (改触发逻辑)
- `skillforge-server/src/main/java/com/skillforge/server/memory/LlmMemoryExtractor.java` (改增量抽取)
- `skillforge-server/src/main/java/com/skillforge/server/memory/IdleSessionMemoryScanner.java` (新增)
- `skillforge-server/src/main/java/com/skillforge/server/service/ChatService.java` (改触发逻辑)
- `skillforge-server/src/main/java/com/skillforge/server/controller/MemoryController.java` (新增 batch endpoints)
- `skillforge-server/src/main/java/com/skillforge/server/config/MemoryProperties.java` (扩展配置)
- `skillforge-server/src/main/java/com/skillforge/server/config/SkillForgeConfig.java` (改 memoryProvider 注册)
- `skillforge-core/src/main/java/com/skillforge/core/engine/AgentLoopEngine.java` (改 memoryProvider 签名)
- `skillforge-core/src/main/java/com/skillforge/core/engine/LoopContext.java` (加 injectedMemoryIds)
- `skillforge-server/src/main/java/com/skillforge/server/tool/MemorySearchTool.java` (加 status + excludeIds)

### 前端

- `skillforge-dashboard/src/pages/MemoryList.tsx` (改 + 批量操作)
- `skillforge-dashboard/src/api/memory.ts` (新增 batch APIs)
- `skillforge-dashboard/src/components/memory/MemoryStatusBadge.tsx` (新增)
- `skillforge-dashboard/src/components/memory/MemoryCapacityBanner.tsx` (新增)

### 测试

- `skillforge-server/src/test/java/com/skillforge/server/service/MemoryEvictionServiceTest.java` (新增)
- `skillforge-server/src/test/java/com/skillforge/server/memory/MemoryConsolidatorTest.java` (新增/扩展)
- `skillforge-server/src/test/java/com/skillforge/server/service/MemoryServiceDedupTest.java` (新增)
- `skillforge-server/src/test/java/com/skillforge/server/service/MemoryServiceL0L1Test.java` (新增)
- `skillforge-server/src/test/java/com/skillforge/server/memory/SessionDigestExtractorIncrementalTest.java` (新增)
- `skillforge-server/src/test/java/com/skillforge/server/memory/IdleSessionMemoryScannerTest.java` (新增)
- 现有 `MemoryServiceTest` 扩展 renderMemoriesForPrompt / mergeWithRrf / hybrid search 覆盖

---

**Doc 结束。等待 §6 决策。**
