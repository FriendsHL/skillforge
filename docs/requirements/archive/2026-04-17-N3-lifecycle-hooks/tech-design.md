# N3 — 用户可配置 Lifecycle Hook 技术方案

> 生成日期：2026-04-17
> 来源：双方案 Plan Pipeline（Plan A 基础版 + Plan B 可扩展/防御版，主 Claude 融合评选）
> 参考：claude-mem 5-hook 模型（SessionStart / UserPromptSubmit / PostToolUse / Stop / SessionEnd）

---

## 1. 概述

### 1.1 目标

给 Agent 提供**可配置的生命周期钩子**，让用户在 5 个标准节点（SessionStart / UserPromptSubmit / PostToolUse / Stop / SessionEnd）各绑定 Skill，事件到达时自动执行。典型场景：

- **UserPromptSubmit hook**：prompt 内容过滤、敏感词拦截、prompt 增强
- **PostToolUse hook**：工具调用审计、结果清洗
- **SessionEnd hook**：对话摘要写入外部系统（Notion / 飞书 / 邮件）
- **SessionStart hook**：注入会话级上下文、启动外部监控

### 1.2 非目标

- **不做多事件全局广播**（Spring ApplicationEvent）—— 保持 per-agent 配置粒度
- **不做 hook 市场 / 共享配置** —— 1 个 Agent = 1 套 hook 配置，与 N2 一致
- **不做浏览器端 Hook**（前端路由/UI 生命周期）—— 本方案仅覆盖 Agent Loop 生命周期

### 1.3 术语统一（**重要**）

SkillForge 平台里 **Tool / Skill / Hook 是三个不同的概念**，历史上有过混用，本方案严格区分：

| 概念 | 定义 | 来源 | 典型示例 |
|---|---|---|---|
| **Tool** | Agent 通过 LLM `tool_use` 调用的能力原语 | 系统自带（Bash, Glob, Grep, FileRead/Write/Edit, ask_user, compact_context...）**或**用户配置 | `Bash`、`FileRead`、`TeamCreate` |
| **Skill** | 可独立运行的打包能力（含 prompt / scripts / references） | 系统自带（Memory, SubAgent）**或**用户上传 zip 包 | `MemorySkill`、`GitHubSkill`、用户自定义 `FeishuNotifySkill` |
| **Hook** | 生命周期事件的**拦截/响应**逻辑；handler 可以是**方法 / 脚本 / Skill** 等多种形式 | 系统自带（`SafetySkillHook`、`ActivityLogHook`）**或**用户配置（N3） | 系统 hook 是 Java 类；用户 hook 的 handler 是 `{type: skill\|script\|method, ...}` |

**关键澄清**：
- Hook 的 handler **不一定是 Skill**。用户可以选 Skill、写一段脚本、或引用一个平台内置方法。
- 本方案在数据层把 handler 设计为 polymorphic，P0 先实现 `type=skill`，`script` / `method` 在 P1/P2 逐步加。
- 本文档涉及 hook 时统一用"**handler**"表达"hook 要执行的那个东西"，避免把 hook 等同于 Skill。

### 1.4 设计决策总述

| 维度 | 选择 | 理由 |
|---|---|---|
| 数据结构 | `Map<Event, List<HookEntry>>` + 每 entry 含 polymorphic `handler` | Schema 从第一天定宽，handler 支持 skill/script/method，未来加新类型不迁数据 |
| **handler 类型** | `type: "skill"\|"script"\|"method"` 判别；P0 只实现 `skill` | 尊重 hook 不必是 Skill 的现实；schema 留扩展口 |
| P0 链式 | 仅取 `list[0]`，默认 CONTINUE policy | 端到端先打通，schema 保持向前兼容 |
| Dispatcher | 统一 `dispatch(event,...)` + 5 个具名 wrapper + `HandlerRunner` 策略模式 | 不同 handler 类型走不同 runner，dispatcher 自身逻辑单点 |
| Timeout | **P0 必做**（`CompletableFuture` + 独立 `hookExecutor` 线程池） | 无 timeout 的 hook 卡 30s 会挂 loop，生产不可接受 |
| SessionStart 插入点 | `ChatService.chatAsync` 的 `history.isEmpty()` 分支内 | "首次消息"语义最准，createSession 后不发消息不应触发 |
| 失败语义 | `CONTINUE` 默认 + `ABORT`（P0，UserPromptSubmit 专用） + `SKIP_CHAIN`（P1） | ABORT 是内容过滤核心 use case，不能推后 |
| 死循环防御 | ThreadLocal `hookDepth` 计数，>1 拒执行 | 比 "Skill 自防" 可靠 |
| 观测 | P0 即写 `TraceSpan(type=LIFECYCLE_HOOK)` | 配错 hook 无 trace 就无法排查 |
| 前端 P0 | 3 模式 Radio + JSON/TextArea(Zod) + 表单先选 handler type（P0 只启用 skill）+ 4 预设 | handler 类型选择器从 P0 就存在，script/method 置灰显示 "coming" |

---

## 2. 数据模型

### 2.1 存储：AgentEntity 内嵌 JSON（与 N2 对称）

沿用 N2 的 `TEXT` 列 + Jackson 反序列化模式。

### 2.2 AgentEntity 变更

```java
/** JSON: 参见 §2.4 schema */
@Column(columnDefinition = "TEXT")
private String lifecycleHooks;
```

