# BUG-G 防御性补强

---
id: BUG-G
mode: full
status: deferred
priority: P1
risk: Full
created: 2026-04-28
updated: 2026-04-29
---

## 摘要

BUG-G 根因已在 2026-04-28 修复：tool-use 处理改为结构优先，并保留 qwen streamed tool call 身份。剩余防御性补强暂缓。

## 已交付

- BUG-G-2 已修：`toolUseBlocks` 和 `stopReason` 一致性。
- qwen streamed 空 `id` / `name` delta 不再覆盖已收集身份。
- AgentLoopEngine 只执行和持久化有效 tool-use blocks。

修复细节：

- `LlmResponse.isToolUse()` 改为结构优先：存在有效 tool_use block 即进入工具执行。
- 过滤空 id/name 和重复 id。
- qwen 流式解析避免空白 delta 覆盖已收集 id/name。
- 覆盖测试包括 `LlmResponseTest`、`OpenAiProviderStreamToolCallTest`、`AgentLoopEngineToolUseInvariantTest`。

## 暂缓项

- BUG-G-1：OpenAI-compatible 发送前历史 sanitizer，处理 dangling assistant tool calls。
- BUG-G-3：error / cancel / max-loop 路径的最终持久化不变量校验。

暂缓原因：根因 BUG-G-2 已堵源头，发送前 sanitizer 和失败路径兜底都是防御性补强。已刻意避免把 qwen 修复和历史防腐混在一个提交里。

## 重评触发

如果再次出现 assistant `tool_use` 缺少匹配 `tool_result`，或 provider switch 再次触发 OpenAI-compatible pairing error，则重新打开。

## 历史来源

详细原始事故记录保留在 [legacy todo](../../../references/legacy-todo-2026-04-28.md)。
