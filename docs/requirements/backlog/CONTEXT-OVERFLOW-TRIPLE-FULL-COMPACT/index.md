# CONTEXT-OVERFLOW-TRIPLE-FULL-COMPACT — Context Overflow 最多三次 Full Compact

---
id: CONTEXT-OVERFLOW-TRIPLE-FULL-COMPACT
mode: full
status: core implemented; Core + Server regression verified 2026-07-19
priority: P1
risk: High
created: 2026-07-19
source: 用户要求模型因 token 太多无法请求时，连续 Full Compact 并重试，最多三次
---

## 语义边界

本需求只处理 provider 拒绝输入请求的 context overflow：

- `LlmContextLengthExceededException`
- `context_length_exceeded`
- `prompt is too long`

不处理 `stopReason=max_tokens/length`。后者表示输出达到上限，Full Compact 输入历史通常不能解决，应继续使用现有“保留 partial text 后 continuation 一次”的逻辑，避免重复流式正文。

## 当前行为

改动前，`AgentLoopEngine` 在首次 context overflow 后只调用一次 `compactFull(SOURCE_POST_OVERFLOW)`；第二次 overflow 就直接抛出。

2026-07-19 已将 Core 行为改为下述最多三次的状态机，并增加 no-op/no-progress 提前停止保护。原有 `stopReason=max_tokens` continuation 行为保持不变。

## 目标行为

```text
模型请求 #1
  overflow -> Full Compact #1 -> 模型请求 #2
  overflow -> Full Compact #2 -> 模型请求 #3
  overflow -> Full Compact #3 -> 模型请求 #4
  overflow -> 失败：context_overflow_exhausted
```

最多执行 3 次 Full Compact、最多执行 4 次同一 logical LLM request。成功时立即退出恢复循环，不继续无意义压缩。

## 功能需求

1. 将单个 `retriedOverflow` boolean 改为当前 LLM iteration 内的 `postOverflowCompactAttempts` 计数器。
2. 默认上限为 3；可配置但生产 hard cap 不得超过 3，避免费用和循环失控。
3. 每次 overflow 必须重新调用 Full Compact，并将返回的新 messages 同步到：
   - engine `messages`
   - `LoopContext.messages`
   - `ToolResultRequestBudgeter` 后的 `request.messages`
4. Full Compact 返回 `null`、`performed=false` 或抛错时立即停止，不消耗后续模型重试。
5. 每次 Full Compact 必须证明有进展：`tokensReclaimed > 0`，且新的 request token estimate 小于上一次。没有进展则以 `context_overflow_no_progress` 失败，不能重复相同请求。
6. 三次 compact 后仍 overflow，抛出原始 `LlmContextLengthExceededException`，日志包含 compact attempt；稳定的客户端 failure code 可在统一 runtime error taxonomy 后续增量补充。
7. 每次模型请求和 compact 使用同一 root trace，但独立 child span；记录 attempt `1..3`、tokens before/after/reclaimed 和 outcome。
8. 不追加新的 user message，不重复广播已流式输出。context overflow 通常发生在 provider 接收阶段；若 provider 在错误前产生 delta，恢复必须先验证当前 provider 行为并避免重复 UI 内容。
9. Compact circuit breaker 仍生效；breaker open 时不绕过继续三次调用。

## Full Compact 连续执行边界

重复 Full Compact 不保证每次都能继续缩小：young generation、summary、recovery payload、Tool pairing 和 safe boundary 都可能形成最小下限。因此“三次”是上限，不是必须执行满三次。

当第二或第三次无法找到新的安全边界时，应提前失败；禁止为了满足次数而破坏：

- tool_use/tool_result 配对
- COMPACT_BOUNDARY
- USER role summary
- message JSON byte shape
- trace/message identity columns
- UTF-16 surrogate 安全

## 验收标准

1. 第一次、第二次或第三次 compact 后请求成功时，模型调用数分别为 2、3、4，compact 数分别为 1、2、3。
2. 连续四次 overflow 时，恰好 compact 3 次、模型请求 4 次，然后抛出 context overflow。
3. 任意一次 compact no-op/异常/no-progress 时提前停止，不调用下一次模型请求。
4. 每次 retry 使用最新 compact messages，不重复使用第一次 request 的旧 messages。
5. Tool pairing、boundary、summary role、recovery payload 和 persistence reconciliation 回归测试通过。
6. `stopReason=max_tokens` 测试保持现有 continuation 语义，不进入三次 Full Compact。
7. 取消、超时和 compact breaker 在恢复循环中仍可立即终止。

## 实施范围

- `AgentLoopEngine` post-overflow inner retry 状态机。
- `AgentLoopEngineRetryOverflowTest` 扩展为成功-attempt 矩阵、四次 overflow、no-op/no-progress/cancel。
- Compaction callback 的进展数据校验；若当前返回值信息不足，只做最小 DTO 扩展。
- Trace/Runtime failure code 与面向客户端的安全错误文案。

该需求触碰 `AgentLoopEngine` 和 Full Compact 路径，必须走 Full pipeline，并由 compact reviewer 检查全部八项不变量。
