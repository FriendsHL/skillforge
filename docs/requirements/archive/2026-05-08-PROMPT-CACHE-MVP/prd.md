# Prompt Cache MVP — PRD

---
id: PROMPT-CACHE-MVP
status: done
owner: youren
priority: P1
risk: Mid-Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-07
updated: 2026-05-08
---

## 摘要

启用 SkillForge 5 provider 全链路 prompt cache 支持。MVP 4 阶段，V1 不含 Qwen 手动 cache_control / messages 历史 cache / cost 估算。

## 目标

1. **稳定 prefix**：system + tools 序列化跨调用稳定（boundary marker 分段 + tools normalize + per-tool hash 漂移检测）
2. **Anthropic 手动 cache_control**：ClaudeProvider 必须改（否则永远不 cache）
3. **5 provider usage normalize**：统一字段 `cacheReadInputTokens` + `cacheCreationInputTokens`，按 protocol family 分支适配
4. **可观测性**：trace 写 cache 字段 + dashboard 显示 hit rate / read tokens / creation tokens + cache break 检测算法

## 非目标（V2）

- Qwen 手动 cache_control（DashScope 同 Anthropic 协议支持，但 Qwen 自动 cache 已 work）
- Anthropic 1h TTL 字段解析（`cache_creation.ephemeral_1h_input_tokens`）
- Messages 历史 sliding window cache（claude code 也不做）
- Cost 美刀节省估算（用户决策：显示 token 数即可，不依赖 model pricing 表）
- xiaomi-mimo `cache_write_tokens` 字段（官方文档不明确，保险用 `cached_tokens`）
- Cache hit rate 监控告警

## 功能需求

### Phase 1: 稳定 prefix（所有 provider 受益）

- **System prompt 分段**：build 时插入 `<!-- SKILLFORGE_CACHE_BOUNDARY -->` marker；marker 前 = stable section（agent.systemPrompt + tools 用法描述 + skills 描述 + lifecycle hooks），marker 后 = dynamic section（当前时间 / 用户名 / session metadata）
- **Tools normalize**：序列化前 trim 尾空格 + 统一 `\n` 换行；input_schema 字段顺序固定（ObjectMapper 启用 `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`）
- **Per-tool hash**：每个 tool serialize 后算 SHA256 → 跟前次对比，漂移时 warn log（`tool '{name}' schema drifted: prevHash=... newHash=...`）
- **测试**：同 LlmRequest 构造 3 次 → SHA256(stable section + tools array) 严格相等

### Phase 2: ClaudeProvider 加 3 cache breakpoint

```java
// Breakpoint 1: system stable section 末尾
ArrayNode systemArray = createArrayNode();
systemArray.addObject()
    .put("type", "text")
    .put("text", stableSection)
    .set("cache_control", obj("type", "ephemeral"));
// dynamic 段单独 block 不标
if (!dynamicSection.isBlank()) {
    systemArray.addObject().put("type", "text").put("text", dynamicSection);
}

// Breakpoint 2: tools 数组末尾（最后一个 tool 标）
ObjectNode lastToolNode = ...;
lastToolNode.set("cache_control", obj("type", "ephemeral"));

// Breakpoint 3: messages 最后一条 user message 末尾 block（claude code 第 3089 行模式）
if (lastMessage.role.equals("user")) {
    ObjectNode lastBlock = lastMessage.content.last();
    lastBlock.set("cache_control", obj("type", "ephemeral"));
}
```

**4 breakpoint 上限**：MVP 用 3，留 1 个给 V2（messages sliding window）。

### Phase 3: 5 provider Usage 解析 normalize

`LlmResponse.Usage` 加 2 字段：
```java
private int cacheReadInputTokens;
private int cacheCreationInputTokens;
```

