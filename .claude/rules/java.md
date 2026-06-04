---
paths:
  - "**/*.java"
---
# Java Rules for SkillForge

Java 17 + Spring Boot 3.2 + JPA/Hibernate + Maven multi-module 项目规范。

---

## ⚠️ Known Footguns（踩过的坑，触碰相关代码必读）

### 1. ObjectMapper 必须注册 JavaTimeModule

项目中存在大量 `new ObjectMapper()`，但 `SessionEntity` 等实体使用了 `Instant` / `LocalDateTime`。
未注册 `JavaTimeModule` 的 `ObjectMapper` 序列化这些类型时**不会报错，而是输出错误的时间戳格式**，极难排查。

```java
// BAD — 无法正确处理 Instant / LocalDateTime
private final ObjectMapper objectMapper = new ObjectMapper();

// GOOD — 必须注册 JavaTimeModule
private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```

在 Spring context 内优先注入 Spring 管理的 `ObjectMapper` Bean（已配好所有模块），不要手动 `new`。

### 2. 时间字段统一用 Instant

数据库持久化字段统一使用 `Instant`，不要混用 `LocalDateTime`。`LocalDateTime` 不带时区，跨时区环境会出错。

```java
// BAD — 混用
private Instant lastCompactedAt;
private LocalDateTime createdAt;   // ← 不一致

// GOOD — 统一 Instant（新增字段）
private Instant createdAt;
private Instant updatedAt;
```

> 注：`createdAt` / `updatedAt` 字段当前是 `LocalDateTime`，历史遗留，**不要扩大**，新字段一律用 `Instant`。

### 3. LlmProvider 新实现必须处理 SocketTimeoutException 重试

参照 `ClaudeProvider`：`chat()` 方法只对 `SocketTimeoutException` 重试，其他 `IOException` 快速失败。
流式路径（`chatStream`）**不重试**——重试会导致已推送的 delta 重复交付给 handler。

### 4. 持久化层 vs Engine 内存层 Message 形态必须字节一致

ChatService 持久化进 `t_session_message.content_json` 的 Message 跟 AgentLoopEngine 内存 `messages` 列表里的同一条 logical message，**JSON 序列化字节必须完全相等**。否则 `SessionService.updateSessionMessages` 内部 `commonPrefixSize + messageEquals`（字节比较）对账时会把发散点之后的内容当 delta silently dup-append（Q2 commit `bdb0453` 这就是这条 invariant 的反面教材，Q3 `cc87776` 修；commit `b2c7039` 加 mid-prefix divergence guard 兜底）。

```java
// BAD — Q2 era 半残品（修于 Q3 cc87776）
ChatService.chatAsync:
    Message userMsg = buildUserMessageWithReminder(...);   // array-shape
    sessionService.appendNormalMessages(..., userMsg);       // 持久化 array
    chatLoopExecutor.execute(() -> runLoop(sessionId, userMessage, ...));   // ← 只透传 String

AgentLoopEngine.runInternal(... String userMessage ...):
    messages.add(Message.user(userMessage));   // ← 重建 String-shape！跟持久化形态不一致

// GOOD — Q3 后
ChatService.chatAsync:
    Message userMsg = buildUserMessageWithReminder(...);
    sessionService.appendNormalMessages(..., userMsg);
    final Message userMsgWithReminder = userMsg;             // ← capture object reference
    chatLoopExecutor.execute(() -> runLoop(..., userMsgWithReminder, ...));

AgentLoopEngine.run(AgentDef, String, Message userMessageBlock, List, ..., LoopContext):
    if (userMessageBlock != null) {
        messages.add(userMessageBlock);   // ← 同一个 Java 对象引用，字节必然一致
    } else if (userMessage != null) {
        messages.add(Message.user(userMessage));
    }
```

**完整 invariant + 4 触发条件 + roundtrip 测试模板**见 [`persistence-shape-invariant.md`](persistence-shape-invariant.md)（触碰 ChatService / AgentLoopEngine / CompactionService / SessionService.rewriteMessages / Message / ContentBlock 时必读）。

### 5. 给 t_session_message 加 identity 列后必须扩展 rewriteMessages preserve 逻辑

