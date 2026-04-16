# N1 — Memory 向量检索 技术方案

> 更新于：2026-04-16
> 关联 todo：docs/todo.md — 竞品分析新增 N1

---

## 1. 目标

将现有 `MemorySkill`（关键词精确匹配）升级为 **FTS + pgvector 混合检索**，并引入渐进披露
（`memory_search` 返回 snippet，`memory_detail` 按需取全文），在不增加新外部依赖的前提下大幅
提升记忆召回质量，同时控制每次检索的 token 消耗。

### 对比

| 维度        | 现状           | 目标                                |
| --------- | ------------ | --------------------------------- |
| 检索方式      | LIKE / 精确关键词 | FTS + pgvector cosine 两路 RRF 合并   |
| 返回内容      | 完整 content   | snippet（100字）+ memoryId，按需取全文     |
| 外部依赖      | 无            | pgvector 扩展（PostgreSQL 内置可选模块）    |
| Embedding | 无            | 复用已配置的 OpenAI-compatible provider |
| 降级        | N/A          | provider 不支持 embedding → 自动纯 FTS  |

---

## 2. 架构变化

### 新增组件

```
skillforge-core/
  └── llm/
      └── EmbeddingProvider.java          # 接口：embed(String) → float[]

skillforge-server/
  ├── service/
  │   └── EmbeddingService.java           # 调用 provider，Optional 降级
  └── skill/
      ├── MemorySearchSkill.java          # 新 Tool：混合检索，返回 snippets
      └── MemoryDetailSkill.java          # 新 Tool：按 memoryId 取全文
```

### 修改组件

```
skillforge-server/
  ├── entity/MemoryEntity.java            # 新增 embedding 字段（不做 JPA mapping）
  ├── repository/MemoryRepository.java    # 新增 FTS + 向量检索 native query
  ├── service/MemoryService.java          # 写入时异步触发 embedding
  └── skill/MemorySkill.java              # 移除 search action，保留 save/delete
```

### Flyway

```
V6__memory_vector_search.sql             # pgvector 扩展 + embedding 列 + HNSW + GIN 索引
```

---

## 3. 数据库 Schema（N1-1）

**文件：`V6__memory_vector_search.sql`**

```sql
-- 启用 pgvector 扩展（PostgreSQL 内置，无需安装额外软件）
CREATE EXTENSION IF NOT EXISTS vector;

-- 新增 embedding 列（nullable，provider 不支持时留 null，降级 FTS）
ALTER TABLE t_memory ADD COLUMN IF NOT EXISTS embedding vector(1536);

-- HNSW 索引：近似最近邻，查询比 IVFFlat 快，不需要预先 vacuum
-- vector_cosine_ops 对应 <=> 余弦距离算子
CREATE INDEX IF NOT EXISTS idx_memory_embedding
    ON t_memory USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- tsvector 全文检索列（GENERATED STORED，自动随 title/content 更新）
-- 'simple' 字典：不做词干提取，适合中英文混合
ALTER TABLE t_memory ADD COLUMN IF NOT EXISTS search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('simple',
            coalesce(title, '') || ' ' || coalesce(content, '') || ' ' || coalesce(tags, ''))
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_memory_fts
    ON t_memory USING gin(search_vector);
```

**说明：**

- `vector(1536)`：OpenAI text-embedding-3-small / DashScope text-embedding-v3 默认维度，通过配置可覆盖
- HNSW vs IVFFlat：HNSW 无需预建聚簇中心，记忆条目写入后立即可查，更适合 SkillForge 场景
- GENERATED STORED：免手动维护 tsvector，insert/update 自动触发

---

## 4. EmbeddingProvider 接口（skillforge-core）

**文件：`skillforge-core/.../llm/EmbeddingProvider.java`**