### 2.3 AgentDefinition 变更

```java
/**
 * 结构化 Lifecycle Hook 配置，由 AgentService.toAgentDefinition() 从 JSON 解析。
 * LifecycleHookDispatcher 直接消费这个对象，不做 JSON 解析。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public static class LifecycleHooksConfig {
    private int version = 1;
    private Map<HookEvent, List<HookEntry>> hooks = new EnumMap<>(HookEvent.class);

    public enum HookEvent {
        SESSION_START,        // 对外序列化 "SessionStart"
        USER_PROMPT_SUBMIT,   // "UserPromptSubmit"
        POST_TOOL_USE,        // "PostToolUse"
        STOP,                 // "Stop"
        SESSION_END           // "SessionEnd"
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HookEntry {
        /** polymorphic —— handler 可能是 Skill / Script / Method，见 HookHandler */
        private HookHandler handler;
        private int timeoutSeconds = 30;
        private FailurePolicy failurePolicy = FailurePolicy.CONTINUE;
        private boolean async = false;
        /** 可选：用户自定义的显示名，前端展示用 */
        private String displayName;
        // getters, setters
    }

    /**
     * Hook 要执行的目标对象。polymorphic —— 通过 type 字段判别。
     * P0 只实现 skill 子类，script/method 的 runner 会在 P1/P2 补上。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.PROPERTY)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = SkillHandler.class, name = "skill"),
        @JsonSubTypes.Type(value = ScriptHandler.class, name = "script"),   // P1
        @JsonSubTypes.Type(value = MethodHandler.class, name = "method")    // P2
    })
    @JsonIgnoreProperties(ignoreUnknown = true)
    public abstract static class HookHandler {
        /** 公共字段：传给 handler 的静态参数（JSON object），运行时与动态 input 合并 */
        protected Map<String, Object> args = new HashMap<>();
    }

    /** 指向一个已注册的 Skill —— 沿用 SkillRegistry 查表 */
    public static class SkillHandler extends HookHandler {
        private String skillName;
    }

    /**
     * 内联脚本（P1 实现）
     * scriptLang 支持：bash / node / python（受 application.yml 白名单约束）
     */
    public static class ScriptHandler extends HookHandler {
        private String scriptLang;   // bash | node | python
        private String scriptBody;   // 脚本源码，传入为 stdin 或落临时文件
    }

    /**
     * 平台内置方法（P2 实现）
     * methodRef 是平台预定义方法的注册 key，不是任意反射入口。
     * 例如 "builtin.log.file"、"builtin.http.post"、"builtin.feishu.notify"
     */
    public static class MethodHandler extends HookHandler {
        private String methodRef;  // 必须在 BuiltInMethodRegistry 白名单中
    }

    public enum FailurePolicy {
        CONTINUE,     // 记 warn，继续主流程（默认）
        ABORT,        // 中断主 loop（仅 UserPromptSubmit 有意义）
        SKIP_CHAIN    // P1: 跳过本事件后续 entry，主 loop 继续
    }
}

@JsonProperty("lifecycle_hooks")
private LifecycleHooksConfig lifecycleHooks;
```

**HookEvent 枚举序列化**：Java 命名 `SESSION_START` → JSON `"SessionStart"`（通过 `@JsonValue` / `@JsonCreator`，保持 JSON 可读性）。

### 2.4 JSON Schema

```json
{
  "version": 1,
  "hooks": {
    "SessionStart": [
      {
        "handler": {
          "type": "skill",
          "skillName": "LogSessionStart"
        },
        "timeoutSeconds": 10,
        "failurePolicy": "CONTINUE",
        "async": true
      }
    ],
    "UserPromptSubmit": [
      {
        "handler": {
          "type": "skill",
          "skillName": "ContentFilter"
        },
        "timeoutSeconds": 5,
        "failurePolicy": "ABORT",
        "async": false,
        "displayName": "Prompt 内容过滤"
      }
    ],
    "PostToolUse": [
      {
        "handler": {
          "type": "script",
          "scriptLang": "bash",
          "scriptBody": "echo \"$SF_SKILL_NAME executed\" >> /tmp/audit.log",
          "args": { "logPath": "/tmp/audit.log" }
        },
        "timeoutSeconds": 15,
        "failurePolicy": "CONTINUE",
        "async": false
      }
    ],
    "Stop": [],
    "SessionEnd": [
      {
        "handler": {
          "type": "method",
          "methodRef": "builtin.feishu.notify",
          "args": { "webhook": "https://open.feishu.cn/...", "template": "session_summary" }
        },
        "timeoutSeconds": 30,
        "failurePolicy": "CONTINUE",
        "async": true
      }
    ]
  }
}
```

> **注**：P0 只实现 `handler.type == "skill"`。后端反序列化时遇到 `script` / `method` 能成功 parse（不报错），但 dispatcher 在 runner 路由时返回 `NOT_IMPLEMENTED` 并走 failurePolicy。前端表单模式对 script/method 置灰并显示 "coming in P1/P2"。