`SessionService.rewriteMessages` 走 DELETE+INSERT 模式。3-arg `AppendMessage` 默认所有 identity 列（`controlId` / `answeredAt` / `traceId` / 未来候选 `origin_span_id`）为 null。Q1 commit `a4100f7` 给 `trace_id` 加了 `snapshotTraceIds + patchTraceIds`，但**只覆盖 trace_id 一列**。新加 identity 列必须扩展同样模式，否则 rewrite 后该列被 silently 清空。

```java
// 加新 identity column 必经 4 步（详细 checklist 见独立 rule）：
// 1) Repository 加 XView projection + findNonNullXProjections JPQL
// 2) SessionService 加 snapshotX() 私有方法
// 3) updateSessionMessages 内调 patchX(messages, oldX)
// 4) AppendMessage record 加新字段 + 7-arg constructor 接受 + 3-arg null 默认
// 5) SessionServiceXxxPreservationIT 4 case（auto-preserve / caller-wins / no-op / tail 查询）
```

判断"是不是 identity 列"：**这条列回答"row 来自哪里 / 关联哪个上层概念" → 是；回答"row 现在内容是啥" → 不是**。

完整列分类表 + Light compact index-alignment limitation 见 [`identity-column-on-rewrite.md`](identity-column-on-rewrite.md)。

### 6. FE-BE Jackson 契约字段名 / 类型漂移

**来源**：2026-05 insights 反复出现的痛点 —— DTO 字段重命名 / 类型变更（如 `ExecuteRequest.commandLine` vs FE `command/args`）本地 BE 单测 + FE tsc 都过得了，但**跨栈调用时反序列化 silent 失败 / 字段 null**。

新加 / 改 `dto/*Request.java` / `*Response.java` / WS event payload 字段时必经 3 步：

1. **字段名 grep 对照** — BE Java 字段名 → FE TS interface 同名字段（kebab vs camel 转换若有 ObjectMapper PropertyNamingStrategy 设置要核对）

   ```bash
   grep -rn "private.*<fieldName>" skillforge-server/src/main/java/com/skillforge/server/dto/
   grep -rn "<fieldName>:" skillforge-dashboard/src/api/
   ```

2. **类型一致性 grep** — Java 类型对应 TS 类型表：

   | Java | TS |
   |---|---|
   | `String` | `string` |
   | `String` (nullable) | `string \| null` |
   | `Long` / `long` / `Integer` / `int` | `number` |
   | `Instant` | `string` (ISO) |
   | `boolean` (primitive) | `boolean` |
   | `Boolean` (nullable) | `boolean \| null` |
   | `Map<String, X>` | `Record<string, X>` |
   | `List<X>` | `X[]` |
   | `enum` | `'A' \| 'B' \| 'C'` 字符串字面量 union |

3. **roundtrip IT** — 用 `ObjectMapper.writeValueAsString(BE 对象)` 跟 FE TS 类型 expected JSON 比对：

   ```java
   @Test
   void <X>Request_jsonRoundtrip_matchesFeContract() {
       <X>Request req = new <X>Request("v1", "v2", ...);
       String json = objectMapper.writeValueAsString(req);
       // 断言含 FE 期望的字段 + 不含多余字段
       assertThat(json).contains("\"<feField1>\":");
       assertThat(json).doesNotContain("\"<oldFieldName>\":");
   }
   ```

**反例 1（inner DTO 字段名）**：P10 斜杠命令 `ExecuteRequest.commandLine` 字段，FE 发的是 `{command, args}` → BE 反序列化 `commandLine` null → silent 失败，r2 才暴露。

### 6b. ⚠️ Outer envelope shape 也得验（reviewer 必查）

**反例 2（outer envelope shape）**：FLYWHEEL-PER-RUN (commit `538b828`) BE Controller 返 `{items, limit, hideTerminal}` envelope，FE wrapper 类型写成裸 `FlywheelRunDto[]`，hook `r.data ?? []` 拿到 envelope object 当 array → `[...runs]` spread 不可 iter 抛 TypeError。r1 双 reviewer 都没 catch：java-reviewer 检了 12 个 DTO 内部字段 ✓，但**没看 outer 是 `List<X>` 直接 ResponseEntity.ok(list) 还是 `Map.of("items", list, ...)` 包裹**；ts-reviewer 跟着 test mock 走，而 test mock 用了跟 FE-Dev **同款的错 shape** (`Promise.resolve({data: [...]})`) → mock 跟实际 BE 不符，测试自洽但跑到 prod 就崩。Hotfix commit `5e25067` 修。

