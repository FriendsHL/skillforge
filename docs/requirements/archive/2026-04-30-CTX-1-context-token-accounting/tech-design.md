---
id: CTX-1
type: tech-design
status: delivered
created: 2026-04-30
updated: 2026-04-30
delivered: 2026-04-30
---

# CTX-1 Tech Design — 三档触发接全量估算 + 阈值配置化 + 撞窗 retry

> 对应 [PRD](prd.md)。2026-04-30 重写,scope 从原 6 个功能缩到 3 个,纯代码改动无 schema。
>
> **Delivered 2026-04-30 注**:
> - **D2 + AC-1 实际落地**:Judge 选 (b),`ContextBreakdownService.breakdown()` 也加了 `output_reserved` segment(`Math.max(0, agentDef.getMaxTokens())`),与 engine `RequestTokenEstimator.estimate()` 包含 maxTokens 项对齐。`EngineEstimateMatchesBreakdownIT` 作为 AC-1 regression guard。
> - **Known minor delta**:dashboard segment 化(per-piece estimateString sum-of-parts)与 engine whole-string BPE boundary 之间存在 ±5 tokens/边界 偏差,在多段 systemPrompt 时显现。属 segment 模型固有副作用,不影响 0.60 阈值粒度判断,记入 follow-up。
> - **Pipeline 实际走法**:Mid 1 轮 review 对抗 + Judge NEEDS_FIX 一次 fix。Judge 终判可一次 fix 不升 Full,主会话目检 + mvn test 通过后归档。0 blocker。

## 整体架构

```
                                       ContextBreakdownService.breakdown()
                                           │  (server, dashboard 用)
                                           ↓ 调用
              ┌─────────────────────────────────────────┐
              │  RequestTokenEstimator (新, core)        │
              │  estimate(systemPrompt, messages,        │
              │           tools, maxTokens, jsonMapper)  │
              └─────────────────────────────────────────┘
                                           ↑ 调用
              ┌─────────────────────────────────────────┐
              │  AgentLoopEngine 三档触发(改造)          │
              │  ratio = estimate(request) / window      │
              │  ┌──────────────────┐  retry on overflow │
              │  │ chatStream(req)  │ ←─ catch ContextLengthExceeded
              │  └──────────────────┘     │
              │           │                ↓             │
              │           │       compactFull + retry 1次│
              │           ↓                              │
              │   LlmResponse.usage(原有路径,不动)       │
              └─────────────────────────────────────────┘
```

## 关键决策

| # | 决策 | 替代方案 | 选定理由 |
|---|---|---|---|
| D1 | 在 `skillforge-core/compact/` 加 `RequestTokenEstimator`,接受 LlmRequest-like 参数 + ObjectMapper | (a) 加方法到 LlmProvider 接口让各 provider 实现 (b) 直接在 AgentLoopEngine 内联估算 | (a) 各 provider 重复实现差异不大;(b) 与 ContextBreakdownService 算法漂移。提取到 core 共享最干净 |
| D2 | `ContextBreakdownService.breakdown()` 的 `estimateToolSchemasTokens` / 系统 prompt 估算逻辑改用 `RequestTokenEstimator`,保证与 engine 一致 | 让 ContextBreakdownService 保持自己的算法,engine 单独写一份 | 数字漂移会导致用户在 dashboard 看到的"context window pct"和实际 compact 触发不对得上 |
| D3 | 三档阈值配置在 `LlmProperties.providers[].compactThresholds`,默认值不变 | (a) 一个全局 thresholds (b) per-agent 配置 | per-provider 是合适粒度(provider chat template overhead 不同);per-agent 太细;全局太粗 |
| D4 | Retry 在 engine 层做(`AgentLoopEngine`),provider 只负责抛 `LlmContextLengthExceededException` | provider 内部 retry(类似 SocketTimeout) | retry 需要触发 compact(跨层资源),provider 不应感知 compactor;engine 是自然位置 |
| D5 | 阈值校准用 log 不持久化(P2 切出 TraceSpan estimatedRequestTokens 字段) | 加 TraceSpanEntity 字段持久化校准数据 | 一次性校准,log 足够;持久化是后续 observability 增强,不在本 wave |

## 实现拆分

### 模块 A — 新增 RequestTokenEstimator (F1 共享算法)

**新文件**:`skillforge-core/src/main/java/com/skillforge/core/compact/RequestTokenEstimator.java`