**约束**：
- `version` 固定 1，给未来 schema 升级留出识别口
- 每个事件的 list 最多 10 条（P0 只取 list[0]，超过部分忽略，P1 实现链式）
- `handler.type` 枚举外的值反序列化失败 → 整个 entry 降级跳过（记 warn）
- `handler.type == "skill"` 时 `skillName` 必须非空；执行时若 Registry 查不到降级处理
- `handler.type == "script"` 时 `scriptLang` 必须在 `application.yml` 白名单（默认 `[bash, node]`），`scriptBody` ≤ 4KB
- `handler.type == "method"` 时 `methodRef` 必须在 `BuiltInMethodRegistry` 白名单
- `timeoutSeconds` 范围 [1, 300]，默认 30
- `failurePolicy` 枚举外的值降级为 `CONTINUE`

### 2.5 Flyway Migration — `V9__agent_lifecycle_hooks.sql`

```sql
-- N3: Agent lifecycle hooks column
ALTER TABLE t_agent ADD COLUMN IF NOT EXISTS lifecycle_hooks TEXT;

COMMENT ON COLUMN t_agent.lifecycle_hooks IS
  'JSON: {"version":1,"hooks":{"SessionStart":[HookEntry...],"UserPromptSubmit":[...],"PostToolUse":[...],"Stop":[...],"SessionEnd":[...]}}';
```

---

## 3. 引擎集成

### 3.1 LifecycleHookDispatcher + HandlerRunner（策略模式）

**两层分离**：
1. **`LifecycleHookDispatcher`** 负责查表、hookDepth、timeout、failurePolicy、trace —— 与 handler 类型无关
2. **`HandlerRunner`** 接口 + 3 个实现（`SkillHandlerRunner` / `ScriptHandlerRunner` / `MethodHandlerRunner`）—— 每个实现知道怎么跑对应类型的 handler

```java
/** 统一执行结果，和 Skill 的 SkillResult 解耦（hook runner 不一定调 Skill） */
public record HookRunResult(boolean success, String output, String errorMessage, long durationMs) {}

public interface HandlerRunner<H extends HookHandler> {
    Class<H> handlerType();
    HookRunResult run(H handler, Map<String,Object> input, HookExecutionContext ctx);
}

public record HookExecutionContext(String sessionId, Long userId, HookEvent event, Map<String,Object> metadata) {}
```

**3 个 runner 的职责**（P0 只实现 Skill，其余 throw `NotImplementedException` 被 dispatcher catch）：

| Runner | P0 | 职责 |
|---|---|---|
| `SkillHandlerRunner` | ✅ | 从 `SkillRegistry` 查 Skill → 构造 `SkillContext` → `skill.execute(input, ctx)` → 包装 `HookRunResult` |
| `ScriptHandlerRunner` | ⏳ P1 | 起子进程 `bash -c` / `node -e` / `python -c`，stdin 传 input JSON，stdout 作为 output，exit code 判成败 |
| `MethodHandlerRunner` | ⏳ P2 | 从 `BuiltInMethodRegistry` 查注册的方法（`Function<Map<String,Object>, HookRunResult>`），带 args 执行 |

**Dispatcher 接口**：

```java
public interface LifecycleHookDispatcher {
    /**
     * 统一分发入口。返回 true 表示主流程继续，false 表示 ABORT。
     */
    boolean dispatch(HookEvent event,
                     Map<String, Object> input,
                     AgentDefinition agentDef,
                     String sessionId);

    // 5 个具名 wrapper，调用点用这些
    boolean fireSessionStart(AgentDefinition agentDef, String sessionId, Long userId);
    boolean fireUserPromptSubmit(AgentDefinition agentDef, String sessionId, String userMessage, int messageCount);
    void firePostToolUse(AgentDefinition agentDef, String sessionId, String skillName, Map<String,Object> skillInput, SkillResult result, long durationMs);
    void fireStop(AgentDefinition agentDef, String sessionId, int loopCount, long inputTokens, long outputTokens);
    void fireSessionEnd(AgentDefinition agentDef, String sessionId, Long userId, int messageCount, String reason);
}
```

- `fireSessionStart` / `fireUserPromptSubmit` 返回 boolean（可被 ABORT 拦截）
- `firePostToolUse` / `fireStop` / `fireSessionEnd` 返回 void（不可 ABORT）

**实现核心逻辑**（伪码）：

