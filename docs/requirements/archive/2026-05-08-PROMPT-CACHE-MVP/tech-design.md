# Prompt Cache MVP — 技术方案

---
id: PROMPT-CACHE-MVP
status: done
prd: ./prd.md
risk: Mid-Full
created: 2026-05-07
updated: 2026-05-08
---

## TL;DR

`SystemPromptBuilder` 加 boundary marker 分稳定/动态段；`ClaudeProvider.buildRequestBody` system + tools + 最后 user message 加 3 breakpoint cache_control；`UsageNormalizer` 按 protocol family 分支解析 5 provider cache usage 字段；V62 schema 加 `cache_creation_tokens`；OBS-1 dashboard 显示 hit rate / break events。

## 关键决策（用户 2026-05-07 ratify）

| # | 决策点 | 选择 | 理由 |
|---|---|---|---|
| Q1 | 范围 | **(a) MVP 4 阶段全做** | 含 messages cache + break 检测 + token 数显示，跟齐 claude code |
| Q2 | breakpoint 数 | **(a) 3 个** | system + tools + 最后 user message，claude code 模式（留 1 个 V2 给 sliding window） |
| Q3 | system 分段 | **(a) boundary marker** | `<!-- SKILLFORGE_CACHE_BOUNDARY -->` openclaw 风格，简单直接（不用 claude code 的 3 段 + MCP 检测降级） |
| Q4 | cache break 检测 | **(a) 加** | claude code 算法 5% 容差 + 2K 阈值，防小波动误报 |
| Q5 | dashboard 显示 | **(a) 显示 token 数 + hit rate**（不算 cost） | 用户决策：不依赖 model pricing 表 |
| Q6 | 文档流程 | implementation brief + 简化 mrd/prd/tech-design | 自足需求 |
| Q-V2 | Qwen 手动 cache_control | 留 V2 | Qwen 自动 cache 已 work，guaranteed hit 是 polish |
| Q-V2 | Anthropic 1h TTL | 留 V2 | 5min 默认够用 |
| Q-V2 | Messages sliding window cache | 留 V2 | claude code 也只做"最后一条 user message" |

## 架构

```
┌────────────────────────────┐
│  AgentLoopEngine           │ build LlmRequest
└──────────┬─────────────────┘
           │
┌──────────▼─────────────────┐
│  SystemPromptBuilder       │ 输出 stable + dynamic 两段（用 BOUNDARY marker）
└──────────┬─────────────────┘
           │
┌──────────▼─────────────────┐
│  Tools normalize + hash    │ trim / 统一换行 / order map keys / SHA per-tool
└──────────┬─────────────────┘
           │
┌──────────▼─────────────────┐
│  LlmProvider.buildRequest  │ ClaudeProvider 加 3 breakpoint cache_control
│   - ClaudeProvider         │ OpenAiProvider 不动 request body（自动 cache）
│   - OpenAiProvider         │
└──────────┬─────────────────┘
           │ → LLM API
┌──────────▼─────────────────┐
│  LlmProvider.parseResponse │ 用 UsageNormalizer 按 protocol family 分支
└──────────┬─────────────────┘
           │
┌──────────▼─────────────────┐
│  LlmCallObserver           │ 写 cache_read / cache_creation 到 t_llm_span
│  + CacheBreakDetector      │ tracker prev cacheRead，5% / 2K 阈值检测 break
└──────────┬─────────────────┘
           │
┌──────────▼─────────────────┐
│  OBS-1 /traces dashboard   │ hit rate / read / creation / break event 红色徽章
└────────────────────────────┘
```

## 模块设计

### 修改：`SystemPromptBuilder`（grep 找现有位置）

加方法 `buildWithBoundary(...)` 返回 `record SystemPromptParts(String stable, String dynamic)`：
- stable = agent.systemPrompt + tool 用法描述 + skills 描述 + lifecycle hooks（这些每次构造结果一致）
- dynamic = 当前时间 / 用户名 / session id / runtime metadata（每次会变）
- 拼成最终字符串时插入 `<!-- SKILLFORGE_CACHE_BOUNDARY -->`：`stable + "\n<!-- SKILLFORGE_CACHE_BOUNDARY -->\n" + dynamic`

backward compat：保留旧 `build(...)` 方法返回完整字符串（用于不支持 cache 的路径，如 logging）。

### 新建：`skillforge-core/.../llm/cache/`

