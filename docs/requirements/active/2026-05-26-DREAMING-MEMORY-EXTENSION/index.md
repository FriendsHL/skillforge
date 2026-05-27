# DREAMING-MEMORY-EXTENSION — `LlmMemorySynthesizer` 从 memory→memory 升级到 memory + sessions[] → memory

> 2026-05-27 implementation update: V1 实施路线已调整为 `memory-curator` dogfood + transcript tool + `t_memory_proposal.evidence_json` + downstream memory context injection。此前以 legacy `LlmMemorySynthesizer.synthesize(clusters, sessions, instructions)` 为中心的设计不再作为 V1 主路，因为当前生产 run-once 已优先触发 `memory-curator` ScheduledTask。具体执行计划见 [`docs/superpowers/plans/2026-05-27-dreaming-memory-integration.md`](../../../superpowers/plans/2026-05-27-dreaming-memory-integration.md)。

> 创建：2026-05-26
> 状态：**prd-draft**（PRD 已草拟，tech-design 草稿已下；开 Plan pipeline 时 ratify D1-D6 决策 + Q1-Q5 澄清）
> 模式：Full pipeline（触红灯 `MemoryClusterer` / `LlmMemorySynthesizer` 子系统 + 新增 2 张 Flyway migration + 新增 1 个 entity + 新增 1 个 class + 协议级 invariant：immutable memory input）
> 触发：用户 2026-05-26 让评估 Anthropic Managed Agents **Dreaming**（Research Preview，beta header `dreaming-2026-04-21`）对 SkillForge 借鉴价值；明确要求 Dreaming 跟 Outcomes 分开成独立需求包

## 阅读顺序

1. 当前 `index.md`（本文）— 摘要 + 状态 + sprint 划分
2. [`mrd.md`](mrd.md) — 用户原话 + 调研来源 + 5 痛点 + Q1-Q5 未澄清
3. [`prd.md`](prd.md) — 目标 / 非目标 / 工作流 / FR-1 ~ FR-4 / AC-1 ~ AC-7
4. [`tech-design.md`](tech-design.md) — 8 条 hotspot + 4 phase 实现拆分 + 风险 + 测试计划
5. **[`spike-evidence.md`](spike-evidence.md) — R-AMA-MEM-5 假设的 spike 验证证据（2026-05-27 跑 user_id=1 真活 dogfood，7 条 actionable observation，假设 ✅ 成立，进 MVP V1 有 ROI 支撑）**

## 调研来源

- **wiki** [agent-harness-wiki/harness/anthropic-managed-agents.md](../../../../../research-docs/research/agent-harness-wiki/harness/anthropic-managed-agents.md) — 5-26 B 模式 source-grounded ingest，700 行，13 维 schema，validation=production，**关 Dreaming 的 R-AMA-MEM-1~5 共 5 条建议**
- **wiki** [agent-harness-wiki/harness/skillforge.md](../../../../../research-docs/research/agent-harness-wiki/harness/skillforge.md) — 5-25 B 模式自家项目 ingest，1135 行，**ground truth for "SkillForge 已有什么"**
- **claude-code-guide Dreaming deep dive**（2026-05-26 晚）— `POST /v1/dreams` API 协议级 schema + `dream.session_id` stream 业内独家 meta-observability + immutable input Iron Law + cost 估算（结果落到 [tech-design.md](tech-design.md)）

## 6 个 ratify 决策（开 Plan 时再次确认 + 细化）

| # | 决策 | 选 |
|---|---|---|
| **D1** | 范围 = **Dreaming only** | Outcomes 拆到独立包 [`backlog/OUTCOMES-RUBRIC-FOUNDATION/`](../../backlog/OUTCOMES-RUBRIC-FOUNDATION/)（等 Dreaming V1 ship 后用户决定是否升 active）；Multiagent 完全不做（wiki 已结论 `MAX_DEPTH=3` 路线更深） |
| **D2** | V1/V2 切分 | V1 = Phase 1 F2/F3/F4 + Phase 2 M1-M4 + Phase 6 T1（基础设施 + 能力扩展 + IT）；V2 留 rollback REST API + dream session 可 stream meta-observability + Thread pool 隔离落地 |
| **D3** | M1 backward compat | 加 1-arg overload `synthesize(clusters)` 转发到 3-arg `synthesize(clusters, [], null)` — 旧 caller 零改动 ship |
| **D4** | M3 超 budget 行为 | 抛 `MemoryStoreTooLargeException`，**不静默截断 single session** — 跟 Anthropic Dreaming `input_memory_store_too_large` 语义一致 |
| **D5** | F2 store-level snapshot | V1 只建 entity 落跑批前 snapshot 行，**不做 rollback REST API**（rollback controller 留 V2）— 让 V1 ship 先有 audit log 数据 |
| **D6** | F4 audit MemorySynthesisExecutor 隔离（R-AMA-MEM-4） | V1 只 audit + 写报告 verdict，**不主动隔离 thread pool**；V2 看 audit 结果（✅ 不抢资源 / ⚠️ 偶尔抢 / ❌ 确认抢）决定 落地 `MemorySynthesisExecutor` |