```java
@Component
public class LifecycleHookDispatcherImpl implements LifecycleHookDispatcher {
    private final Map<Class<? extends HookHandler>, HandlerRunner<?>> runners;  // Spring 自动注入所有 runner
    private final Executor hookExecutor;
    private final TraceCollector traceCollector;
    private static final ThreadLocal<Integer> hookDepth = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_HOOK_DEPTH = 1;

    public boolean dispatch(HookEvent event, Map<String,Object> input, AgentDefinition agentDef, String sessionId) {
        // 1. 防死循环
        if (hookDepth.get() >= MAX_HOOK_DEPTH) {
            log.warn("Skip hook {} due to depth limit", event);
            return true;
        }
        // 2. 取 list[0]（P0 仅执行第一个，P1 改为 for 循环 + SKIP_CHAIN）
        List<HookEntry> entries = agentDef.getLifecycleHooks() == null
            ? List.of()
            : agentDef.getLifecycleHooks().getHooks().getOrDefault(event, List.of());
        if (entries.isEmpty()) return true;
        HookEntry entry = entries.get(0);
        HookHandler handler = entry.getHandler();
        if (handler == null) return true;  // 防腐：null handler 直接跳过

        // 3. 路由到对应 runner
        HandlerRunner runner = runners.get(handler.getClass());
        if (runner == null) {
            log.warn("No runner for handler type {}, skipping", handler.getClass().getSimpleName());
            traceHook(event, entry, null, "runner_not_implemented", 0);
            return entry.getFailurePolicy() == FailurePolicy.ABORT ? false : true;
        }

        // 4. 执行（timeout + async/sync）
        hookDepth.set(hookDepth.get() + 1);
        HookExecutionContext execCtx = new HookExecutionContext(sessionId, agentDef.getOwnerId(), event,
            Map.of("_hook_origin", "lifecycle:" + event.name()));
        try {
            CompletableFuture<HookRunResult> fut = CompletableFuture.supplyAsync(
                () -> runner.run(handler, input, execCtx),
                hookExecutor);
            if (entry.isAsync()) {
                fut.orTimeout(entry.getTimeoutSeconds(), TimeUnit.SECONDS)
                   .whenComplete((r, ex) -> traceHook(event, entry, r, ex == null ? "ok" : "async_error", 0));
                return true;
            }
            long t0 = System.currentTimeMillis();
            HookRunResult result = fut.get(entry.getTimeoutSeconds(), TimeUnit.SECONDS);
            traceHook(event, entry, result, result.success() ? "ok" : "handler_error", System.currentTimeMillis()-t0);
            if (!result.success() && entry.getFailurePolicy() == FailurePolicy.ABORT) return false;
            return true;
        } catch (TimeoutException e) {
            log.warn("Hook {} timed out after {}s", describe(handler), entry.getTimeoutSeconds());
            traceHook(event, entry, null, "timeout", entry.getTimeoutSeconds() * 1000L);
            return entry.getFailurePolicy() == FailurePolicy.ABORT ? false : true;
        } catch (Exception e) {
            log.warn("Hook {} threw exception", describe(handler), e);
            traceHook(event, entry, null, "exception:" + e.getClass().getSimpleName(), 0);
            return entry.getFailurePolicy() == FailurePolicy.ABORT ? false : true;
        } finally {
            hookDepth.set(hookDepth.get() - 1);
        }
    }

    private String describe(HookHandler h) {
        if (h instanceof SkillHandler s) return "skill:" + s.getSkillName();
        if (h instanceof ScriptHandler s) return "script:" + s.getScriptLang();
        if (h instanceof MethodHandler m) return "method:" + m.getMethodRef();
        return h.getClass().getSimpleName();
    }
}
```

**`SkillHandlerRunner`（P0 唯一实现）**：

```java
@Component
public class SkillHandlerRunner implements HandlerRunner<SkillHandler> {
    private final SkillRegistry skillRegistry;

    public Class<SkillHandler> handlerType() { return SkillHandler.class; }

    public HookRunResult run(SkillHandler handler, Map<String,Object> input, HookExecutionContext ctx) {
        Skill skill = skillRegistry.getSkill(handler.getSkillName());
        if (skill == null) {
            return new HookRunResult(false, null, "skill_not_found:" + handler.getSkillName(), 0);
        }
        // 合并 handler.args 和动态 input
        Map<String,Object> merged = new HashMap<>(handler.getArgs());
        merged.putAll(input);
        SkillContext sctx = SkillContext.builder()
            .sessionId(ctx.sessionId())
            .userId(ctx.userId())
            .metadata(ctx.metadata())
            .build();
        long t0 = System.currentTimeMillis();
        SkillResult r = skill.execute(merged, sctx);
        return new HookRunResult(r.isSuccess(), r.getOutput(), r.getError(), System.currentTimeMillis()-t0);
    }
}
```

**依赖注入**：
- 所有 `HandlerRunner` bean（Spring 按类型自动装配为 `Map<Class<? extends HookHandler>, HandlerRunner<?>>`）
- `TraceCollector`（写 `LIFECYCLE_HOOK` span）
- `hookExecutor`：新建 Spring bean，`ThreadPoolExecutor` 核心 4 / 最大 8 / 队列 100，与 `chatLoopExecutor` 分离

**ThreadLocal 结构**：
```java
private static final ThreadLocal<Integer> hookDepth = ThreadLocal.withInitial(() -> 0);
private static final int MAX_HOOK_DEPTH = 1;  // 不允许 hook 中的 Skill 再触发 hook
```

### 3.2 引擎 5 个触发点（file:line）

| 事件 | 触发位置 | 同步 | 失败后果 |
|---|---|---|---|
| SessionStart | `ChatService.chatAsync`，`history.isEmpty()` 判断后、`executor.submit` 之前 | 同步 | ABORT 拒绝 `chatAsync`（返回 400 + error 原因） |
| UserPromptSubmit | `AgentLoopEngine.java:183` `LoopHook.beforeLoop` 链中，新增 `LifecycleHookLoopAdapter` 类实现 `LoopHook` 接口，内部调 `dispatcher.fireUserPromptSubmit` | 同步 | ABORT 让 `beforeLoop` 返回控制信号，loop 不进入 LLM 调用，session 标记为 `aborted_by_hook` |
| PostToolUse | `AgentLoopEngine.java:1217` `SkillHook.afterSkillExecute` 链中，新增 `LifecycleHookSkillAdapter` 实现 `SkillHook` 接口 | 同步（不可 ABORT） | 记 trace，loop 继续 |
| Stop | `AgentLoopEngine.java:846` `LoopHook.afterLoop` 链中，同 `LifecycleHookLoopAdapter` | 同步（不可 ABORT） | 记 trace |
| SessionEnd | `ChatService.java:384` 的 `triggerExtractionAsync` 同位置，`dispatcher.fireSessionEnd` 自身通过 `hookExecutor` 异步执行 | 异步 | 纯记 trace |

