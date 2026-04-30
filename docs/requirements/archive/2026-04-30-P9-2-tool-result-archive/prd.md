# P9-2 PRD

---
id: P9-2
status: prd-ready
owner: youren
priority: P0
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-28
updated: 2026-04-30
---

## 摘要

当 tool_result 在单条消息或单次 LLM 请求中占用过大时，按预算替换为 preview + archive/request-trim reference，避免长任务被工具输出挤爆 context 或触发错误的 `Token budget exceeded` / `max_tokens` 终止。

本 PRD 合并 BUG-32：用户在 2026-04-30 反馈长任务仍出现 `Token budget exceeded (526516 tokens). Providing best answer with current information.`，并看到模型返回 `"stopReason":"max_tokens"` 后无法继续；session `919c2bda-1867-40eb-8c7c-4bb49d341ee2` 的现场 trace 显示首个 trace 有多次 `max_tokens`，后续请求仍携带 30+ 条 tool message 的完整内容。

## 目标

- 减少大 tool output 对活跃 context 的挤占。
- 保持 append-only message history 语义。
- 归档内容可通过 ID 追溯。
- 累计 input token 是成本指标（归 cost dashboard / quota 系统），不作为 agent loop 终止条件；长任务能否继续完全由 provider 单次 context window + 单 turn `max_tokens` 决定。业界标杆 Claude Code 与 OpenClaw / pi-mono 主 loop 均无跨调用累计闸门，仅以 context window 为基准触发 compact（详见 [业界标杆参考](#业界标杆参考)）。
- `max_tokens` 不得被当成成功最终答案；必须先做透明续写恢复，恢复耗尽后才以明确可诊断失败状态结束，且**单 turn 内最多触发一次恢复**避免死循环。"turn" 在本 PRD / Agent Loop 语境下指**单次 LLM 调用 / 单次 agent loop iteration**（与 Claude Code `query.ts:1104,1157` 的 `hasAttemptedReactiveCompact` per-413-event 语义一致），不是整个 `run()` 任务；防死循环只保护单次 LLM 调用的 continuation 链，下一迭代是新的独立恢复机会。该解读由 Judge round 1 评审落地，详见 [tech-design FIX-1](tech-design.md#实施计划)。

## 非目标

- 本任务不做完整 post-compact 文件摘要注入。
- 本任务不做 partial compact。
- 本任务不大改 session message model。
- 本任务不处理 P1-D skill catalog / reconciler 同名问题。
- 本任务不优化 skill marketplace 冷启动或安装确认流。
- 本任务不交付 `ToolResultRetrieveSkill`（让模型按 archive ID 主动取回原文）。Claude Code 同样未将归档原文暴露给模型，2KB preview 从模型视角即是最终视图；若上线后观测到"模型反复重跑同一工具"等明确诉求，再开 P9-2-follow-up 需求包单独评估。

## 功能需求

- 检测单条消息中 tool_result aggregate size 是否超过 200K chars。
- 按大小降序归档 tool_result，直到消息回到预算内。
- 用 2KB preview 和 archive reference 替换被归档内容。
- 在专用 store/table 中持久化归档正文。
- 归档决策对同一 `tool_use_id` 在整个 session 生命周期内必须**幂等不翻转**：某条 tool_result 一旦被归档替换为 preview，后续所有 turn（含 session reload / resume）看到该 id 都必须是 preview；反之，未被归档的不会突然被归档替换原文。该不变量与 prompt cache 稳定性强相关 —— 翻转会导致前缀变化引发缓存全部 miss。参考 Claude Code `applyToolResultBudget` 的 `partitionByPriorDecision` 三状态（`mustReapply` / `frozen` / `fresh`）。
- 每次调用 LLM 前，基于将要发送的 request messages 再执行一次 request-time tool_result 预算裁剪：
  - 单条 tool_result 仍受现有 per-result cap 保护。
  - 多条 tool_result 的聚合内容必须受 request-level cap 保护，覆盖“33 条各自不大但合计很大”的情况。
  - request-time 裁剪只能作用在发送给 provider 的消息副本上，不得破坏内存中的原始会话历史和持久化历史。
- `AgentLoopEngine` 不得默认因为 `totalInputTokens > max_input_tokens` 返回“Providing best answer”。该累计值可用于 telemetry、notice 或显式用户配置的硬限，但默认不能阻断正常长任务。
- 如果 provider 返回 `stopReason=length` 或 `stopReason=max_tokens`：
  - 首次请求前应使用足够的 `maxTokens` 上限，避免明显不足。
  - 已经流式输出的截断响应不得被静默当成最终成功。
  - 恢复路径必须采用 continuation 语义：保留已输出片段，只请求模型继续未完成内容，不能重放同一个 request 导致 UI 重复。
  - 如果 continuation 前触发 compact，compact 后的消息必须真正写回 `request.messages`。
  - **同一 turn 内最多触发一次 `max_tokens` 恢复**：首次恢复后必须置 `hasAttemptedMaxTokensRecovery` 等价标志（参考 Claude Code `query.ts:1104,1157` 的 `hasAttemptedReactiveCompact` 一次性防护），第二次再触发 `max_tokens` 直接走 LoopResult 失败状态，避免 "压缩 → 续写 → 再超 → 再压缩" 死循环。
  - 恢复耗尽时，LoopResult status 必须明确为可诊断失败，不得返回 success。
- observability trace 必须能显示 request-time 裁剪结果，包括原始 tool_result chars、发送给 provider 的 tool_result chars、裁剪条数和触发原因。

## 旧 ToDo 原文要点

> 直接解决“长对话 context 爆满”真实用户慢性病。P6 消息行存储上线后，归档表前置条件已满足，工程复杂度大幅降低。触碰核心文件（CompactionService），走 Full Pipeline 对抗循环。

| 子任务 | 说明 |
| --- | --- |
| P9-2 Per-message 聚合预算 + 归档持久化 | 单条 user message 的 tool_result 总量超 200K chars 时，按大小降序归档到 `t_tool_result_archive` 表，消息替换为 2KB preview + 引用 ID；可选新增 `ToolResultRetrieveSkill` 让模型按需读取。 |

## BUG-32 现场证据

- session：`919c2bda-1867-40eb-8c7c-4bb49d341ee2`。
- 首个 trace 观测到 15 次 LLM 调用、28 次工具调用、累计 input 371,433 tokens，且多次 `finishReason=max_tokens`。
- 其中一次 LLM request 含 33 条 tool message、约 99K chars 工具内容、request body 约 158K chars；后续重试仍携带同类完整工具内容。
- 现有 `ToolResultTruncator` 只做单条 40K chars 截断，无法处理多条中等 tool_result 聚合撑爆请求。
- `AgentLoopEngine` 仍保留默认 `max_input_tokens=500000` 的累计硬停，导致非 context-window 的长任务被直接结束。
- `max_tokens` 恢复路径在第二次 compact 后没有同步更新 `request.messages`，且恢复耗尽后会继续普通响应处理。

## 业界标杆参考

设计前调研了两份本地源码笔记锚定的实现，作为本次方案的参考基线：

- **Claude Code**（`src/query.ts:370-470` 主动压缩流水线 + `:1060-1200` 反应式 413 恢复）：5 级压缩链（`applyToolResultBudget` → `snipCompactIfNeeded` → `microcompact` → `contextCollapse` → `autoCompactIfNeeded`）+ 反应式恢复 `hasAttemptedReactiveCompact` 一次性防护；`autoCompact` 阈值 = `contextWindow - MAX_OUTPUT_TOKENS_FOR_SUMMARY(20K) - AUTOCOMPACT_BUFFER_TOKENS(13K)` ≈ 167K（Claude 200K 窗口）。**整套机制基于 provider context window，不存在跨调用累计闸门**；`src/query/tokenBudget.ts` 是 subagent 任务成本预算，与主 loop 终止决策正交。
- **OpenClaw / pi-mono**（`pi-embedded-runner/run/attempt.ts` 决策树 + `agents/compaction.ts`）：明确语义 "context overflow → 自动 compact（**不 retry**）" / "rate_limit / 5xx → 指数退避" / "max_tokens → 映射 stop_reason=length 进入 failover 决策"；**同样不存在累计 token kill switch**。

P9-2 的"per-message archive + request-time aggregate trim"是 Claude Code 5 级流水线的简化版，未做 microcompact / contextCollapse 中间层（后者依赖 `cache_edits API` 等 ant-only feature，工程复杂度高且 ROI 不明）；保留 Phase 2 引入空间。Claude Code 的归档幂等不变量、`hasAttemptedReactiveCompact` 一次性防护均在功能需求中显式映射。

## 验收标准

- [ ] 超大 tool result 会从活跃 context 中归档出去。
- [ ] context 里保留 preview 和 archive ID，不保留全文。
- [ ] 普通小 tool result 不受影响。
- [ ] compaction 和 session reload 路径能保留 archive reference。
- [ ] session `919c2bda-1867-40eb-8c7c-4bb49d341ee2` 同类 30+ tool message 请求在进入 provider 前会被 request-time 聚合裁剪。
- [ ] 默认配置下不会再出现 `Token budget exceeded (...). Providing best answer with current information.` 作为长任务最终答案。
- [ ] `stopReason=max_tokens` 不会被标记为成功最终答案；恢复成功时用户看到已输出片段后的续写内容，不出现重复重放；恢复失败时状态和 trace 可诊断。
- [ ] `max_tokens` 恢复在同一 turn 内最多触发 1 次；第二次仍 `max_tokens` 不再续写，LoopResult 直接进入可诊断失败状态。
- [ ] 同一 `tool_use_id` 的归档决策在整个 session 生命周期内不翻转：归档过的不再变回原文，未归档的也不会突然在后续 turn 被归档替换；session resume 后归档决策可被正确重建。
- [ ] request-time 裁剪不破坏 tool_use/tool_result 成对协议，不影响 replay、compaction 和 session reload。

## 验证预期

- 后端：archive 创建和 context replacement 的 service/repository 测试。
- 数据库：archive table migration 校验。
- Core：request-time aggregate trimming、`max_tokens` 恢复、累计 token budget 不硬停的 AgentLoop 回归测试。
- 回归：session context construction、provider conversion、observability trace 和 compaction 测试。
