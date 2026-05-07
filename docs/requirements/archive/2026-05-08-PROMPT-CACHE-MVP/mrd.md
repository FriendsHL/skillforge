# Prompt Cache MVP — MRD

---
id: PROMPT-CACHE-MVP
status: ratified
created: 2026-05-07
---

## 用户原始诉求

> 来自用户 2026-05-07：
>
> "prompt cache 这可能有记录，其实虽然有一些模型厂商还没有 prompt cache，但是我们整体的调用模型的机制要保证这些。system prompt 各个工具 schema 什么的也要尽量保持不变。"
>
> 后续调研发现：用户感知错误——**SkillForge 当前 5 个 provider 都支持 prompt cache**，只是 SkillForge 没接入：
> - **Anthropic Claude**: 必须手动标 `cache_control`，client 不动 → 永远不 cache → 每次完整 input token 计费
> - **DeepSeek / OpenAI / xiaomi-mimo / Qwen**: server 自动 cache（用户已经在 server 端省钱），但 SkillForge 没解析 usage 字段 → dashboard 看不到，无法监控

## 现状摸底（grep 验证）

| 组件 | 当前 | 问题 |
|---|---|---|
| `ClaudeProvider.buildRequestBody:397` | `root.put("system", String)` 裸字符串 | 不能加 `cache_control` |
| `ClaudeProvider.buildRequestBody:408-417` | tools 数组无任何 cache hint | tools 没法 cache |
| `OpenAiProvider.buildRequestBody:483-488` | system message 裸字符串 | 不影响（OpenAI 自动 cache，但 prefix 稳定才能持续命中） |
| `LlmResponse.Usage` | 只 `inputTokens` / `outputTokens` | 缺 `cacheReadInputTokens` / `cacheCreationInputTokens` |
| `t_llm_span.cache_read_tokens` | schema 有但**无 provider 写入路径** | OBS-1 dashboard 永远 0 |
| 5 个 configured provider | claude / deepseek / bailian (qwen) / xiaomi-mimo / openai | 都没启用 cache 解析路径 |

## 5 Provider Cache 协议总表（调研验证）

| Provider | 自动 cache | Response usage 字段 | 手动 `cache_control` |
|---|---|---|---|
| **Anthropic Claude** | ❌ | `cache_creation_input_tokens` + `cache_read_input_tokens` + `cache_creation.ephemeral_1h_input_tokens` | ✅ **必须** — 4 breakpoint，1024 token 阈值，5min TTL |
| **DeepSeek** | ✅ 默认 | `prompt_cache_hit_tokens` + `prompt_cache_miss_tokens` | ❌ 不支持 |
| **DashScope Qwen** | ✅ 隐式 | `prompt_tokens_details.cached_tokens`（OpenAI 风格）| ✅ 可选（同 Anthropic 协议，4 marker 上限）— V2 |
| **xiaomi-mimo** | ✅ 隐式 | `prompt_tokens_details.cached_tokens` + `cache_write_tokens` | 待官方 doc 确认 |
| **OpenAI gpt-4o** | ✅ 默认 | `prompt_tokens_details.cached_tokens` | ❌ 不支持 |

**调研引用**：
- [Alibaba Cloud Context Cache for Qwen](https://www.alibabacloud.com/help/en/model-studio/context-cache)
- [DeepSeek Context Caching](https://api-docs.deepseek.com/guides/kv_cache)
- [Anthropic Prompt Caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
- [OpenAI Prompt Caching](https://developers.openai.com/api/docs/guides/prompt-caching)
- [Xiaomi MiMo OpenAI API](https://platform.xiaomimimo.com/docs/api/chat/openai-api)

## 用户场景

### 场景 1：长 system prompt 的 agent loop
agent 配 5K-token system prompt（agent description + 工具用法 + skills 描述 + lifecycle hooks）。一次 chat 会话 10 轮 LLM 调用：
- **当前**：每次都全量 5K input tokens 计费 → 50K 累积
- **V1 后**（Claude）：第 2 轮起 cache hit，9 × 5K × 90% = 40.5K tokens 省（cost 降到原 ~10%）

### 场景 2：多 agent / multi-turn dashboard 观测
用户在 OBS-1 trace 详情页查 LLM 调用：
- **当前**：只能看 input_tokens，看不到哪部分被 cache
- **V1 后**：看到 `cache_read_tokens / cache_creation_tokens / hit_rate%` + cache break 事件标记（用算法识别 system prompt / tools 漂移导致 cache miss）

### 场景 3：cache 漂移诊断
某天突然 cache hit rate 从 90% 跌到 30%，用户想知道为啥：
- **当前**：没指标，靠猜
- **V1 后**：cache break 检测算法（claude code 模式：5% 容差 + 2K token 阈值）+ per-tool hash 漂移日志告警，能定位是 tool description 改了 / system prompt dynamic 段污染了稳定段 / 还是其他

## 参考实现

### claude code（`/Users/youren/myspace/claw-cli-claude-code-source-code-v2.1.88`）
- **3 breakpoint** 模式：system 末 + tools 末 + 最后一条 user message 末
- system 三段分割（header / static / dynamic）+ MCP 检测时降级
- cache break 检测算法：5% 容差 + 2K token 阈值，防小波动误报
- 不检查 1024 token 阈值（让 Anthropic server 自己处理）
- 每个 tool per-tool hash 检测漂移

### openclaw（`/Users/youren/myspace/openclaw`）
- **2 breakpoint**（system + 最后 user message）
- system 用 boundary marker（`<!-- OPENCLAW_CACHE_BOUNDARY -->`）分稳定 / 动态
- tools normalize：trim / 统一换行 / capability ids 去重排序小写
- usage 字段多 provider normalize（兼容 cached_tokens / input_tokens_details.cached_tokens 等）

### SkillForge 选择
- breakpoint 数：**3**（claude code 模式，最优）
- system 分段：**boundary marker**（openclaw 风格，简单直接）
- tools 处理：**normalize + per-tool hash**（混合两个项目）
- cache break 算法：claude code 5% / 2K 阈值
- 1024 阈值：不检查（claude code 模式）
- usage normalize：openclaw 多 provider 风格

## 范围边界

**V1 包括**：
- Phase 1 稳定 prefix（5 provider 都受益）
- Phase 2 ClaudeProvider 手动 cache_control（必做，否则 Claude 永远 0 cache）
- Phase 3 5 provider usage 解析 normalize
- Phase 4 trace + break 检测 + dashboard cache_read/creation/hit_rate 显示

**V1 不做**（V2）：
- **Qwen 手动 cache_control**（自动 cache 已 work，guaranteed hit 是 polish）
- Anthropic 1h TTL（`cache_creation.ephemeral_1h_input_tokens`，5min 默认够用）
- Messages 历史 sliding window cache（claude code 也不做）
- Cost 节省美刀估算（用户决策：显示 token 数即可，不算 cost）
- xiaomi-mimo `cache_write_tokens` 字段（需官方文档确认协议）

## 不确定 / 后续评估

- xiaomi-mimo `cache_write_tokens` 协议：保险用 OpenAI 风格 `cached_tokens`，等官方文档明确再 V2 加
- Cache hit rate 阈值告警：实际 dogfooding 看是否需要
- 多 turn agent loop 的 cache 实际命中率：V1 上线后 dashboard 真实数据决定 V2 优化点
