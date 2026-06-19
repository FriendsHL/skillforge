# COMPACT-IDEMPOTENCY-BOUNDARY-FIX — 压缩失效（曾压缩过的 session 自动压缩被跳过 + tool-heavy 压不动）

> 创建：2026-06-19
> 状态：**立项 / 待修**（CompactionService + compact 子系统核心文件，8 不变量 → Full + `compact-reviewer`）
> 来源：用户报 session `9d3eff0f-c22c-4568-bec8-a75ffe1f952d`（微信 / agent 3）「一直有压缩问题、`/compact` 压不动多少」。系统化调试取证（日志 + DB + 代码）。
> 优先级：**高**（线上微信 agent 正在 1.7× 窗口裸跑，压缩全被拦，随时可能 LLM 超窗失败/降质）。

## 现象（实测证据）

```
AgentLoopEngine: Preemptive compaction triggered: ratio=1.72, estTokens=109821, window=64000, threshold=0.85
→ CompactionService: fullCompact skipped (idempotency) gap=-97 / -276 / -418
```
引擎每 loop 判定上下文 110K vs 窗口 64K（1.7×）、明确触发抢占压缩，**却被负 gap idempotency 跳过**。`/compact`（手动绕守卫）能跑但 `reclaimed=0/22 tokens`。另一 session `c9129461` 直接 `fullCompact no-op (no safe boundary)` 刷屏 + `updateSessionMessages prefix mismatch` 兜底。

## 根因（3 条线）

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
2. tech-design（修复方案，开工前补）

## 关联
- 核心文件：`CompactionService` / `skillforge-core/compact/*` / `AgentLoopEngine` resolveContextWindow
- 多 session 受影响（9d3eff0f / c9129461 …），非个例
- 与 [`persistence-shape-invariant.md`](../../../.claude/rules/persistence-shape-invariant.md) / [`identity-column-on-rewrite.md`](../../../.claude/rules/identity-column-on-rewrite.md) 直接相关