```java
package com.skillforge.core.llm;

/**
 * 文本 Embedding 接口，对应 OpenAI-compatible POST /v1/embeddings。
 * 实现类不要求线程安全：调用方通过 EmbeddingService 序列化访问。
 */
public interface EmbeddingProvider {

    /**
     * 将文本转为向量。
     *
     * @param text 输入文本
     * @return float 向量，长度由模型决定
     * @throws EmbeddingNotSupportedException provider 不支持 embedding 时抛出
     */
    float[] embed(String text);

    /**
     * 向量维度，用于 DDL 建表时确定 vector(N)。
     * 默认 1536。
     */
    default int dimension() {
        return 1536;
    }
}
```

**文件：`skillforge-core/.../llm/EmbeddingNotSupportedException.java`**

```java
package com.skillforge.core.llm;

public class EmbeddingNotSupportedException extends RuntimeException {
    public EmbeddingNotSupportedException(String providerName) {
        super("Provider '" + providerName + "' does not support embeddings");
    }
}
```

---

## 5. OpenAiEmbeddingProvider（skillforge-core）

OpenAI-compatible 的 `/v1/embeddings` API 和 `/v1/chat/completions` 格式一致，复用 OkHttp client。

**文件：`skillforge-core/.../llm/OpenAiEmbeddingProvider.java`**

```java
package com.skillforge.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible embedding provider。
 * 适配：OpenAI、DeepSeek、通义千问、DashScope、月之暗面等所有兼容 /v1/embeddings 的 API。
 */
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int dimension;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiEmbeddingProvider(String apiKey, String baseUrl, String model, int dimension) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.dimension = dimension;
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", List.of(text)
            ));
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new EmbeddingNotSupportedException(
                            "HTTP " + response.code() + " from embedding API");
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode vector = root.path("data").path(0).path("embedding");
                float[] result = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    result[i] = (float) vector.get(i).asDouble();
                }
                return result;
            }
        } catch (EmbeddingNotSupportedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Embedding request failed: {}", e.getMessage());
            throw new EmbeddingNotSupportedException("request failed: " + e.getMessage());
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
```

---

## 6. EmbeddingService（skillforge-server）

**文件：`skillforge-server/.../service/EmbeddingService.java`**

```java
package com.skillforge.server.service;

import com.skillforge.core.llm.EmbeddingNotSupportedException;
import com.skillforge.core.llm.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Embedding 服务。
 *
 * <p>复用已配置的 OpenAI-compatible provider；provider 不支持 embedding 时返回
 * {@code Optional.empty()}，调用方降级为纯 FTS，不报错。
 *
 * <p>不引入 Ollama 或其他新外部依赖。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    // null 表示当前没有可用的 embedding provider，所有检索退化为 FTS-only
    private final EmbeddingProvider provider;

    public EmbeddingService(EmbeddingProvider provider) {
        this.provider = provider;
    }

    /**
     * 生成文本向量。
     *
     * @return 向量，或 empty（provider 不支持 / 调用失败时）
     */
    public Optional<float[]> embed(String text) {
        if (provider == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(provider.embed(text));
        } catch (EmbeddingNotSupportedException e) {
            log.debug("Embedding not supported: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Embedding failed, falling back to FTS-only: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
```

**EmbeddingProvider Bean 的注册（`SkillForgeAutoConfiguration` 或独立 `@Configuration`）：**

```java
@Bean
@ConditionalOnProperty(name = "skillforge.embedding.enabled", havingValue = "true")
public EmbeddingProvider embeddingProvider(
        @Value("${skillforge.embedding.api-key}") String apiKey,
        @Value("${skillforge.embedding.base-url:https://api.openai.com}") String baseUrl,
        @Value("${skillforge.embedding.model:text-embedding-3-small}") String model,
        @Value("${skillforge.embedding.dimension:1536}") int dimension) {
    return new OpenAiEmbeddingProvider(apiKey, baseUrl, model, dimension);
}

// provider 未配置时注入 null，EmbeddingService 自动降级
@Bean
@ConditionalOnMissingBean(EmbeddingProvider.class)
public EmbeddingProvider noOpEmbeddingProvider() {
    return null;
}
```