**Reviewer 检查 footgun #6 时必查 4 件事（不只是字段名）**：

1. **outer envelope shape**：BE Controller `return ResponseEntity.ok(...)` 内的对象是：
   - 裸 `List<X>` / `X[]`？ → FE `api.get<X[]>(...)` + `r.data ?? []`
   - `Map<String, Object>` envelope（`items` + 分页 meta 等）？ → FE 必须类型化 envelope interface + `r.data?.items ?? []`
   - paged envelope（`{content / pageable / totalElements / ...}`）？ → 同上
   - 单对象 `X`？ → FE `api.get<X>(...)`
   - **grep `return ResponseEntity.ok(` 看到的 value 是什么 → FE wrapper 对应得上吗？**
2. **inner DTO 字段名 / 类型**（原 footgun #6 内容）
3. **测试 mock shape 必须 mirror 真 BE shape**：reviewer 看 test mock 的 `Promise.resolve({data: ...})` 时**不能信 FE-Dev 假设**，要交叉对 BE 真 Controller 返的 shape。若 mock shape 跟 BE 不符 → BLOCKER（mock 是 echo chamber 假绿）
4. **真活 curl smoke 必跑**：dev 自验 + Phase Final 都要 `curl <endpoint>` 至少一次，把 raw JSON shape 跟 FE TS interface 行对行 verify

**Reviewer checklist 写法（直接 copy 进 review prompt）**:

```
[ ] grep BE Controller return: `ResponseEntity.ok(\(.*\))` 看返的真正 outer shape (List? Map? 单对象?)
[ ] FE wrapper api.get<T>(...) 的 T 是否 match outer shape (裸 array vs envelope object)?
[ ] hook 取数据是 `r.data` (单对象/裸 list) 还是 `r.data.items` (envelope)?
[ ] 测试 mock `Promise.resolve({data: ...})` 的 `data:` 后值 shape 跟 BE 真 return 比对一致?
[ ] 真活 curl smoke 至少跑过一个 happy path 拿 raw JSON 跟 FE TS interface 对齐 verify?
```

**触碰路径**：`skillforge-server/src/main/java/com/skillforge/server/dto/**/*.java` / WebSocket payload 类型 / Controller `@RequestBody` / `@ResponseBody` / **Controller 返 `ResponseEntity.ok(...)` 内的 shape**。

---

## 通用 Java 规范以 vendored `java/*` 为准

依赖注入（构造器注入、禁 `@Autowired` 字段注入）/ 命名（PascalCase/camelCase/常量）/ 错误处理（domain `RuntimeException` 子类 + handler 层统一 catch 不暴露 stack trace）/ Optional（`orElseThrow`，禁裸 `get()`）/ 安全（secrets 不硬编码、SQL 参数化、输入校验）/ 测试（JUnit5 + Mockito + AssertJ + AAA + 80% coverage）等**通用规范见** [`java/coding-style.md`](java/coding-style.md) · [`java/security.md`](java/security.md) · [`java/testing.md`](java/testing.md) · [`java/patterns.md`](java/patterns.md)。

**本文只列 SkillForge 项目独家规范 + footgun**（下方各节 + 文末项目约定），与 vendored 冲突时**本文优先**。

---

## JPA Entity 规范

- Entity 类必须有无参构造器（JPA 要求）
- 字段默认可变（JPA 需要 setter）；业务逻辑中避免直接操作字段，通过 Service 方法修改
- 表名用 `t_` 前缀（已有惯例：`t_session`, `t_agent` 等）
- 时间审计字段用 `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate` / `@LastModifiedDate`
- 枚举状态字段用 `String` 存储（如 `status`, `runtimeStatus`），加 `@Column(length = 32)` 限制长度
- `TEXT` / `CLOB` 类字段加 `@Column(columnDefinition = "TEXT")` 或 `"CLOB"`，避免默认 255 截断

```java
@Entity
@Table(name = "t_example")
@EntityListeners(AuditingEntityListener.class)
public class ExampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 32)
    private String status = "active";

    @Column(columnDefinition = "TEXT")
    private String content;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public ExampleEntity() {}
    // getters + setters...
}
```