```java
package com.skillforge.core.compact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Preflight 估算 LLM 请求的 token 消耗,覆盖 systemPrompt + messages + tools + maxTokens。
 * 与 ContextBreakdownService 共享底层 TokenEstimator,保证 engine 触发与 dashboard 显示一致。
 */
public final class RequestTokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(RequestTokenEstimator.class);

    private RequestTokenEstimator() {}

    public static int estimate(String systemPrompt,
                                List<Message> messages,
                                List<ToolSchema> tools,
                                int maxTokens,
                                ObjectMapper jsonMapper) {
        int total = 0;
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            total += TokenEstimator.estimateString(systemPrompt);
        }
        if (messages != null && !messages.isEmpty()) {
            total += TokenEstimator.estimate(messages);
        }
        if (tools != null && !tools.isEmpty() && jsonMapper != null) {
            total += estimateToolSchemas(tools, jsonMapper);
        }
        // output reservation:max_tokens 是 LLM 给输出预留的上限,要从 window 里扣
        total += Math.max(0, maxTokens);
        return total;
    }

    private static int estimateToolSchemas(List<ToolSchema> tools, ObjectMapper mapper) {
        int total = 0;
        for (ToolSchema schema : tools) {
            if (schema == null) continue;
            try {
                total += TokenEstimator.estimateString(mapper.writeValueAsString(schema));
            } catch (JsonProcessingException e) {
                // Best-effort: 序列化失败时降级到 name + description
                total += TokenEstimator.estimateString(nullSafe(schema.getName()))
                       + TokenEstimator.estimateString(nullSafe(schema.getDescription()));
                log.debug("ToolSchema serialize failed, fallback to name+description", e);
            }
        }
        return total;
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
```

**修改**:`ContextBreakdownService.estimateToolSchemasTokens()` 改用 `RequestTokenEstimator.estimateToolSchemas` 等价逻辑(或重构成调 RequestTokenEstimator,但保留 segment 化结果)。**只要 token 数字一致即可,不要求结构合并**。

### 模块 B — AgentLoopEngine 三档触发改写(F1 接线)

**文件**:`skillforge-core/src/main/java/com/skillforge/core/engine/AgentLoopEngine.java`

**改动**:三处(约 line 449、466、570)

```diff
-int estTokens = TokenEstimator.estimate(messages);
+int estTokens = RequestTokenEstimator.estimate(
+        systemPrompt,            // 已构建好的 system prompt(line 558 附近)
+        messages,
+        tools,                    // 已构建好的 tools(line 561)
+        agentDef.getMaxTokens(),  // line 563
+        objectMapper);            // 注入(见下)
 double ratio = contextWindowTokens > 0 ? (double) estTokens / contextWindowTokens : 0;
```

**重排**:三档判断中第一处(B1)在 `request` 构建之前,需要把 `systemPrompt` / `tools` / `agentDef.getMaxTokens()` 的解析上移,或先构建 `LlmRequest` 再做判断。**推荐先构建 request 再判断**,因为 request 内容确定后不会改(包括 promptSuffix 的注入也可放到判断之后,因为 promptSuffix 占比通常 <500 tokens,对触发判断不敏感)。

**新增依赖**:`AgentLoopEngine` 加构造器参数 `ObjectMapper`(已在 Spring context 中,通过 Bean 注入)。

### 模块 C — 阈值配置化(F2)

**文件**:`skillforge-server/src/main/java/com/skillforge/server/config/LlmProperties.java`

**改动**:`ProviderConfig` 加可选嵌套对象

```java
public static class CompactThresholds {
    private Double softRatio = 0.60;
    private Double hardRatio = 0.80;
    private Double preemptiveRatio = 0.85;
    // getters/setters
}
private CompactThresholds compactThresholds;
```

**注入路径**:`LlmProperties` 在 server 模块,但 `AgentLoopEngine` 在 core。两种选择:

- **C-1(选)**:在 `LlmProvider` 接口加 `default CompactThresholds getCompactThresholds()`,server 层注入 provider 时把 thresholds 设上;engine 通过 provider 取
- C-2:把 thresholds 通过 LoopContext 传入(每次 chatAsync 时从 server 层注入)

C-1 更干净,因为 thresholds 是 per-provider 属性。在 core 加一个轻量值对象 `CompactThresholds` (同名,放 `core.llm`)避免跨模块依赖。

### 模块 D — chatStream 撞窗 retry(F3)

**新文件**:`skillforge-core/src/main/java/com/skillforge/core/llm/LlmContextLengthExceededException.java`

