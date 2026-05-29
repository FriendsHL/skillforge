---
type: review-report
created: 2026-05-29
input_packet: anthropic-dynamic-workflows-review-packet.md
reviewed_files:
  - prd.md
  - tech-design.md
  - dsl-syntax.md
  - (verified against Sprint 1 impl: com.skillforge.workflow.* @ commit 54941c3)
reviewer: 林 (Opus 4.8, SkillForge orchestrator)
---

# Review Report — V1 DSL vs Anthropic Dynamic Workflows

> **判断基调**：user 原话 "plan as workflow 这个能力 没有想的很复杂"。所以 ⚠️缺口 / 🤔分歧 重点判断
> **"对 autoEvolving 这个用例够不够用"**，不是功能对等 Anthropic。Anthropic 有但 autoEvolving 用不上的，标 "V2+ / 用例不需要"，不算 ship-blocker。

## ✅ 已对齐（9 项）

| # | V1 DSL 设计点 | Anthropic 对应 | 证据 |
|---|---|---|---|
| 1 | plan-as-code：plan 在 JS runtime（Rhino），sub-agent 中间结果不进主 Claude context | plan 移出 Claude context，只 final 进 | prd §FR-1.1 + Sprint 1 已 ship `WorkflowRunnerService` |
| 2 | `meta` 必须纯字面量（禁变量/函数/插值/spread） | meta PURE LITERAL（packet §4.2） | dsl-syntax §2 + prd FR-1.3 |
| 3 | `Date.now()` / `new Date()` / `Math.random()` 沙箱禁 | 同禁（packet §4.3，保 cache 确定性） | dsl-syntax §7 |
| 4 | 硬限制：总 agent ≤1000 / 并发 min(16, cores-2) | 1000 total / min(16, cores-2)（packet §4.1） | `BudgetTracker.DEFAULT_AGENT_CALL_CAP=1000` + dsl-syntax §10 — **数值直接照抄，正确** |
| 5 | schema 强制 structured output + 失败 retry（tool-call layer） | schema force-retry（packet §5.4） | prd FR-1.4 + dsl-syntax §3.1（Sprint 2 实现中） |
| 6 | `pipeline` 默认 / `parallel` 慎用（仅 cross-item/dedup/早退） | DEFAULT TO pipeline（packet §5.3） | dsl-syntax §3.3 文案明确 |
| 7 | 6 原语命名 5 个一一对应（agent/parallel/pipeline/phase/log） | 同名同语义（packet §3） | dsl-syntax §3 — 迁移 Claude Code workflow 用户成本最低 |
| 8 | 修改 workflow = 直接编辑 `.js` 文件 + hot-reload，不建专门 Edit 工具 | 复用 Edit/Write，不建 WorkflowEdit（packet §2.4/§5.1） | prd FR-1.5 + `WorkflowDefinitionRegistry` |
| 9 | 沙箱：脚本不能直接 fs/shell，只 `agent()` 能触达 | 脚本无 fs/shell（packet §4.1） | prd FR-2.1 ClassShutter + `L1SandboxFactory` |

**对齐度：核心架构哲学 ~90% 命中**。最关键的 4 条（plan-as-code / pure-literal meta / 照抄硬限制 / schema 强制）全在。

## ⚠️ 缺口 — 需判断（3 项）

| # | Anthropic 有的 | V1 DSL 缺的 | 影响 | P | 建议改法 |
|---|---|---|---|---|---|
| 1 | `resumeFromRunId`（改脚本后 cache-hit 未变 prefix，只 live-run 改过的 agent 之后） | **只有 humanApprove journal-replay，无通用 debug-iteration resume** | 改一行 workflow 重跑要全量重跑（30min level），不能 <5min 增量 | **P1** | journal cache 已经在了（FR-4），加 `resumeFromRunId` 入口 + script-hash 比对，复用现有 stepIndex cache。**对 autoEvolving 用例非 ship-blocker**（workflow 数量少、迭代频率低），但采用率/dogfood 体验关键 |
| 2 | `parallel` 真并发 + `pipeline` 真并发（每 item 独立流不等齐） | `parallel` 真并发✅ 但**约束 thunk 必须 tail-call `agent()`**；`pipeline` **V1 纯串行**（impl 注释承认，HostPipeline.java:75 显式 log "no concurrency"） | pipeline 是 Anthropic 推荐默认 pattern，串行实现下"默认 pipeline"建议名实不符；OPT-REPORT fanout 用 parallel 能撑住，但慢 item 阻塞 barrier | **P1** | pipeline 并发已规划 V2（dsl-syntax §14）。**对 OPT-REPORT 用例够用**（fanout-annotate 用 parallel）。建议：dsl-syntax 把"默认 pipeline"措辞降级为"V2 起默认"，避免 workflow 作者误用串行 pipeline 当并发 |
| 3 | adversarial verification（agent 互相 refute 直到 converge） | V1 无内建 refute 路径 | V3 K-4 falsification stage 落不下来 | **P2 / 用例不需要** | packet §10 例子是用 pipeline+parallel 组合手写的，**不是引擎原语** —— V1 原语集已足够表达（pipeline stage2 起 verify agent）。明确非 V1 缺口，V3 用现有原语拼即可 |