---

## @Transactional 使用规则

- `@Transactional` 放在 **Service 层**，不放在 Controller 或 Repository
- 只读操作加 `@Transactional(readOnly = true)` 提升性能
- 涉及多个 Repository 写操作的方法**必须**加 `@Transactional`，保证原子性
- 不要在 `private` 方法上加 `@Transactional`（Spring AOP 不生效）

```java
@Service
public class SessionService {

    @Transactional(readOnly = true)
    public SessionEntity getSession(String id) { ... }

    @Transactional
    public SessionEntity createSession(CreateSessionRequest req) { ... }
}
```

---

## Tool 接口实现规范

> 术语说明：原 `Skill` 接口（deprecated alias）已删除。SkillForge 里 "Tool" 指 Java 内置 function-calling 工具（A 类）；"Skill" 仅保留给 zip 包资产（B 类，如 `SkillDefinition` / `SkillRegistry` / `SkillPackageLoader` / `SkillContext` / `SkillResult` 等核心 API surface 命名）。

新增 Tool 必须实现 `com.skillforge.core.skill.Tool` 接口：

```java
public class MyTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MyTool.class);

    // 依赖通过构造器注入，不用 @Autowired
    private final SomeDependency dep;

    public MyTool(SomeDependency dep) {
        this.dep = dep;
    }

    @Override
    public String getName() { return "MyTool"; }   // 全局唯一，驼峰

    @Override
    public String getDescription() { return "..."; } // 清晰描述用途，LLM 会读这个

    @Override
    public ToolSchema getToolSchema() { ... }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) { ... }
}
```

- `getName()` 返回值全局唯一，用 PascalCase
- `getDescription()` 面向 LLM，要准确描述功能和使用时机
- 在 `SkillForgeConfig` 中通过 `skillRegistry.registerTool(new MyTool(...))` 注册（注意：`SystemSkillLoader` 装载的是 B 类 zip 包资产，不是 Java Tool）

---

## LlmProvider 接口扩展规范

新增 LLM Provider 必须实现 `com.skillforge.core.llm.LlmProvider`，并在 `LlmProviderFactory` 中注册。
`chat()` 方法对超时重试，`chatStream()` 单次尝试不重试（参见 `ClaudeProvider` 实现）。

---

## SkillForge 项目约定（通用规范见文首指向的 vendored `java/*`）

> 这些是 SkillForge 特有约定 / 在通用规范基础上的项目收窄，**不在 vendored 里**。

### 命名（项目特有部分）

- 包：`com.skillforge.<module>.<layer>`（如 `com.skillforge.server.service`）
- Entity 类名后缀 `Entity`（`SessionEntity`）；Repository 接口后缀 `Repository`（`SessionRepository`）
- DTO 的 Request/Response 类加后缀（`ChatRequest` / `ChatResponse`）
- 其余 PascalCase/camelCase/常量等通用命名见 [`java/coding-style.md`](java/coding-style.md)

### 错误处理（项目特有部分）

- 域错误用语义明确的异常类：`SessionNotFoundException` / `AgentNotFoundException` 等
- **WebSocket handler 层**与 Controller 一样统一 catch、返回结构化错误、不暴露 stack trace
- 日志用 SLF4J：`private static final Logger log = LoggerFactory.getLogger(Xxx.class);`
- 通用 unchecked/handler 边界规范见 [`java/coding-style.md`](java/coding-style.md) + [`java/security.md`](java/security.md)

### 安全（项目高危点）

- 用户输入（**Tool 参数、Chat 内容**）在执行系统命令前必须校验，防命令注入（Bash 等 Tool 高危）
- 日志**不打印** API Key、用户 token、**会话内容**等敏感信息
- 通用 secrets / SQL 注入 / 输入校验见 [`java/security.md`](java/security.md)

### 测试（项目特有部分）

- Spring Boot 集成测试用 `@SpringBootTest`，数据库测试用项目默认 **H2**（注意 H2 vs PostgreSQL 行为差异，见 [`systematic-debugging.md`](systematic-debugging.md)）
- 通用 JUnit5 + Mockito + AssertJ + AAA + 命名 + 80% coverage 见 [`java/testing.md`](java/testing.md)
