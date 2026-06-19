# Tech-Design（修复判断）— COMPACT-IDEMPOTENCY-BOUNDARY-FIX

> 状态：修复方向已判断（2026-06-19，待开工细化）。核心文件 + compact 8 不变量 → Full + `compact-reviewer`。
> 已做缓解：agent 3 `context_window_tokens=400000`（glm-5.2 实测稳定区，临时脱离 1.7× 裸跑；非修复）。

## 修复判断

### ① 负 gap —— 必修，高置信

**判断**：idempotency guard 把"压得太频繁(不必要)"和"压不动(没需要)"混为一谈，且用错了计数空间。两层修：

- **（主）over-threshold 必压、绕过 idempotency**：当引擎已判定 `ratio > softThreshold`（抢占压缩，上下文真超窗）时，**这次压缩是必需的,绝不能被 idempotency 跳过**。在 L348/L418 的 idempotency 判断前加：`if (preemptive / ratio>threshold) → 不走 idempotency skip`。这条直接消除"110K vs 64K 还被 skip"的活危害。
- **（底）gap 用单调持久化计数,两端一致**：`lastCompactedAtMessageCount` 记录在持久化全量空间(L259/657)，但 L348 用 `current.size()`、L418 用 `inMemoryMessages.size()`（engine 压缩后工作集，非单调）。改为两端都用 `session.getMessageCount()`（持久化行数，跟记录端同空间；注意 callback 路径要取**当前**持久化计数而非 run 起始快照）。in-memory size 只用于"压多少"的决策，不用于 idempotency。
- **验证**：构造一个压过、持久化高水位的 session，跑一轮使 ratio>0.85 → 必须 `fullCompact done reclaimed>0`，无 `skipped gap=负`。

**附带**（同包顺手）：`AgentLoopEngine.resolveContextWindow` 回退平 64K 默认，**未用 application.yml `KNOWN_MODEL_WINDOWS` per-model map** → 改为按 model 解析真实窗口（glm-5.2/claude 等），agent 未显式配也能拿到正确窗口，而非保守 64K。

### ② no safe boundary / tool-heavy 压不动 —— 核心,中置信(需补 Phase-1)

**现状判断**：`fullCompact no-op (no safe boundary)` + reclaim≈0 出现在 SubAgent/Team tool 对链密集的 session。safe boundary 不能切 tool_use↔tool_result 对中间(INV-1)；young-gen(保留近期不压)增长直到覆盖整条可压区 → 找不到边界。

**修复方向**（开工前需补取证确认是 young-gen 吞没 还是 配对扫描问题）：
- 把**完整的 tool_use↔tool_result 对 / 一次 SubAgent 派发+结果块**当作**原子可摘要单元**，边界落在完整单元之后（既守 INV-1 又能切大段）。
- young-gen 上限：当 young-gen 已覆盖到 contextWindow 很大比例仍找不到边界时，**允许把更老的成对单元纳入可压区**（而不是 no-op 放弃）。
- **必须取证先确认根因**（young-gen 比例 vs 配对扫描），再锁实现。这条我**不打包票**，开工第一步是 Phase-1 取证（在该 session 上打印 boundary 搜索过程）。

### ③ 总结输入无窗口保护 —— 必修(防御),高置信

**判断**：`FullCompactStrategy.callLlm`(L286) 把要总结的 window 原样喂模型，输出限 800，**输入无保护**。修 ② 让边界能切大段后，window 会变大撞模型窗口。
**修复**：callLlm 前按"模型窗口 − 输出预算 − system"分块；**map-reduce 摘要**（分块各摘 → 再摘合并），或单块超限时降级截断 + 标注。加输入 token 估算 guard。

## 实施顺序（开工）
1. **①主(over-threshold bypass)** —— 最小改动、消除活危害，先落。
2. **②取证 → 边界修** —— 核心，但先 Phase-1 确认根因。
3. **③总结分块** —— 跟 ② 一起(② 修好才暴露 ③)。
4. **附带 resolveContextWindow per-model map**。
5. 全程 `compact-reviewer` 审 INV-1/4/5；回归普通 session 压缩 + 持久化字节一致。

## 不变量（必守）
- INV-1 tool_use↔tool_result 配对（② 的边界 + 原子单元）
- INV-4 持久化-engine 字节一致（rewrite 路径）
- INV-5 rewrite preserve identity 列（trace_id 等）

## 冒烟（部署后 qa-reviewer）见 index.md §冒烟用例。