**`LifecycleHookLoopAdapter` / `LifecycleHookSkillAdapter`**：两个薄 adapter 类实现现有接口，在 `SkillForgeConfig.java:258-279` 注册，传给 `AgentLoopEngine` 构造器。它们内部持有 `LifecycleHookDispatcher` 引用，把接口回调转发到 dispatcher 的具名方法。

### 3.3 ABORT 语义的实现细节

**LoopHook 接口无原生返回值**（两个 default 方法返回 void）。要实现 ABORT，两种方案：

**方案 A（选）**：在 `LoopContext` 中加 `volatile boolean abortedByHook`，`LifecycleHookLoopAdapter.beforeLoop` 调 `dispatcher.fireUserPromptSubmit()`，若返回 false 则 `loopContext.setAbortedByHook(true)` + 抛 `HookAbortException`。`AgentLoopEngine` catch 这个异常，session 置为 error 状态，写 trace，return 给 ChatService。

**方案 B（不选）**：给 `LoopHook` 接口加返回值（`boolean beforeLoop(...)`）。侵入已有接口，破坏 `SafetySkillHook` 等现有实现的兼容性，成本高。

### 3.4 Hook Skill 输入 Schema

所有 input 都包含基础字段 `hook_event`（string）+ `session_id`（string）+ `agent_id`（long）。各事件额外字段：

| 事件 | 额外 keys |
|---|---|
| SessionStart | `agent_name`(string), `user_id`(long) |
| UserPromptSubmit | `user_message`(string), `message_count`(int) |
| PostToolUse | `skill_name`(string), `skill_input`(object), `skill_output`(string, 最多 4KB 截断), `success`(bool), `duration_ms`(long) |
| Stop | `loop_count`(int), `total_input_tokens`(long), `total_output_tokens`(long), `final_response`(string, 最多 2KB 截断) |
| SessionEnd | `user_id`(long), `message_count`(int), `reason`(string: "completed"\|"cancelled"\|"error") |

### 3.5 Hook Skill 输出处理

**P0 阶段**：所有事件的 output 只记 trace，不注入消息流。
**P1 阶段**：`UserPromptSubmit` 的 `result.getOutput()` 若含 `injected_context` 字符串（JSON 约定键名），追加到 `LoopContext.messages` 作为 `system` 消息（实现 prompt enrichment）。

**SkillContext 构造**：
- `workingDirectory`：继承当前 agent 的默认工作目录
- `sessionId`：当前 session id
- `userId`：当前 agent owner
- `metadata`：加一个 `_hook_origin: "lifecycle:<event_name>"` 键（供 Skill 识别自己是 hook 调用还是 LLM tool_use 调用）

---

## 4. REST API

### 4.1 CRUD 端点

**沿用 `PUT /api/agents/{id}`**，`lifecycle_hooks` 作为 `AgentEntity` 的 TEXT 字段一并读写。理由：与 N2 behaviorRules 完全对称，无需新 controller。

**前端请求示例**：
```http
PUT /api/agents/42
Content-Type: application/json

{
  "name": "MyAgent",
  ...
  "lifecycle_hooks": "{\"version\":1,\"hooks\":{\"SessionEnd\":[{...}]}}"
}
```

后端 `AgentService.updateAgent` 已有 setter copy 模式（参照 `behaviorRules`），加一行：
```java
existing.setLifecycleHooks(updated.getLifecycleHooks());
```

### 4.2 新增端点

**`GET /api/lifecycle-hooks/events`**：返回 5 个事件的 metadata（显示名、描述、input schema 示例、推荐 Skill 类型）。前端用于配置向导和 tooltip。

```json
[
  {
    "id": "SessionStart",
    "displayName": "会话开始",
    "description": "用户发送第一条消息时触发",
    "inputSchema": { "agent_name": "string", "user_id": "long" },
    "canAbort": true
  },
  ...
]
```

**`GET /api/lifecycle-hooks/presets`**：返回 4 个内置预设模板。前端"预设模式"的数据源。

```json
[
  {
    "id": "audit-all",
    "name": "Audit All",
    "description": "5 个事件都绑审计 Skill，适合合规场景",
    "config": { "version": 1, "hooks": { ... } }
  },
  ...
]
```

### 4.3 dry-run 端点（P2）

**`POST /api/agents/{id}/hooks/test`** 模拟触发一次 hook，返回执行结果。供用户验证配置正确。延后到 P2。

---

## 5. 前端

### 5.1 AgentList 新增 HOOKS.md Tab

第 6 个 Tab，位于 MEMORY.md 之后（或 RULES.md 之后，待用户重构时调整）。

### 5.2 3 种编辑模式（Segmented / Radio.Group 顶部切换）

**核心原则**：三模式共享同一个 `rawJson: string`，切换时：
- 进入表单模式：`JSON.parse(rawJson)` → 填表单字段
- 退出表单模式：表单字段 → `JSON.stringify` → `rawJson`
- 进入 JSON 模式：直接显示 `rawJson`
- 预设模式：点击模板 → `rawJson = JSON.stringify(template.config)`