```java
package com.skillforge.core.llm;

public class LlmContextLengthExceededException extends RuntimeException {
    public LlmContextLengthExceededException(String message) { super(message); }
    public LlmContextLengthExceededException(String message, Throwable cause) { super(message, cause); }
}
```

**修改**:`ClaudeProvider` / `OpenAiProvider` 错误响应解析处:

- Claude(`ClaudeProvider.java`):`error.type == "invalid_request_error"` + message contains "prompt is too long" → 抛 `LlmContextLengthExceededException`(包装原始 error)
- OpenAI(`OpenAiProvider.java`):`error.code == "context_length_exceeded"` 或 message 含 "context length" → 同上
- 其他错误保留现有处理(IOException / 其他业务错误)

**修改**:`AgentLoopEngine` chatStream 调用处加 try/catch:

```java
boolean retried = false;
while (true) {
    try {
        // chatStream 调用(现有逻辑)
        break; // 正常退出
    } catch (LlmContextLengthExceededException overflow) {
        if (retried || compactorCallback == null) {
            throw overflow;  // 已 retry 过或 callback 不存在,直接抛
        }
        log.warn("Context overflow caught, attempting compact + retry: sessionId={}",
                 loopCtx.getSessionId(), overflow);
        CompactCallbackResult cr = compactorCallback.compactFull(
                loopCtx.getSessionId(), messages, "post-overflow",
                "context_length_exceeded:" + overflow.getMessage());
        if (cr == null || !cr.performed) {
            throw overflow;  // compact 没做(no-op / in-flight),抛
        }
        messages = cr.messages;
        loopCtx.setMessages(messages);
        request.setMessages(messages);
        retried = true;
        // 重新走 chatStream(while 循环)
    }
}
```

**注意**:retry 是**新建一次** chatStream 调用,不是在已断开的 stream 上续。这与 footgun #3("chatStream 单次不重试")不冲突,因为后者是为了避免 SocketTimeout 重发已推 delta;撞窗 retry 是新一次完整调用,delta 从零开始。

## 替代方案

### A1. 不抽 RequestTokenEstimator,直接在 AgentLoopEngine 内联估算

**否决**:与 ContextBreakdownService 算法漂移(目前已经在用 TokenEstimator 但部分计算重复实现),长期会产生数字不一致。

### A2. 在 LlmProvider 接口加 estimateRequestTokens 让各 provider 自己估

**否决**:各 provider 估算差异非常小(只是 chat template overhead 不同,可忽略,因为本来就是 ±10% 估算),分散反而增加维护负担。Provider 真要做精确计费可以自己加 method,本 wave 不需要。

### A3. 撞窗 retry 用递归而不是 while

**否决**:递归更难限制次数,且栈帧不必要。while + retried flag 更明确。

### A4. 撞窗 retry 改在 ChatService 层

**否决**:ChatService 不持有 messages 状态,无法做 compact 后 retry;engine 是自然位置(它管 loop 和 messages)。

## 风险

1. **重排 LlmRequest 构建顺序的副作用**
   - 来源:为了在 B1 判断时拿到 systemPrompt/tools/maxTokens,需要把 request 构建上移
   - 影响:原先在判断之后注入的 `promptSuffix`(waste detection / no-progress 提示) 时序可能变化
   - 缓解:promptSuffix 是 systemPrompt 后缀,可以在判断之前就拼好(注入逻辑本身不依赖判断结果)
   - **B1/B2 的 `b1RanInThisIteration` 状态机** 不动

2. **错误识别覆盖不全**
   - 来源:Claude / OpenAI / DeepSeek / Ollama / vLLM 各自 context_length 错误表达不同
   - 缓解:本 wave 实现 Claude + OpenAI 主路径;其他兼容协议如发现新模式增量加;识别失败时退化为现有错误抛出(不影响功能,只是不会 retry)

3. **撞窗 retry 触发 compact 失败的连锁**
   - 来源:context_length_exceeded → compactFull 也失败 → 状态混乱
   - 缓解:compactFull 内有 in-flight 去重(`fullCompactInFlight` Set)和 stripe lock,本 wave 加的 retry 路径是 callback 调用,会被原有去重接住;失败时直接上抛原 overflow 异常

4. **共享 RequestTokenEstimator 让 ContextBreakdownService 数字小幅漂移**
   - 来源:重构 ContextBreakdownService 调新方法时,可能与历史展示的 token 数微差(±5%)
   - 缓解:这是改进,因为之前 engine 和 dashboard 数字本来就不一致;改完后两者完全对齐,符合用户预期