`application.yml` 示例（复用现有 provider 的 key，只需配置 embedding model）：

```yaml
skillforge:
  embedding:
    enabled: true
    api-key: ${OPENAI_API_KEY}
    base-url: https://api.openai.com   # 或 DashScope / DeepSeek 的 baseUrl
    model: text-embedding-3-small
    dimension: 1536
```

---

## 7. MemoryService 写入时触发 Embedding（N1-2）

在 `MemoryService.createMemory()` 和 `updateMemory()` 中，用 `@Async` 异步写 embedding，
不阻塞主流程。embedding 失败时记 warn log，列留 null，检索自动降级 FTS。

```java
@Service
public class MemoryService {

    private final MemoryRepository memoryRepository;
    private final EmbeddingService embeddingService;

    // ... 现有方法 ...

    @Transactional
    public MemoryEntity createMemory(MemoryEntity memory) {
        MemoryEntity saved = memoryRepository.save(memory);
        triggerEmbeddingAsync(saved.getId(), buildEmbedText(saved));
        return saved;
    }

    @Async
    protected void triggerEmbeddingAsync(Long memoryId, String text) {
        embeddingService.embed(text).ifPresent(vec ->
                memoryRepository.updateEmbedding(memoryId, vec));
    }

    private String buildEmbedText(MemoryEntity m) {
        // title + content 合并，tags 附加，保证向量语义完整
        StringBuilder sb = new StringBuilder();
        if (m.getTitle() != null) sb.append(m.getTitle()).append("\n");
        if (m.getContent() != null) sb.append(m.getContent());
        if (m.getTags() != null) sb.append("\nTags: ").append(m.getTags());
        return sb.toString();
    }
}
```

---

## 8. MemoryRepository — 混合检索 Query（N1-3）

```java
public interface MemoryRepository extends JpaRepository<MemoryEntity, Long> {

    // FTS 召回：tsvector @@ plainto_tsquery（'simple' 字典，中英文兼容）
    @Query(value = """
        SELECT id, type, title, content, tags, recall_count,
               ts_rank(search_vector, plainto_tsquery('simple', :query)) AS rank
        FROM t_memory
        WHERE user_id = :userId
          AND search_vector @@ plainto_tsquery('simple', :query)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByFts(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("limit") int limit);

    // 向量召回：余弦距离（<=> 越小越近）
    @Query(value = """
        SELECT id, type, title, content, tags, recall_count,
               (embedding <=> CAST(:embedding AS vector)) AS distance
        FROM t_memory
        WHERE user_id = :userId
          AND embedding IS NOT NULL
        ORDER BY distance ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByVector(
            @Param("userId") Long userId,
            @Param("embedding") String embedding,   // pgvector 接受 '[0.1,0.2,...]' 格式字符串
            @Param("limit") int limit);

    // 异步更新 embedding 列
    @Modifying
    @Transactional
    @Query(value = "UPDATE t_memory SET embedding = CAST(:embedding AS vector) WHERE id = :id",
           nativeQuery = true)
    void updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding);
}
```

**注意**：`float[]` → pgvector 字符串转换在 Service 层完成：

```java
private String toVectorString(float[] vec) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vec.length; i++) {
        if (i > 0) sb.append(',');
        sb.append(vec[i]);
    }
    return sb.append(']').toString();
}
```

---

## 9. RRF 合并算法

Reciprocal Rank Fusion：将 FTS rank 和 vector rank 合并，不依赖两路分数的量纲一致性。