```
skillforge-core/src/main/java/com/skillforge/core/llm/cache/
├── SystemPromptParts.java          // record (stable, dynamic)
├── ToolNormalizer.java              // trim / 统一 \n / sort input_schema keys / per-tool SHA256
├── ToolHashTracker.java             // 跟踪 per-tool hash，漂移时 warn log（per-session memory）
├── UsageNormalizer.java             // 按 ProviderProtocolFamily 分支解析 cache fields
├── CacheBreakDetector.java          // 5% 容差 + 2K 阈值算法 + per-session prevCacheRead 状态
└── CacheControlMarker.java          // 工具方法构造 cache_control ObjectNode
```

### 修改：`ClaudeProvider.buildRequestBody`

```java
// 改 system 为 structured array
SystemPromptParts parts = systemPromptBuilder.buildWithBoundary(...);
ArrayNode systemArray = objectMapper.createArrayNode();
systemArray.addObject()
    .put("type", "text")
    .put("text", parts.stable())
    .set("cache_control", CacheControlMarker.ephemeral());
if (!parts.dynamic().isBlank()) {
    systemArray.addObject().put("type", "text").put("text", parts.dynamic());
}
root.set("system", systemArray);

// tools 数组末尾标 cache_control
if (!sortedTools.isEmpty()) {
    ObjectNode lastToolNode = ...; // 最后一个 tool
    lastToolNode.set("cache_control", CacheControlMarker.ephemeral());
}

// messages 最后一条 user message 末尾 block 标 cache_control
JsonNode lastMsg = messagesNode.get(messagesNode.size() - 1);
if ("user".equals(lastMsg.path("role").asText())) {
    JsonNode contentArray = lastMsg.path("content");
    if (contentArray.isArray() && contentArray.size() > 0) {
        ObjectNode lastBlock = (ObjectNode) contentArray.get(contentArray.size() - 1);
        lastBlock.set("cache_control", CacheControlMarker.ephemeral());
    }
}
```

### 修改：`LlmResponse.Usage`

```java
public static class Usage {
    private int inputTokens;
    private int outputTokens;
    private int cacheReadInputTokens;       // 新增
    private int cacheCreationInputTokens;   // 新增
    // 全 4 字段 getter/setter + 兼容旧 2-arg constructor
}
```

### 修改：5 provider parseResponse → 用 UsageNormalizer

```java
// ClaudeProvider.parseResponse
LlmResponse.Usage usage = UsageNormalizer.parse(usageNode, ProviderProtocolFamily.CLAUDE);

// OpenAiProvider.parseResponse
ProviderProtocolFamily family = ProviderProtocolFamilyResolver.resolve(model);
LlmResponse.Usage usage = UsageNormalizer.parse(usageNode, family);
```

`UsageNormalizer`：
```java
public static Usage parse(JsonNode usageNode, ProviderProtocolFamily family) {
    int input = usageNode.path("input_tokens").asInt(usageNode.path("prompt_tokens").asInt(0));
    int output = usageNode.path("output_tokens").asInt(usageNode.path("completion_tokens").asInt(0));
    int cacheRead, cacheCreation;
    switch (family) {
        case CLAUDE -> {
            cacheRead = usageNode.path("cache_read_input_tokens").asInt(0);
            cacheCreation = usageNode.path("cache_creation_input_tokens").asInt(0);
        }
        case DEEPSEEK_V4, DEEPSEEK_V3, DEEPSEEK_REASONER_LEGACY -> {
            cacheRead = usageNode.path("prompt_cache_hit_tokens").asInt(0);
            cacheCreation = 0; // DeepSeek 自动 cache 无写入指标
        }
        case DASHSCOPE_QWEN, GENERIC_OPENAI -> {
            cacheRead = usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            cacheCreation = 0; // OpenAI 风格自动 cache
        }
    }
    return new Usage(input, output, cacheRead, cacheCreation);
}
```

### 新建：`LlmCallObserver` cache 写入

实现 `LlmCallObserver` 接口（grep 现有 observer 模式）：
- `onResponse(LlmCallContext ctx, LlmResponse response)`：从 response.getUsage() 读 cacheRead / cacheCreation → 写 trace span 字段
- 同时 invoke `CacheBreakDetector.check(sessionId, cacheRead)` → 如果是 break，trace span metadata 加 `cache_break: true`

### 新建：`CacheBreakDetector`