5. **Mid 档对核心文件改动的覆盖度**
   - 来源:AgentLoopEngine 改三处 + 新增 retry catch,改动 lines ≈ 30-50;LlmProvider 加错误识别 ≈ 20 lines;严格按 pipeline.md 红灯应走 Full
   - 缓解:Mid 流程仍有 1 轮 reviewer 对抗 + Judge,blocker 直接升 Full;Phase Final verify 严格执行;**用户授权降档,不在 pipeline.md 红灯例外列表中**

## Pipeline 档位说明

**用户授权 Mid 降档(2026-04-30)**:

- 严格按 [pipeline.md 红灯](../../../../.claude/rules/pipeline.md#-full-的强制升级红灯) 第 1 条:触碰 AgentLoopEngine、LlmProvider 应走 Full
- 改动实质风险低:不动 schema、不动 SessionEntity 数据流、不动并发路径(锁 / streaming);只是接线 + 配置化 + 单点异常处理
- 用户判断 ROI:Full 多 1-2 轮对抗循环,对小 wave 边际收益低
- **Mid 流程仍有 reviewer 对抗 1 轮 + Judge 仲裁**;reviewer 提 blocker 立即升 Full(不抗拒)

## 实现拆分(Mid Pipeline)

按 [pipeline.md Mid 流程](../../../../.claude/rules/pipeline.md#-mid-流程):

| Phase | 内容 |
|---|---|
| Phase 1 — Dev | 1 个 Backend Dev:模块 A + B + C + D 全部 Java 代码改动 |
| Phase 2 — Review(1 轮) | 2 个 Backend Reviewer 并行(diff-in-prompt),Judge 仲裁 |
| Phase Final | Verify clipboard:`mvn test`、长会话校准跑、超长会话 retry 验证、`git commit`(等批准) |

无 frontend 改动 → 无 frontend dev / reviewer。

## 测试计划

### 单元测试

- `RequestTokenEstimatorTest`(新):覆盖各分量(system / messages / tools / maxTokens)、null/empty 边界
- `ClaudeProviderErrorTest` / `OpenAiProviderErrorTest`:覆盖 LlmContextLengthExceededException 识别(各自 fixture)
- `LlmPropertiesTest`:CompactThresholds 配置加载

### 集成测试

- `AgentLoopEngineCompactTriggerIT`(扩展现有):验证 AC-1, AC-2(三档触发分子用 RequestTokenEstimator,与 ContextBreakdownService 一致)
- `AgentLoopEngineRetryIT`(新):覆盖 AC-4, AC-5(fake provider 第一次抛 LlmContextLengthExceededException 第二次成功 / 两次都抛)
- `LlmPropertiesCompactThresholdsIT`(新):验证 AC-3(YAML 配置 vs 默认值)
- `ContextBreakdownServiceIT`(扩展):验证 ContextBreakdownService 重构后 token 数与新 RequestTokenEstimator 一致(AC-1 的另一面)

### 回归

- `CompactionServiceTest` / `CompactPersistenceIT` / `LightCompactStrategyTest` / `FullCompactStrategyTest` / `SessionMemoryCompactStrategyTest` 全部继续通过
- `ContextBreakdownServiceTest`(已有,如有):重构后输出 segment 结构不变,只是底层 token 数算法共享

### Phase Final 真实任务验证(对应 AC-7)

按 [pipeline.md Phase Final 清单](../../../../.claude/rules/pipeline.md#六phase-final-verify-清单claude-亲自做mid-和-full-共用):

- [ ] 长会话校准跑:dashboard 跑 50+ 轮会话,log 估算 vs 实测偏差;偏差 >15% 调整默认阈值并写到 delivery-index.md
- [ ] 撞窗 retry 验证:刻意构造接近 window 上限的会话,触发撞窗,确认 retry 成功
- [ ] dashboard 一致性验证:`/sessions/{id}/context-breakdown` 返回的 total 与 engine 触发判断的 estTokens 数字相等(共享算法保证)
- [ ] `mvn test` 全部通过
- [ ] PRD 决策回写 + delivery-index.md 增加交付行 + todo.md 移到"最近完成"

## 不在本 wave 范围

- ❌ Partial compact / 恢复(P9-4/P9-5)
- ❌ Token-aware quota / rate limiting
- ❌ TraceSpan 加 estimatedRequestTokens 字段(原 PRD F4,降级为 P2 follow-up)
- ❌ Dashboard UI 改动
- ❌ 新 LLM provider 接入
- ❌ Memory token estimation 进一步细化(已含在 system prompt 估算)