**注意**：缺口 1、2 **都不是 ship-blocker**，都已在 V2 roadmap（dsl-syntax §14/§15 显式列）。缺口 3 是用例本身 V3 才需要，且现有原语可表达。

## 🤔 分歧 — 需判断（4 项）

| # | V1 DSL 有的 | Anthropic 没有 | 判断 | 理由 |
|---|---|---|---|---|
| 1 | `humanApprove` 原语（journal-replay 暂停/resume，扛 server 重启） | 无 human-in-loop 原语 | ✅ **合法 divergence** | autoEvolving 核心用例就是"attribution 人审后才写 promotion"。Anthropic 是 dev-tool（无人审需求），SkillForge 是治理平台 —— 这是真实业务差异，不是 over-engineering |
| 2 | Rhino + 自建 L1 sandbox（ClassShutter / instruction cap / token filter / await-stripper） | Claude Code 内部 runtime（不暴露） | ✅ **必要 divergence** | SkillForge 是服务端，必须自己跑不可信 agent-written JS。这是平台必需的，不是镀金。`AwaitPreprocessor` / `JsKeywordStripper` 是 Rhino 无 async 的务实适配 |
| 3 | `ctx.json()` / `ctx.runId()` 全局 helper | 无（Anthropic 有 `budget` 全局，V1 没实现） | ✅ 合法（小） | 沙箱禁 ObjectMapper 直接访问 → 需 `ctx.json()` 兜底。`budget` 全局 V1 缺（dsl-syntax §14 承认），autoEvolving 用例不需要自适应预算，V2 补即可 |
| 4 | `parallel` thunk 必须 tail-call `agent()`（非通用 thunk） | parallel 接任意 `() => Promise` | ⚠️ **潜在 over-constraint，非 over-engineering** | 是 Rhino 单线程 offload 模型的实现妥协（HostParallel.java），不是设计选择。对当前 fanout 用例够用，但 workflow 作者写 `parallel([() => { const x = await agent(); return x+1 }])` 会抛 "did not tail-call agent()" —— **需在 dsl-syntax §3.2 显式文档化此约束**，否则作者踩坑。不建议 V1 改实现 |

**无 over-engineering 判定** —— 所有 V1 独有项要么是真实业务需求（humanApprove），要么是服务端平台必需（sandbox），要么是实现妥协（parallel 约束）。Sprint 2 没有该砍的镀金。

## 优先级排序的 action items

### P0（必须在 ship 前修）
- **无 P0**。V1 DSL 核心架构跟 Anthropic 高度对齐，所有缺口都已在 V2 roadmap 或用例本身不需要。Sprint 2 方向无需调整。

### P1（Sprint 2-4 内，强烈建议）
- [ ] **文档化 `parallel` thunk 约束**（分歧 4）：dsl-syntax §3.2 显式写 "thunk 必须以 `agent()` 调用收尾，不能在 thunk 内对 agent 结果做后处理"，给反例。零成本防作者踩坑。
- [ ] **降级 "pipeline 默认" 措辞**（缺口 2）：dsl-syntax §3.3 标注 "V1 pipeline 串行执行，真并发 V2 起"，避免作者把串行 pipeline 当并发用导致 OPT-REPORT 跑慢。
- [ ] **规划 `resumeFromRunId` debug-iteration**（缺口 1）：journal cache 基建已在，V2 加 script-hash 比对 + resume 入口。记 V2 子包。

### P2（V2+ 可选 / 用例不需要）
- [ ] `budget` 全局（分歧 3）— autoEvolving 不需自适应预算，V2 补。
- [ ] `workflow()` 嵌套（dsl-syntax §15）— V2。
- [ ] pipeline 真并发（缺口 2 完整版）— V2。
- [ ] adversarial verification（缺口 3）— V3 用现有原语拼，非引擎工作。

## 总结

V1 DSL 跟 Anthropic Dynamic Workflows **路线对齐度 ~90%**：plan-as-code、pure-literal meta、照抄硬限制（1000/min(16,cores-2)）、schema 强制、单工具多模式、复用 Edit 改 workflow、沙箱无 fs/shell —— 最关键的架构选择全部独立收敛到同一答案，验证方向正确。

**最大风险点**：不是缺口，是 **`pipeline` V1 串行 + `parallel` thunk 约束**这两个实现妥协 **未充分文档化**，workflow 作者会把串行 pipeline 当并发用、或在 parallel thunk 里写后处理踩坑。两者都是 P1 文档修，零代码改动。

**ship 时间影响：0 天**。无 P0，Sprint 2（humanApprove + schema + REST + V127 step_index）方向不需调整，按计划推进。`humanApprove` 和自建 sandbox 是 SkillForge 作为服务端治理平台的合法 divergence，不是 over-engineering，不该砍。`resumeFromRunId` 是采用率/dogfood 体验加分项，但对 autoEvolving 低频用例非 ship-blocker，留 V2。