```java
@Component
public class CacheBreakDetector {
    private final Map<String, Integer> prevCacheReadBySession = new ConcurrentHashMap<>();
    private static final double TOLERANCE_RATIO = 0.95;
    private static final int MIN_DROP_TOKENS = 2000;

    public boolean check(String sessionId, int currentCacheRead) {
        Integer prev = prevCacheReadBySession.put(sessionId, currentCacheRead);
        if (prev == null) return false; // 第一次调用不算 break
        int drop = prev - currentCacheRead;
        if (drop < MIN_DROP_TOKENS) return false;       // 小波动不算
        if (currentCacheRead >= prev * TOLERANCE_RATIO) return false; // 5% 内不算
        return true; // 真 break
    }
}
```

### Tools normalize（CRITICAL — cache hit 关键）

```java
public class ToolNormalizer {
    public static String normalizeToJson(List<ToolSchema> tools, ObjectMapper mapper) {
        // 1. tools 数组按 ToolSchema.name 字典序排序（确认现有 SkillRegistry 是否已稳定排序，
        //    实测：grep skillRegistry 看返回顺序）
        // 2. 每个 tool 的 description trim 尾空格 + 统一 \r\n -> \n
        // 3. input_schema 序列化时 ObjectMapper 启用 ORDER_MAP_ENTRIES_BY_KEYS
        // 4. 返回 SHA256-stable JSON string
    }

    public static String hashTool(ToolSchema tool) {
        // SHA256(name + description + input_schema_json) → 可比对
    }
}
```

### `ToolHashTracker`（per-session memory，warn log）

```java
@Component
public class ToolHashTracker {
    private final Map<String, Map<String, String>> hashesBySession = new ConcurrentHashMap<>();

    public void track(String sessionId, List<ToolSchema> tools) {
        Map<String, String> sessionHashes = hashesBySession.computeIfAbsent(sessionId, k -> new HashMap<>());
        for (ToolSchema tool : tools) {
            String currentHash = ToolNormalizer.hashTool(tool);
            String prevHash = sessionHashes.put(tool.getName(), currentHash);
            if (prevHash != null && !prevHash.equals(currentHash)) {
                log.warn("tool '{}' schema drifted in session {}: prevHash={} newHash={} - cache will miss",
                        tool.getName(), sessionId, prevHash, currentHash);
            }
        }
    }
}
```

## 数据模型 / Migration（V62）

```sql
-- V62__add_llm_span_cache_creation_tokens.sql
ALTER TABLE t_llm_span ADD COLUMN cache_creation_tokens INTEGER;
COMMENT ON COLUMN t_llm_span.cache_creation_tokens IS
    'Anthropic prompt cache write tokens (cache_creation_input_tokens). Other providers leave NULL.';

-- 已有 t_llm_span.cache_read_tokens 字段，本期写入路径补齐（之前是 stub）
```

## 关键执行语义（INV）

| # | INV | 实现要点 |
|---|---|---|
| INV-1 | system prompt stable section 跨调用稳定 | `SystemPromptBuilder.buildWithBoundary` stable 段不含动态字段；测试 SHA256 严格相等 |
| INV-2 | tools 序列化稳定 | `ToolNormalizer` trim + 排序 + ORDER_MAP_ENTRIES_BY_KEYS |
| INV-3 | per-tool hash 漂移时 warn log（不阻塞） | `ToolHashTracker` warn 不抛异常 |
| INV-4 | Anthropic 必须手动 cache_control 才会 cache | `ClaudeProvider.buildRequestBody` 3 breakpoint 缺一不可（system / tools / 最后 user message） |
| INV-5 | OpenAI 风格 provider 不能加 cache_control | OpenAiProvider 不改 request body（cache_control 字段可能被 server 拒） |
| INV-6 | `cache_control` 数量 ≤ 4（Anthropic 上限） | MVP 用 3，留 1 个 V2 |
| INV-7 | UsageNormalizer 按 protocol family 分支精确解析 | 测试 5 provider 各 case，5 个独立单测覆盖 |
| INV-8 | cache break 5% 容差 + 2K 阈值 | `CacheBreakDetector` 实现 + 单测 |
| INV-9 | break 检测 first-call 不报 | `prevCacheReadBySession` 第一次 put 返 null → 直接 false |
| INV-10 | LlmCallObserver 异常不影响主路径 | observer onResponse try/catch 包，写 trace 失败不抛 |
| INV-11 | OBS-1 dashboard 字段 backward compat | `cache_creation_tokens` 字段 nullable，旧 trace span 无该字段 → 显示 0 |
| INV-12 | tool 排序基于现有 SkillRegistry 稳定性 | grep `getAllTools` / collectTools 看返回顺序，必要时 ToolNormalizer 内部 stable sort |