**useLifecycleHooks 自定义 hook**（与 `useBehaviorRules` 对称）：
```ts
function useLifecycleHooks(agentId: number) {
  const [rawJson, setRawJson] = useState<string>('{"version":1,"hooks":{}}');
  const [mode, setMode] = useState<'preset'|'form'|'json'>('form');

  const { data: skills } = useQuery({ queryKey: ['skills'], queryFn: getSkills });
  const { data: events } = useQuery({ queryKey: ['hook-events'], queryFn: getLifecycleHookEvents });
  const { data: presets } = useQuery({ queryKey: ['hook-presets'], queryFn: getLifecycleHookPresets });

  const parsed = useMemo(() => safeParse(rawJson), [rawJson]);
  const jsonError = useMemo(() => validateSchema(parsed), [parsed]);

  return { rawJson, setRawJson, mode, setMode, parsed, jsonError, skills, events, presets };
}
```

### 5.3 模式 1：预设模板（Preset）

UI：一排 `Card` 或 `Radio.Button`，每个代表一个预设。点击 → 覆盖 `rawJson`。

4 个内置预设：
1. **Audit All** — 5 个事件各绑 `LoggerSkill`（审计场景）
2. **Prompt Enricher** — 仅 UserPromptSubmit 绑 `PromptEnricherSkill`
3. **Memory Writer** — 仅 SessionEnd 绑 `MemoryWriter`（`async=true`）
4. **Full Lifecycle** — SessionStart + Stop + SessionEnd 各绑不同 Skill

### 5.4 模式 2：表单（Form）— P0 主力

UI：5 个 `Card`，每个 Card 对应一个事件，包含：
- 事件名 + 说明（Tooltip 显示 input schema）
- **Handler Type 选择器**（`Radio.Group`，3 选项）：
  - `Skill`（P0 可选）—— 展开后显示 `Select` 选 Skill（从 `GET /api/skills` 加载）
  - `Script`（P1，**置灰 + Tag "coming in P1"**）—— 展开后是 lang 下拉 + 代码 textarea
  - `Method`（P2，**置灰 + Tag "coming in P2"**）—— 展开后是内置方法 Select + args JSON 编辑器
- `InputNumber`（timeoutSeconds，默认 30，范围 1-300）
- `Select`（failurePolicy，仅 UserPromptSubmit 显示 ABORT 选项）
- `Switch`（async，SessionStart/SessionEnd 推荐开启）
- `Input`（displayName，可选，前端列表显示用）

**关键**：handler type 切换时保留公共字段（timeoutSeconds / failurePolicy / async），只重置 handler 内部字段。这样用户在 P1/P2 从 Skill 切到 Script 时体验顺滑。

P0 每个事件单 entry（list 长度 0 或 1），P1 加列表 + 上移/下移支持多 entry。

### 5.5 模式 3：原生 JSON（Raw）

UI：`Input.TextArea`（12 行高度，等宽字体）+ 下方错误提示条。

**不引入 monaco-editor**（理由：前端当前无此依赖，Vite bundle 增 ~2MB，收益低；前端即将重构，可在重构时统一决策）。

**校验**：用 Zod schema（项目已有依赖）实时校验，错误显示在 TextArea 下方：
```ts
const skillHandlerSchema = z.object({
  type: z.literal('skill'),
  skillName: z.string().min(1),
  args: z.record(z.unknown()).optional(),
});
const scriptHandlerSchema = z.object({
  type: z.literal('script'),
  scriptLang: z.enum(['bash','node','python']),
  scriptBody: z.string().max(4096),
  args: z.record(z.unknown()).optional(),
});
const methodHandlerSchema = z.object({
  type: z.literal('method'),
  methodRef: z.string().min(1),
  args: z.record(z.unknown()).optional(),
});
const handlerSchema = z.discriminatedUnion('type', [
  skillHandlerSchema, scriptHandlerSchema, methodHandlerSchema
]);

const hookEntrySchema = z.object({
  handler: handlerSchema,
  timeoutSeconds: z.number().int().min(1).max(300).default(30),
  failurePolicy: z.enum(['CONTINUE','ABORT','SKIP_CHAIN']).default('CONTINUE'),
  async: z.boolean().default(false),
  displayName: z.string().optional(),
});

const lifecycleHooksSchema = z.object({
  version: z.literal(1),
  hooks: z.record(
    z.enum(['SessionStart','UserPromptSubmit','PostToolUse','Stop','SessionEnd']),
    z.array(hookEntrySchema).max(10)
  )
});
```

### 5.6 保存逻辑

点 Agent Modal 的 "保存" 按钮时：
1. 当前模式是表单 → 把表单字段序列化为 `rawJson`
2. Zod 校验 `rawJson`
3. 校验失败 → 阻止保存 + Toast 错误
4. 校验成功 → `PUT /api/agents/{id}` 带上 `lifecycle_hooks: rawJson`

### 5.7 组件文件

- `skillforge-dashboard/src/components/LifecycleHooksEditor.tsx` — 主容器（Radio 切换 + 三子组件）
- `skillforge-dashboard/src/components/lifecycle-hooks/PresetMode.tsx`
- `skillforge-dashboard/src/components/lifecycle-hooks/FormMode.tsx`
- `skillforge-dashboard/src/components/lifecycle-hooks/JsonMode.tsx`
- `skillforge-dashboard/src/hooks/useLifecycleHooks.ts`
- `skillforge-dashboard/src/constants/lifecycleHooks.ts` — Zod schema + 前端缓存的 presets
- `skillforge-dashboard/src/api/index.ts` — 新增 `getLifecycleHookEvents` / `getLifecycleHookPresets`