```java
/**
 * RRF 合并：FTS 结果 + 向量结果 → 统一排序
 *
 * @param ftsResults    FTS 召回，已按 ts_rank 降序
 * @param vectorResults 向量召回，已按 cosine distance 升序（distance 越小越好）
 * @param topK          返回条数
 */
private List<MemorySearchResult> mergeWithRrf(
        List<MemorySearchResult> ftsResults,
        List<MemorySearchResult> vectorResults,
        int topK) {

    int K = 60; // RRF 常数，业界标准值
    Map<Long, Double> scores = new HashMap<>();

    for (int i = 0; i < ftsResults.size(); i++) {
        long id = ftsResults.get(i).memoryId();
        scores.merge(id, 1.0 / (K + i + 1), Double::sum);
    }
    for (int i = 0; i < vectorResults.size(); i++) {
        long id = vectorResults.get(i).memoryId();
        scores.merge(id, 1.0 / (K + i + 1), Double::sum);
    }

    // 合并两路候选，按 RRF score 降序取 topK
    Map<Long, MemorySearchResult> byId = new HashMap<>();
    ftsResults.forEach(r -> byId.put(r.memoryId(), r));
    vectorResults.forEach(r -> byId.putIfAbsent(r.memoryId(), r));

    return scores.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> byId.get(e.getKey()).withScore(e.getValue()))
            .toList();
}
```

---

## 10. MemorySearchSkill — Tool 实现（N1-3）

**功能**：Agent 调用此 Tool 进行记忆检索，只返回 snippet + memoryId，不返回全文。

```java
public class MemorySearchSkill implements Skill {

    private static final int FTS_LIMIT = 20;
    private static final int VEC_LIMIT = 20;
    private static final int DEFAULT_TOP_K = 5;

    private final MemoryService memoryService;
    private final EmbeddingService embeddingService;

    @Override
    public String getName() { return "memory_search"; }

    @Override
    public String getDescription() {
        return """
            Search long-term memories by semantic similarity and keyword matching.
            Returns a ranked list of snippets (first 100 chars) with memoryId.
            Call memory_detail to fetch the full content of a specific memory.
            Use this before answering any question that might benefit from past context.
            """;
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        Long userId = toLong(input.get("userId"));
        String query = (String) input.get("query");
        int topK = toInt(input.get("topK"), DEFAULT_TOP_K);

        if (userId == null || query == null || query.isBlank()) {
            return SkillResult.error("userId and query are required");
        }

        // FTS 召回（始终执行）
        List<MemorySearchResult> ftsResults = memoryService.searchByFts(userId, query, FTS_LIMIT);

        // 向量召回（有 embedding 时执行）
        List<MemorySearchResult> vectorResults = embeddingService.embed(query)
                .map(vec -> memoryService.searchByVector(userId, vec, VEC_LIMIT))
                .orElse(List.of());

        // RRF 合并
        List<MemorySearchResult> merged = mergeWithRrf(ftsResults, vectorResults, topK);

        if (merged.isEmpty()) {
            return SkillResult.success("No memories found for: " + query);
        }

        String output = merged.stream()
                .map(r -> String.format("[id=%d, type=%s, score=%.3f] %s: %s",
                        r.memoryId(), r.type(), r.score(), r.title(), r.snippet()))
                .collect(Collectors.joining("\n"));

        return SkillResult.success("Found " + merged.size() + " memories:\n" + output);
    }
}
```

**Tool Schema：**

```json
{
  "name": "memory_search",
  "description": "...",
  "input_schema": {
    "type": "object",
    "properties": {
      "userId":  { "type": "integer" },
      "query":   { "type": "string", "description": "Natural language search query" },
      "topK":    { "type": "integer", "description": "Max results to return (default 5)" }
    },
    "required": ["userId", "query"]
  }
}
```

---

## 11. MemoryDetailSkill — Tool 实现（N1-4）

**功能**：Agent 按 memoryId 取全文，避免 search 阶段一次性传入大量 token。

```java
public class MemoryDetailSkill implements Skill {

    private final MemoryService memoryService;

    @Override
    public String getName() { return "memory_detail"; }

    @Override
    public String getDescription() {
        return """
            Retrieve the full content of a specific memory by its ID.
            First call memory_search to get memoryId, then call this tool for full text.
            """;
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        Long memoryId = toLong(input.get("memoryId"));
        if (memoryId == null) {
            return SkillResult.error("memoryId is required");
        }

        return memoryService.findById(memoryId)
                .map(m -> SkillResult.success(
                        "[" + m.getType() + "] " + m.getTitle() + "\n\n" + m.getContent()))
                .orElse(SkillResult.error("Memory not found: id=" + memoryId));
    }
}
```