> **说明**：D1 把 Outcomes 拆出去是用户明确要求 — Dreaming 触碰 Memory 子系统，Outcomes 触碰 Eval / AgentLoopEngine 子系统，本就属不同 surface，独立 ship 风险更隔离。

## 核心交付（V1，待开工时细化）

参见 [`prd.md`](prd.md) 验收点 + [`tech-design.md`](tech-design.md) 实现拆分。简略：

- **Phase 1 — 基础设施**（XS / 无依赖）
  - F2 `t_memory_store_snapshot` entity + Flyway V119
  - F3 `LlmSpanEntity.span_kind` 扩 4-enum 加 `MEMORY_SYNTHESIS`
  - F4 Audit-Mem-1 verify `MemorySynthesisExecutor` 是否真跟 production loop 抢资源（pure docs，无代码）

- **Phase 2 — Memory 能力扩展**（M / 触红灯 `MemoryClusterer` 子系统）
  - M1 `LlmMemorySynthesizer.synthesize()` 加 `sessions: List<SessionId>` + `instructions: String`（含 1-arg backward-compat overload）
  - M2 新 `SessionTranscriptProvider`（从 `t_session_event` 拼接，role filter / chunk size / overlap 走 `@ConfigurationProperties`）
  - M3 `TokenEstimator` per-session 截断 + 全局 cap + `MemoryStoreTooLargeException`
  - M4 Prompt 重写：`<memory_clusters>` + `<sessions>` + `<instructions>` 三 slot + prompt-snapshot 测试防字节漂移

- **Phase 6 — 测试**
  - T1 `MemorySynthesisIT` 5 case（含 backward-compat / immutable input verify / token budget overflow）
  - T2 现有 `MemoryClustererTest` / `LlmMemorySynthesizerTest` backfill 新签名调用

## V2 包（V1 dogfood 1 周后判断是否启动）

- **rollback REST API**：`POST /api/memory-stores/:owner/rollback?to=:snapshot_id`（V1 已落 entity，V2 加 controller + service）
- **`MemorySynthesisExecutor` 独立 thread pool**（依 V1 F4 audit verdict 决定）
- **Dream Session Meta-Observability**：让 `LlmMemorySynthesizer.synthesize()` 跑批自身成为可 stream session（参考 Anthropic `dream.session_id` 设计），扩 `LlmTraceEntity` 跟 WebSocket relay 让 operator 看 synthesis 实时读/写过程

## 验收点（V1，待细化）

- **AC-1**: Phase 1 F2 — `t_memory_store_snapshot` 表能写能查；M1 跑批前 INSERT 一行 snapshot（`content_hash` 非空 SHA256）
- **AC-2**: Phase 1 F3 — 每次 M1 跑批 emit 1 个 `LlmSpanEntity(span_kind=MEMORY_SYNTHESIS)`，含 input cluster count / session count / instructions hash / output proposal count / duration / cost
- **AC-3**: Phase 1 F4 — Audit 报告落到 `docs/requirements/active/2026-05-26-DREAMING-MEMORY-EXTENSION/audit-mem-1-executor-isolation.md`，verdict 三选一（✅ / ⚠️ / ❌）
- **AC-4**: Phase 2 M1-M4 — `LlmMemorySynthesizer.synthesize(clusters, sessions: List<SessionId>, instructions: String)` 跑批后能从 session transcript 挖出**至少 1 条新 memory observation**（dogfood：拿一个 5+ active session 但 0 reflection 的 agent 跑批，验 `t_memory` +1 行）
- **AC-5**: Phase 2 M1 — Immutable input 验证：跑批前后对比 `MemoryEntity` 表 + 原 sessions 内容**字节不变**（IT 断言）
- **AC-6**: Phase 2 M3 — 超 token cap 抛 `MemoryStoreTooLargeException`（不静默截断 single session），DB 无副作用（snapshot 也不写）
- **AC-7**: Phase 6 T1 — IT 至少 5 case 覆盖（见 [`prd.md` AC-7 矩阵](prd.md#ac-7---it-覆盖矩阵)）

## 下一步

1. **用户确认 D1-D6 决策**（特别是 D6 F4 audit 是否 V1 必须 / D5 是否真不做 rollback API）
2. 开 Plan pipeline（Full 档对抗 review，因为触碰 `MemoryClusterer` 子系统 + 新增 entity + 协议级 invariant `immutable input`）
3. Plan 通过后 Sprint 1 (F2/F3/F4) 启动，Sprint 2 (M1-M4) 跟上

## 关联

- 拆出去的姊妹包：[`backlog/OUTCOMES-RUBRIC-FOUNDATION/`](../../backlog/OUTCOMES-RUBRIC-FOUNDATION/) — Outcomes 部分（独立 ship，独立路径）
- wiki R-AMA-MEM-1/2/3/4/5（关 Dreaming 的 5 条）→ 反向链 [`anthropic-managed-agents.md`](../../../../../research-docs/research/agent-harness-wiki/harness/anthropic-managed-agents.md)
- 跟 [`docs/plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md`](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) 互补 — 飞轮 V1-V6 已交付 production loop / surface A/B / attribution 三层；本需求包补 memory 能力扩展（从 reorganize 升到挖未观测 pattern）
