# LLM Provider Compatibility Rules

Read this before changing `skillforge-core/src/main/java/com/skillforge/core/llm/**`,
`ClaudeProvider`, `OpenAiProvider`, `ProviderProtocolFamily*`,
`LlmStreamHandler`, LLM cache code, provider-related `Message` /
`ContentBlock` fields, or agent-loop streaming handshakes.

## Provider Protocol Differences

Claude and OpenAI-compatible providers encode the same concepts differently:

- Text delta:
  - Claude: `content_block_delta` with `delta.type=text_delta`.
  - OpenAI-compatible: `choices[].delta.content`.
- Tool call:
  - Claude: `tool_use` content block with `id`, `name`, and object `input`.
  - OpenAI-compatible: `tool_calls[]` with `id`, `function.name`, and string
    `function.arguments`.
- Tool result:
  - Claude: `tool_result` with `tool_use_id`.
  - OpenAI-compatible: `role=tool` with `tool_call_id`.
- Reasoning:
  - Claude uses thinking blocks.
  - Qwen / DeepSeek / MiMo style providers may stream `reasoning_content`.
- Usage:
  - Claude uses input/output/cache token fields.
  - OpenAI-compatible uses prompt/completion/total token fields.

## Regression Checklist

1. Qwen streamed tool-call identity: later chunks may omit `id`; carry forward by
   index so `tool_call_id` stays stable.
2. Qwen thinking default: qwen3-style models must explicitly set
   `enable_thinking=false` unless thinking is intentionally enabled.
3. Reasoning fields must be declared and carried on all relevant DTO/message
   paths, not just one provider.
4. OpenAI-compatible SSE parsing must handle `delta.reasoning_content`.
5. Tool normalization must map `tool_use_id` <-> `tool_call_id` and object input
   <-> JSON-string arguments symmetrically.
6. Claude prompt-cache `cache_control` must not leak into OpenAI-compatible
   request bodies.
7. Finish reasons must map correctly: `stop`, `length`, `tool_calls`, provider
   error events, and context-overflow recovery.
8. Usage normalization must preserve provider-specific token fields needed by
   observability and cost displays.
9. New model IDs must update `ProviderProtocolFamilyResolver` when they need
   special behavior.

## Review Requirements

- Run or add provider-specific tests for both Claude and OpenAI-compatible paths
  when shared request/response shapes change.
- For endpoint/base-url changes, verify with direct curl against the provider
  and do not print API keys.
- `chat()` may retry timeout-safe calls; `chatStream()` must not retry after
  deltas may have been delivered.
- Provider error messages must preserve enough diagnostic context without
  logging secrets or full sensitive payloads.

## Severity

- Blocker: one provider path is broken, streamed tool IDs can drift, reasoning is
  dropped, cache fields leak, streaming retries duplicate deltas, or the model
  family is misclassified.
- Warning: tests only cover one provider, diagnostics are weak, or usage/cost
  fields are incomplete.
