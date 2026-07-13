# COMPACT-IDEMPOTENCY-BOUNDARY-FIX — 压缩失效（曾压缩过的 session 自动压缩被跳过 + tool-heavy 压不动）

> 创建：2026-06-19
> 状态：**部分交付**（P0 止血 + range-model go-live + 结构化摘要 + 2026-07-09 frontier 修复已实现；剩 ② tool-heavy 最坏情况 / W3 watchdog / 旧 orphan 残骸复核）。CompactionService + compact 子系统核心文件，8 不变量 → Full + `compact-reviewer`。
> 来源：用户报 session `9d3eff0f-c22c-4568-bec8-a75ffe1f952d`（微信 / agent 3）「一直有压缩问题、`/compact` 压不动多少」。系统化调试取证（日志 + DB + 代码）。
> 优先级：**高 → 中**（活的 P1 危害 ① 负 gap 已修且未再犯，见下方复验；剩余为 tool-heavy 偏弱 + 存储残骸）。

## 进度（2026-06-20 复验）

**2026-07-09 frontier follow-up（本批待提交）**：active summary range 改为模型视图真源，
不再依赖可能陈旧的 `compacted_by_summary_id` marker；full compact 增加单调 frontier guard，
只 supersede 被新范围完整覆盖的 summary，并在持久化后重算 marker；range-model light compact
禁止把派生 summary 回写成用户可见 NORMAL message。实施计划与测试矩阵见
[range-model frontier plan](../../../superpowers/plans/2026-07-09-range-model-compaction-frontier-fix.md)。

**已交付（均已 commit）**：① 负 gap idempotency（`3756ca43` Phase 0，gap 两端统一持久化计数空间）/ ③ 总结输入 map-reduce 分块（`3756ca43`，`MAX_SUMMARY_REDUCE_DEPTH=3`）/ 退化 guard + per-model 窗口（`3756ca43`）/ 结构化摘要 10 段模板（`9d226468`，`MAX_SUMMARY_TOKENS` 800→2000）/ range-model 存储重构 go-live（`068a4a5d`，治行膨胀）。

**2026-06-20 live 取证复验**（DB + 日志）：
- ① 负 gap **未再复发**：近期日志无 `fullCompact skipped gap=-` / `no safe boundary`，仅 routine `light compact no-op`（这轮无可修剪，正常）→ ① 修复 hold 住。
- **新发现：持久化历史 orphan tool_use 残骸**。session `9d3eff0f`（压过 5 次）有 **6 个 orphan tool_use**（无配对 tool_result，seq 7/18/53/140/278/420），但全在当前 boundary（seq 590）**之前 = 死历史，LLM 不读 → live 视图干净、无正确性影响**。对照 session `c9129461`（压 2 次）配对完全干净（70/70，0 orphan）。性质 = 持久化记录里的 INV-1 残骸（很可能修复前旧压缩 mangle，**未 100% 归因**：旧压缩 vs 中断 turn）。
- **结论**：活危害已修；残骸是死历史存储债（无害但属 INV-1 痕迹）。

**仍开放**：② tool-heavy(SubAgent/Team) **最坏情况**（grow young-gen 已缓解，但 grow 完仍 `no safe boundary` 的极端会话压不动；根治走 storage-redesign）/ W3 hung-running watchdog（未做）/ orphan 残骸是否需一次性 cleanup（待定）/ 在 9d3eff0f 实跑确认现在能压（live 复验）。

## 现象（实测证据）

```
AgentLoopEngine: Preemptive compaction triggered: ratio=1.72, estTokens=109821, window=64000, threshold=0.85
→ CompactionService: fullCompact skipped (idempotency) gap=-97 / -276 / -418
```
引擎每 loop 判定上下文 110K vs 窗口 64K（1.7×）、明确触发抢占压缩，**却被负 gap idempotency 跳过**。`/compact`（手动绕守卫）能跑但 `reclaimed=0/22 tokens`。另一 session `c9129461` 直接 `fullCompact no-op (no safe boundary)` 刷屏 + `updateSessionMessages prefix mismatch` 兜底。

## ⚠️ 深挖修正（2026-06-19,DB 亲验）

初判"压缩问题/负 gap"后深挖发现 session 562 行仅 156 distinct(406 重复)。一度误判为 **persistence-shape 字节发散 dup-append + LLM 吃重复(高危活损坏)** —— **此判断已收回(夸大了)**。亲验结论:

- **live 上下文干净**:最后 boundary(seq 418)+ summary(419)之后 **143 行全 distinct,零重复,tool 配对完整** → **LLM 没吃重复**。
- **重复 = 行存膨胀,非 live 污染**:`CompactionService.persistCompactResult` 每次压缩把 retained young-gen **当新行 append**(带各自保留的原 trace_id,证实是它而非 updateSessionMessages delta），而 `getContextMessages` 只读最后 boundary 之后 → 旧 young-gen 副本永久留存但永不被读。每次压缩 +~142 行(boundary 元数据 `compacted_message_count=0`、`tokens_after≈before` 甚至变大）。
- **b2c7039 guard 不触发**正常:dup 走 `persistCompactResult.appendMessages`(纯 append 无对账),updateSessionMessages 的 post-boundary 视图与 engine 内存一致 → 无 mismatch。
- **真正功能问题**:compaction 对 tool-heavy session **无效**(boundary 退化→reclaim≈0)→ 真实 110K live 上下文压不下去(已由 400K 窗口缓解)。

→ **严重度从"高危活损坏"下修为"中:存储/成本浪费 + 压缩无效"**。修复重点调整为下方,其中行存膨胀(persistCompactResult 双存)为新增主线。

## 根因（修正后 3 条线 + 行存膨胀）

### ① 负 gap：idempotency 计数空间错配（主因，活的危害）
`lastCompactedAtMessageCount` 在**持久化全量计数空间**记录（`CompactionService` L259/L657 = `getMessageCount()`=持久化行数高水位 560），但 gap 在 **engine 内存工作集空间**比较：
- L348 callback：`gap = current.size() - lastCompacted`
- L418 fullCompact：`gap = inMemoryMessages.size() - lastCompacted`

engine 每次 run 从**压缩后子集**（远小于 560）起步 → gap 恒负（-97…-418）< `IDEMPOTENCY_MIN_GAP_MESSAGES=5` → **in-loop 自动压缩永久跳过**。任何曾压到高持久化水位的 session 都中招：内存工作集涨到 400+ 仍 = 400-560 < 5 照跳。
**修向**：gap 两端必须同一计数空间。倾向 **(A)** idempotency 基于**单调持久化计数**（或单调 appended-message 计数器），内存 size 只用于"压多少"的决策、不用于 idempotency。（L308 REST 路径已自洽，问题在 L348/L418。）

### ② tool-heavy session 找不到 safe boundary（为什么手动也压不动）
`/compact` → `SessionMemoryCompactStrategy`（result 83668 > budget 40000 → fallback LLM）→ `FullCompactStrategy`。但 session 全是 **SubAgent/Team 的 tool_use↔tool_result 成对链**，safe boundary 不能切 tool 对中间 → 边界卡很早（只切一小段 → 替换后省≈0）或完全找不到（`no safe boundary` no-op）。→ 即使绕过 gap 跑了也切不动。
**修向**：boundary 检测对 tool-heavy/嵌套 tool 链的处理（如何在保持 tool_use↔tool_result 配对不变量前提下找到可压缩段；可能需要把成对的 tool 块整体作为可摘要单元）。

### ③ 总结输入无窗口保护（潜在，修 ② 后会暴露）
`FullCompactStrategy.callLlm`（L286）把要总结的 window 原样喂模型，输出限 `MAX_SUMMARY_TOKENS=800`，**输入无窗口/分块保护**。当前被 ② 掩盖（边界早→window 小）；一旦 ② 修成"能切大段历史"，window 变大就会**撞模型窗口**（用户预判正确）。
**修向**：总结输入按模型窗口分块/截断（map-reduce 式摘要）。

## 关联缓解：模型窗口配置（非修复，是缓冲）

- agent 3 model=`ark:glm-5.2`，config **未设 `context_window_tokens`** → 回退默认 `DEFAULT_CONTEXT_WINDOW_TOKENS=64_000`（即 window=64000 来源）。
- **可放大**：agent config 加 `"context_window_tokens": <N>`（`AgentLoopEngine.resolveContextWindow` 优先读）。N 须 ≤ glm-5.2 真实窗口（**待核实**，GLM 系列常见 128K）。
- **附带改进点**：`resolveContextWindow` 回退平 64K，**没用 application.yml 的 per-model `KNOWN_MODEL_WINDOWS` map**（claude 配了 200K）→ 应让它按模型解析真实窗口，而非平 64K 默认。
- ⚠️ **放大只是缓冲**：session 110K 且在涨，128K 仍 ratio≈0.86；压缩 bug 不修迟早再撞。

## 验收点

1. 曾压缩过的 session，in-loop 抢占压缩**不再被负 gap 跳过**（gap 用单调计数；构造一个压过的 session 触发 preemptive 能真压）。
2. tool-heavy（SubAgent/Team）session `/compact` **reclaimed 显著 > 0**（不再 no-op / 0），且不破坏 tool_use↔tool_result 配对 + 持久化字节一致不变量。
3. 总结输入超模型窗口时**分块处理不报错**（不再静默吞）。
4. 回归：普通 session 压缩照常；compact 8 不变量全守（compact-reviewer 审）。
5. （缓解，可选）agent 3 窗口配到 glm-5.2 真实值 + resolveContextWindow 走 per-model map。

## 冒烟用例（部署后 qa-reviewer）

1. 取一个曾 full-compact 过、persisted 高水位的 session，跑一轮 agent loop → 日志应见 `fullCompact done reclaimed>0`，**不再** `skipped gap=负`。
2. tool-heavy session 手动 `/compact` → `reclaimed>0` + DB t_session_message 配对仍合法。
3. 构造超窗口的 summary 输入 → 分块摘要成功，无 context-length 报错。

## 风险 / 不变量

- 全在 `CompactionService` / `FullCompactStrategy` / `SessionMemoryCompactStrategy` / boundary（核心文件 + compact 8 不变量）→ **Full + `compact-reviewer`**。
- 改 idempotency 计数 / boundary / rewrite 必守：INV-1 tool_use↔tool_result 配对、INV-4 持久化-engine 字节一致、INV-5 rewrite preserve identity 列。

## 阅读顺序
1. 本 index（诊断 3 线 + 窗口缓解 + 验收）
2. tech-design（即时 bug 修复判断：① 负 gap / ② boundary / ③ 总结窗口 / R 双存）
3. **storage-redesign（根治方案：三视图分离 + 范围摘要表 + 派生模型视图 + 合并滚动摘要；Q1–Q5 决策已锁）** ← 真正的重构主文档

## 关联
- 核心文件：`CompactionService` / `skillforge-core/compact/*` / `AgentLoopEngine` resolveContextWindow
- 多 session 受影响（9d3eff0f / c9129461 …），非个例
- 与 [`persistence-shape-invariant.md`](../../../.claude/rules/persistence-shape-invariant.md) / [`identity-column-on-rewrite.md`](../../../.claude/rules/identity-column-on-rewrite.md) 直接相关