---

## 12. MemorySkill 调整

现有 `MemorySkill` 只保留 `save` 和 `delete` action，移除 `search`。
搜索能力由 `MemorySearchSkill` 和 `MemoryDetailSkill` 承接。

**修改后的 action enum：**

```json
"enum": ["save", "delete"]
```

**Tool 注册（SystemSkillLoader）：**

```java
// 新增两个 Skill 注册
registry.register(new MemorySearchSkill(memoryService, embeddingService));
registry.register(new MemoryDetailSkill(memoryService));
```

---

## 13. MemorySearchResult DTO

```java
public record MemorySearchResult(
        long memoryId,
        String type,
        String title,
        String content,  // 全文，snippet() 取前 100 字
        double score
) {
    public String snippet() {
        if (content == null) return "";
        return content.length() <= 100 ? content : content.substring(0, 100) + "…";
    }

    public MemorySearchResult withScore(double newScore) {
        return new MemorySearchResult(memoryId, type, title, content, newScore);
    }
}
```

---

## 14. 降级策略总结

```
EmbeddingProvider Bean 注入？
  ├── 否 → embeddingService.embed() 始终返回 Optional.empty()
  └── 是 → 调用 /v1/embeddings
              ├── 成功 → FTS + vector 双路 RRF
              └── 失败（HTTP 4xx/5xx / 超时）→ Optional.empty() → 纯 FTS
```

降级对 Agent 完全透明，`memory_search` 返回结果格式不变，只是 score 基于纯 FTS rank。

---

## 15. 实施顺序

| 步骤  | 内容                                                       | 依赖  |
| --- | -------------------------------------------------------- | --- |
| ①   | Flyway V6 migration（pgvector + 列 + 索引）                   | 无   |
| ②   | EmbeddingProvider 接口 + OpenAiEmbeddingProvider + Bean 注册 | ①   |
| ③   | EmbeddingService（含降级）                                    | ②   |
| ④   | MemoryRepository 新增 FTS/向量 query + updateEmbedding       | ①   |
| ⑤   | MemoryService 写入时异步触发 embedding                          | ③④  |
| ⑥   | MemorySearchSkill + MemoryDetailSkill 实现 + 注册            | ③④⑤ |
| ⑦   | MemorySkill 移除 search action                             | ⑥   |
| ⑧   | 集成测试 + browser 验证                                        | ⑥⑦  |

---

## 16. 测试策略

### 单元测试

- `OpenAiEmbeddingProvider`：mock OkHttpClient，验证请求格式 + 响应解析 + 失败抛 EmbeddingNotSupportedException
- `EmbeddingService`：provider=null 时返回 empty；provider 抛异常时返回 empty（不传播）
- RRF 合并函数：两路结果 overlap / 互斥 / 一路为空 三种场景

### 集成测试（Testcontainers）

- 启用 pgvector 扩展的 PostgreSQL 容器
- `MemoryRepository.findByFts` + `findByVector`：验证索引正确命中
- `MemoryService.createMemory` → 异步 embedding → `updateEmbedding` 落库

### 降级测试

- `EmbeddingProvider` Bean 缺失时，`memory_search` 正常返回 FTS 结果，无异常

---

## 17. 配置参考

```yaml
skillforge:
  embedding:
    enabled: true                        # false → 纯 FTS 模式
    api-key: ${EMBEDDING_API_KEY}        # 可与 LLM provider 的 key 相同
    base-url: https://api.openai.com    # DashScope: https://dashscope.aliyuncs.com/compatible-mode
    model: text-embedding-3-small       # DashScope: text-embedding-v3
    dimension: 1536                      # DashScope text-embedding-v3: 1024 or 1536
```

DashScope（通义千问）的 embedding endpoint 完全兼容 OpenAI `/v1/embeddings` 格式，无需额外适配。