新建 `UsageNormalizer` 工具类按 `ProviderProtocolFamily` 分支适配：
| Provider 家族 | parse 逻辑 |
|---|---|
| CLAUDE | `cache_creation_input_tokens` (creation) + `cache_read_input_tokens` (read) |
| DEEPSEEK_V3 / V4 / R1 | `prompt_cache_hit_tokens` (read) + creation=0（自动 cache 无 write 指标） |
| DASHSCOPE_QWEN | `prompt_tokens_details.cached_tokens` (read) + creation=0 |
| GENERIC_OPENAI（含 OpenAI / xiaomi-mimo / vLLM / Ollama） | `prompt_tokens_details.cached_tokens` (read) + creation=0（mimo 的 `cache_write_tokens` V2 加） |

### Phase 4: Trace + Dashboard

- `t_llm_span` 加 `cache_creation_tokens INT` 字段（已有 `cache_read_tokens`），V62 migration
- `LlmSpan` domain + `LlmSpanEntity` + `LlmSpanDetailDto` + `PgLlmTraceStore.save` 同步加 cacheCreationTokens
- `LlmCallObserver` 实现：`onResponse(usage)` 时写入 trace（read + creation）
- **Cache break 检测**（claude code 算法）：tracker 跟踪 prev session cache_read，当前 cacheRead < prev × 0.95 且 drop > 2K → 写 trace metadata `cache_break: true` + reason
- OBS-1 `/traces` LLM 调用详情面板：
  - 显示 `cache_read_tokens` / `cache_creation_tokens` / `total_input_tokens`
  - **Hit rate** = `cache_read / total_input × 100%`
  - Break event 标记（红色徽章 "cache broken at this turn"）

## 验收标准

### 后端
- [ ] V62 migration 加 `t_llm_span.cache_creation_tokens` 字段
- [ ] `LlmResponse.Usage` 加 `cacheReadInputTokens` / `cacheCreationInputTokens` 字段 + getter/setter
- [ ] `SystemPromptBuilder` 支持 boundary marker + 输出 stable / dynamic 两段
- [ ] `ClaudeProvider.buildRequestBody` system 改 structured array + 3 breakpoint cache_control（system + tools + 最后 user message）
- [ ] `ClaudeProvider.parseResponse` 解析 `cache_creation_input_tokens` / `cache_read_input_tokens`
- [ ] `OpenAiProvider.parseResponse` 按 protocol family 分支解析 cache 字段（DeepSeek / Qwen / OpenAI / xiaomi-mimo）
- [ ] `UsageNormalizer` 工具类 + 5 provider 单测
- [ ] `LlmCallObserver` 写 cache_read / cache_creation 进 trace
- [ ] Cache break 检测算法（5% 容差 + 2K 阈值）+ break 事件写 trace metadata
- [ ] **稳定性测试**：同 LlmRequest 构造 3 次 → stable section + tools SHA256 严格相等
- [ ] **Per-tool hash 漂移测试**：tool description 改一个空格 → warn log 触发
- [ ] `mvn test` 全套绿（baseline 1057 + 新增）

### 前端
- [ ] OBS-1 `/traces` LLM 调用详情显示 cache_read / cache_creation / total_input + hit rate %
- [ ] Cache break 事件红色徽章显示
- [ ] `LlmSpanDetailDto` 类型加 `cacheCreationTokens` 字段
- [ ] `npx tsc --noEmit` + `npm run build` 通过

### 整体
- [ ] Phase Final 浏览器 e2e：起 backend → 用 Claude provider 跑 2 轮 chat（同 system prompt）→ trace 详情页第 2 轮显示 `cache_read_tokens > 0` + hit rate > 50%
- [ ] DeepSeek / Qwen / xiaomi-mimo 任一 provider 跑 2 轮 → cache_read > 0（自动 cache 验证 usage 解析正确）

## 验证预期

- 单元测试：UsageNormalizer 5 provider 各 case + ClaudeProvider 3 breakpoint + SystemPromptBuilder marker / tools normalize
- 稳定性测试：SHA256(stable section) 跨构造严格相等
- Per-tool hash 漂移：tool 修改触发 warn log
- 集成 IT：模拟 5 provider response JSON → UsageNormalizer 解析正确
- 浏览器 e2e（Phase Final）：真 Claude API 跑 2 轮验证 cache 真命中
- 数据库 migration：V62 cache_creation_tokens 字段