## 测试策略

### 单元测试（Mockito）
- `SystemPromptBuilderTest`：buildWithBoundary 返回 stable + dynamic 两段，marker 插入位置正确
- `ToolNormalizerTest`：trim / 换行 / order map keys / hashTool 稳定性（同输入返同 hash）
- `ToolHashTrackerTest`：漂移检测 warn log（用 LogCaptor 或类似工具）
- `UsageNormalizerTest`：5 provider 各模拟 response JSON，验证 cacheRead / cacheCreation 解析正确
- `CacheBreakDetectorTest`：first-call / 小波动 / 真 break / 同 session 多次调用
- `ClaudeProviderTest`：3 breakpoint 都加上（system / tools / 最后 user message）；request body JSON 验证 `cache_control` 字段位置

### 稳定性测试
- `PromptCacheStabilityTest`：同 LlmRequest 构造 3 次 → SHA256(stable section) + SHA256(tools array JSON) 严格相等

### 集成 IT
- 真 Claude API 调 2 次（同 system + tools），第 2 次 response 应含 `cache_read_input_tokens > 0`（如果有 ANTHROPIC_API_KEY env）
- DeepSeek API 调 2 次，第 2 次 response 含 `prompt_cache_hit_tokens > 0`

### 浏览器 e2e（Phase Final）
- 起 backend → 用 Claude provider 跑 2 轮 chat → OBS-1 trace 详情页第 2 轮显示 `cache_read_tokens > 0` + hit rate > 50%
- 故意改 tool description（如 capitalize 一个字母）→ 第 3 轮 cache miss + ToolHashTracker warn log 触发 + dashboard cache break 红色徽章

## 风险

- **system prompt build 路径**：grep 现有 `SystemPromptBuilder` / `buildSystemPrompt` 看是否已有，如果是字符串拼装散落多处，需要先抽出 stable / dynamic
- **tools 排序稳定性**：现有 SkillRegistry 是否返回稳定顺序？grep `getAllTools` / collectTools 确认；不稳定的话 `ToolNormalizer` 内部用 `sort by name`
- **ClaudeProvider 改 system structured array 后 backward compat**：之前是裸字符串，现在 Anthropic API 接 array 也行 → 看是否影响其他 path（如 logging）
- **ProviderProtocolFamily 分支完整性**：grep `ProviderProtocolFamily` 确认所有 family 在 UsageNormalizer 都有 case（漏 case 抛 IllegalStateException 或 fallback default）
- **CacheBreakDetector 内存泄漏**：`prevCacheReadBySession` Map 长期累积；MVP 接受（per-session 量级 < 1000），V2 加 LRU eviction
- **触碰 `LlmResponse.Usage` 是核心 API surface**：所有 provider parseResponse 都要改；保留 2-arg constructor backward compat 防漏改

## 实施计划

- [x] 完成 scope ratify（2026-05-07 用户 6 决策 + V1/V2 拆分）
- [x] Phase 1-4 BE 实施（1 BE dev，含 SystemPromptBuilder boundary + ClaudeProvider 3 breakpoint + UsageNormalizer 5 provider + V62 + CacheBreakDetector + Observer）
- [x] Phase 4 FE 实施（CacheStatsRow 组件 + LlmSpanDetailView 接入）
- [x] r1 + r2 + r3 对抗审查（详见下方"r1-r3 fix 记录"）
- [x] Phase Final：用户用 OpenAI-family provider e2e 验证（无 ANTHROPIC_API_KEY，dogfood 走 DeepSeek/Qwen/mimo），dashboard cache stats 行正常显示
- [x] commit + 归档

## r1-r3 对抗审查 fix 记录

**r1 reviewer**：
- BE r1：PASS / 0 blocker / 4 warning（W1 OPENAI_REASONING 缺独立测 / W2 ToolNormalizer canonicalMapper 每次 copy / W3 promptSuffix bleed 边界 / W4 redundant @Autowired）
- FE r1：**NEEDS_FIX_R2 / 2 blocker** + 2 warning：
  - **B1 hit rate denominator 算错** — `inputTokens` 是 BE 非 cached 部分，FE 拿它做分母 → cache 命中时 hit rate 显 16000% 之类荒谬值（color 阈值全失效）
  - **B2 wire shape 不匹配（silent failure）** — BE `LlmSpanDetailDto` 用 `cacheBreak` 顶层 boolean，FE 类型用 `metadata.cache_break` 嵌套 snake_case → 红色 break 徽章永远不渲染（P11 教训重演）

