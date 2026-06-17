# WF-CONCURRENT-PIPELINE — Workflow 引擎并发 pipeline（学习 CC 并行模型）

> 创建：2026-06-17
> 状态：backlog（未排期；归属 AUTOEVOLVING V2(d) DSL Phase 2 原语）
> 模式：Full（触碰 workflow 引擎核心 + 确定性/journal-resume 不变量）
> 来源：对照 Anthropic《Introducing Dynamic Workflows》《A Harness for Every Task》两篇 blog 复盘 SkillForge workflow 引擎能力差距。

## 用户请求

「看看 CC 的并行是如何做的，我们是否可以学习？」—— 我们当时不就是 batch 并行 agent 吗？

## 背景

SkillForge workflow 引擎（AUTOEVOLVING V1）已有两种并行语义：
- `parallel(thunks)`：**已是并发** fan-out + barrier（worker pool）。evolve-loop 当前用的就是它，所以现状没卡。
- `pipeline(items, ...stages)`：**V1 是串行实现**（脚本里直接写了 "V1 串行执行"）—— 每个 item 顺序走完所有 stage，无 stage 间重叠。

CC Dynamic Workflows 的并行模型（blog + 我方对照确认）：
- **pipeline-by-default，stage 间无 barrier**：item A 可以在 stage 3、item B 还在 stage 1，wall-clock = **最慢单条链**，不是各 stage 最慢之和。
- `parallel` 是 barrier，**只在 3 种情况才用**：(1) 跨全集 dedup/merge 后再做贵活；(2) 总数为 0 早退；(3) 下游 stage 需要引用"其它 item 的结果"。
- 资源 aware 上限：`min(16, cpu cores − 2)` 并发 / 单 run 1000 总 agent / 单次 parallel|pipeline ≤ 4096 项。

## 问题

我们的 `pipeline()` 串行 → 多 stage 链路（如"多 badcase 并行诊断 → 各自生成候选 → 各自 verify"）wall-clock 会累加，浪费墙钟。随着 EVOLVE-JUDGE-GROUNDING 引入 per-badcase fan-out + pairwise verify 这类多阶段链路，串行 pipeline 会成为真瓶颈。

## 提议方案（待 design 细化）

1. 把 `pipeline()` 从 V1 串行升级为**并发流水线**：每个 item 独立穿过所有 stage、stage 间不设 barrier，复用现有 worker pool。
2. 采纳 CC 的**作者指引**：默认 pipeline、parallel 仅限上述 3 种情形（写进引擎文档/示例）。
3. 采纳资源 aware 并发上限（对齐 `min(16, cpu−2)` / 1000 / 4096，按 SkillForge 服务端实际核数调）。

## 验收点（草拟）

- `pipeline()` 多 item 多 stage 时有 stage 重叠；wall-clock ≈ 最慢单条链，而非各 stage 最慢之和（基准对比）。
- 并发受上限约束，不打爆服务端 CPU/worker pool。
- 现有 evolve-loop / opt-report workflow 行为不回归。

## 开放问题（design 阶段解）

- **journal-replay 确定性**：并发后 step-index 分配顺序还能保证 resume 缓存命中吗？（现 step-index 在单线程 Rhino 按程序序分配——并发 pipeline 必须保留可复现的 step 编号，否则 resume 失效）。这是本需求最大风险点。
- Rhino 单线程脚本 + worker pool 的边界：哪些在脚本线程、哪些 offload。
- budget（agent-call cap）与并发的交互。

## 关联

- 现状：[autoevolving 能力现状快照](../../../references/autoevolving-capability-stage-2026-06-17.md) L1/L4
- 归属：[AUTOEVOLVING-MASTER](../../active/2026-05-28-AUTOEVOLVING-MASTER/index.md) V2(d) DSL Phase 2 原语
- 姊妹方向：[EVOLVE-JUDGE-GROUNDING](../EVOLVE-JUDGE-GROUNDING/index.md)（它引入的多阶段链路是本需求的主要受益者）