---

## 6. YAML round-trip（CLI 导出/导入）

`AgentYamlMapper` 加 `lifecycleHooks` 字段 round-trip，防腐处理（JSON 解析失败 → 置 null，不抛异常，参照 N2 behaviorRules 模式）。

---

## 7. 测试策略

### 7.1 Core 单元测试

**`LifecycleHookDispatcherTest`**：
- [ ] `dispatch` 在 agentDef.lifecycleHooks == null 时返回 true（无 hook 配置不影响主流程）
- [ ] Skill 不存在时返回 true（CONTINUE），返回 false（ABORT）
- [ ] Skill.execute 抛 RuntimeException 时按 policy 决定返回值
- [ ] Skill 超时时按 policy 决定返回值
- [ ] `hookDepth` 计数在嵌套 hook 中生效（mock 一个 Skill 内部调 dispatch）
- [ ] async=true 时立即返回 true，不阻塞

**`LifecycleHooksConfigSerdeTest`**：
- [ ] Jackson round-trip 正常
- [ ] JSON 含未知字段时 `ignoreUnknown` 生效
- [ ] JSON 损坏时解析降级为 null（不抛异常）
- [ ] HookEvent enum 序列化为 "SessionStart" 而非 "SESSION_START"

### 7.2 集成测试（`@SpringBootTest`）

**`ChatServiceLifecycleHookIT`**：
- [ ] 配置了 SessionStart hook 的 agent 发首条消息后，hook Skill 被调用一次
- [ ] 配置了 UserPromptSubmit + ABORT policy 的 agent，hook Skill 返回失败时 session 状态变 error
- [ ] 配置了 SessionEnd hook 的 agent，session 完成后异步执行 hook（`await` 超时 5s）
- [ ] JSON 损坏的 `lifecycle_hooks` 字段不影响 agent 启动

### 7.3 E2E（agent-browser）

- [ ] 打开 AgentList → 编辑 Agent → HOOKS.md Tab 可见
- [ ] 切换 3 种模式，数据不丢失
- [ ] 表单模式选择 Skill + 保存 → 重新打开 Modal 数据一致
- [ ] JSON 模式输入损坏 JSON → 错误提示显示 + 禁止保存
- [ ] 预设模式选 "Audit All" → 5 个事件都填上

---

## 8. 风险与边界

| 风险 | 缓解 |
|---|---|
| **死循环**：SessionEnd hook 触发 Skill 又创建 session 再触发 SessionEnd | ThreadLocal `hookDepth` 限 1 层；SessionEnd 在 finally 块异步执行，不在调用线程上下文中 |
| **hookExecutor 耗尽** | 独立 4-8 线程池 + 100 队列长度 + 队列满时 Caller Runs，不影响 chatLoopExecutor |
| **权限提升**：hook 中调用 SubAgent / TeamCreate 产生嵌套 | **P1 加黑名单**：`application.yml: lifecycle.hooks.forbidden-skills: [SubAgent, TeamCreate, TeamSend, TeamKill]`。P0 先用 hookDepth 防一层嵌套 |
| **CollabRun 子 agent**：每个子 session 各自触发自己的 hook | 本来就是期望行为（每个 AgentDefinition 独立），无需特殊处理 |
| **V9 rollback** | `ADD COLUMN IF NOT EXISTS` 向前兼容；`ignoreUnknown=true` 让字段存在但不被读取不影响 |
| **Skill 不存在导致运行时报错** | `SkillRegistry.getSkill` 返回 null，dispatcher 降级 warn + trace + policy 决策，不传播异常 |
| **JSON 手动编辑引入非法 schema** | 保存前 Zod 校验（前端）+ Jackson 反序列化防护（后端）双保险 |
| **handler type 扩展破坏向前兼容** | `@JsonTypeInfo(use=NAME, property="type")` + 未知 type 反序列化降级（Jackson `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES=false`）；`ObjectMapper` 必须在 config 显式注册 subtypes，避免 auto-discover 漂移 |
| **Script handler 注入 / 沙箱逃逸**（P1 风险） | P1 实施时：禁止 `sudo` / `curl \| sh` 等模式（复用 `SafetySkillHook` 黑名单）；子进程工作目录限定 `/tmp/sf-hook-<sessionId>/`；内存/CPU ulimit；单次输出 ≤ 64KB |
| **Method handler 白名单被绕过**（P2 风险） | `BuiltInMethodRegistry` 必须是**显式注册表**（Map 查表），绝不用反射 `Class.forName`；methodRef 必须命中 registry，未命中直接 fail |
| **已有 session 运行期间 agent 配置改了 hook** | `AgentDefinition` 在 session 创建时快照传入 loop，运行中改配置不影响当前 session（与 N2 behaviorRules 一致） |

---

## 9. 优先级拆分

### P0 — MVP（5.5 天）