**Judge（team-lead 仲裁）**：
- must-fix-r2 BE：B2（Option B：BE 改 dto 暴露 metadata Map，与 FE 类型对齐）+ 顺手 W1/W2/W3/W4
- must-fix-r2 FE：B1 + W1（label） + W2（integration test）
- accept-as-is：BE C1-C5（C1 真 ANTHROPIC_API_KEY → Phase Final 用户验 / C2 in-memory state V2 LRU / C3 current_date 已隔离 / C4 OPENAI_REASONING 同 GENERIC_OPENAI / C5 LlmSpan record arity 同步）

**r2 fix**：
- BE r2: 5 fix 全做（B2 dto Map metadata + W1/W2/W3/W4）+ 新增 LlmSpanControllerMetadataTest IT；1060 tests / 0 fail
- FE r2: 3 fix（B1 prop 重命名 nonCachedInputTokens + 内部 promptTokens 聚合 / W1 label `total in → prompt` / W2 LlmSpanDetailView.cacheIntegration.test.tsx 6 wire-level case + r1 B1 broken example regression）

**FE r2 push-back（抓 architectural flaw）**：
读 BE `UsageNormalizer.java:53` 后发现 cross-provider 语义不一致：Claude wire `input_tokens` 已是非 cached，但 OpenAI-family `prompt_tokens` 是 TOTAL（含 cached）。FE 在 LlmSpanDetailView 算 promptTokens = inputTokens + cacheRead + cacheCreation 时，OpenAI-family 会把 cached 重复计入。

DeepSeek 真实场景：`prompt_tokens=1000, cacheRead=800` → FE 算 hit rate = 800/1800 = 44%（应 80%，badge 永远偏低）。

3 个解决方案中**方案 3 最干净**：BE UsageNormalizer 单点 normalize 让 inputTokens 跨 provider 统一表示"非 cached"语义。

**r3 mini-fix（BE）**：UsageNormalizer 4 个 OpenAI-family case 加 `input = Math.max(0, input - cacheRead)` 减去 cached 部分；CLAUDE / default 不减；类 + parse 方法 + LlmResponse.Usage JavaDoc 加跨 provider 统一语义合同；UsageNormalizerTest 4 OpenAI-family case 断言更新 + 新增 dirtyWireDataClampedToZero edge case（cached > total → inputTokens=0 不负）；1060 tests / 0 fail（core 188→189）。

**r2 reviewer 复审**：（跳过 r3 review，理由：BE r3 是 5 行 surgical normalize + self-check 严格 / FE r2 集成 mock 用的是 normalize 后的非 cached inputTokens 值，自然对齐）

**Phase Final 修一个 Maven 多模块 jar 不一致 bug**：spring-boot:run 加载 ~/.m2 旧 SNAPSHOT jar 触发 `NoSuchMethodError: LlmSpan.cacheCreationTokens()`。修法：`mvn install -pl skillforge-core,skillforge-tools,skillforge-observability -DskipTests` 把新 jars 安装到 .m2 后再 spring-boot:run。

## Ratified Decisions（2026-05-07 用户 ratify）

详见上文"关键决策"表 6 决策 + 3 V2 推迟。

**MVP 不做** (V2)：
- Qwen 手动 cache_control（自动 cache 已 work）
- Anthropic `cache_creation.ephemeral_1h_input_tokens` 1h TTL
- Messages 历史 sliding window cache
- Cost 美刀节省估算
- xiaomi-mimo `cache_write_tokens` 字段（官方文档不明确）
- Cache hit rate 监控告警
- LRU eviction CacheBreakDetector
- ToolHashTracker / CacheBreakDetector 持久化（重启清空 in-memory state）

## Ratified Decisions（2026-05-07 用户 ratify）

详见上文"关键决策"表。6 决策 + 3 V2 推迟。

**MVP 不做** (V2)：
- Qwen 手动 cache_control（自动 cache 已 work）
- Anthropic `cache_creation.ephemeral_1h_input_tokens` 1h TTL
- Messages 历史 sliding window cache
- Cost 美刀节省估算
- xiaomi-mimo `cache_write_tokens` 字段（官方文档不明确）
- Cache hit rate 监控告警
- LRU eviction CacheBreakDetector