| # | 子任务 | 估时 | 文件 |
|---|---|---|---|
| N3-P0-1 | V9 migration + `AgentEntity.lifecycleHooks` + `LifecycleHooksConfig` / `HookEntry` / polymorphic `HookHandler` + 3 子类 stub（`SkillHandler` 完整，`ScriptHandler` / `MethodHandler` 只定义字段，runner 留 P1/P2） | 0.5天 | `V9__agent_lifecycle_hooks.sql`, `AgentEntity.java`, `AgentDefinition.java` |
| N3-P0-2 | `AgentService.updateAgent/toAgentDefinition` 增加 `lifecycleHooks` 处理（含 JSON parse 防腐） | 0.5天 | `AgentService.java` |
| N3-P0-3 | `HandlerRunner` 接口 + `SkillHandlerRunner` 实现（P0 唯一实现，其余 runner 占位） | 0.5天 | 3 新类 |
| N3-P0-4 | `LifecycleHookDispatcher` bean（dispatch + 5 wrapper + hookDepth + timeout + runner 路由 + 独立 executor） | 1.5天 | `LifecycleHookDispatcher.java`, `LifecycleHooksExecutorConfig.java` |
| N3-P0-5 | `LifecycleHookLoopAdapter` + `LifecycleHookSkillAdapter` + 引擎接线（`SkillForgeConfig.java`） | 0.5天 | 2 新类 + `SkillForgeConfig.java` |
| N3-P0-6 | `ChatService.chatAsync` SessionStart 插入点 + `ChatService:384` SessionEnd 插入点 | 0.5天 | `ChatService.java` |
| N3-P0-7 | `TraceSpan(type=LIFECYCLE_HOOK)` 观测 + `LoopContext.abortedByHook` + `HookAbortException` | 0.5天 | `TraceCollector.java`, `LoopContext.java` |
| N3-P0-8 | 前端 `LifecycleHooksEditor` 组件 + 3 模式骨架 + `useLifecycleHooks` hook + AgentList Tab 接线（含 handler type 选择器，P0 只启用 skill） | 1.5天 | 6 新前端文件 |
| N3-P0-9 | 前端 Zod discriminatedUnion schema（handler type）+ 保存校验 + `GET /api/lifecycle-hooks/events\|presets` 后端实现 | 1天 | `LifecycleHookController.java`, `constants/lifecycleHooks.ts` |
| N3-P0-10 | 单元测试（Dispatcher + SkillHandlerRunner + Config 序列化 polymorphic）+ 集成测试（ChatService 触发 hook） | 1天 | 测试文件 |
| N3-P0-11 | `AgentYamlMapper` round-trip（含 polymorphic handler）+ CLI 导出验证 | 0.5天 | `AgentYamlMapper.java` |

### P1 — 完善（4 天）

| # | 子任务 | 估时 |
|---|---|---|
| N3-P1-1 | 多 entry 链式执行（for 循环 entries）+ `SKIP_CHAIN` policy 实现 | 0.5天 |
| N3-P1-2 | 前端表单模式支持多 entry（列表 + 上移/下移）| 1天 |
| N3-P1-3 | **`ScriptHandlerRunner` 实现**（bash/node 子进程，stdin JSON，白名单 lang，沙箱/工作目录隔离）| 1天 |
| N3-P1-4 | 前端 Script handler 表单启用（lang 下拉 + code textarea，去掉 P1 置灰）| 0.5天 |
| N3-P1-5 | `UserPromptSubmit` output 注入 `LoopContext.messages`（prompt enrichment）| 0.5天 |
| N3-P1-6 | 禁止 Skill 黑名单配置化（`application.yml: lifecycle.hooks.forbidden-skills`）| 0.5天 |
| N3-P1-7 | E2E 测试（agent-browser 覆盖 5 个交互路径 + script handler 场景）| 0.5天 |

### P2 — Nice-to-have（3 天）

| # | 子任务 | 估时 |
|---|---|---|
| N3-P2-1 | **`MethodHandlerRunner` + `BuiltInMethodRegistry`**（`builtin.log.file`、`builtin.http.post`、`builtin.feishu.notify` 3 个种子方法） | 1.5天 |
| N3-P2-2 | 前端 Method handler 表单启用（内置方法下拉 + args JSON 编辑器）| 0.5天 |
| N3-P2-3 | dry-run 端点 `POST /api/agents/{id}/hooks/test` | 1天 |
| N3-P2-4 | Traces 页面 `LIFECYCLE_HOOK` span 可视化过滤 | 0.5天 |
| N3-P2-5 | Agent detail 页显示 hook 最近触发历史（类似 `PromptHistoryPanel`）| 0.5天 |

---

## 10. 附录：5 个事件的语义精确定义

| 事件 | 精确触发时机 | 语义 | ABORT 影响 |
|---|---|---|---|
| SessionStart | User 在一个 session 里发送第一条消息时，loop 提交前 | 会话开始，用户首次 prompt 已知 | session 不进入 loop，标记 `aborted_by_hook` |
| UserPromptSubmit | 每一轮 Agent Loop 开始时（包括第一次和后续对话） | 用户 prompt 即将送给 LLM | 跳过本轮 LLM 调用，session 置 error |
| PostToolUse | 每个 tool 执行完、结果回传 LLM 之前 | tool 调用已完成，结果可检查 | 不可 ABORT，只记 trace |
| Stop | Agent Loop 结束（正常完成 / 达到 maxLoops / LLM 输出 end_turn） | agent 本轮对话结束 | 不可 ABORT |
| SessionEnd | Session 关闭（user 取消 / error 终止 / 手动关闭） | session 整体生命周期结束 | 不可 ABORT，异步执行 |

**重点**：SessionStart 只在首条消息触发一次，UserPromptSubmit 每轮都触发。不要把这两个事件设计成同义。
