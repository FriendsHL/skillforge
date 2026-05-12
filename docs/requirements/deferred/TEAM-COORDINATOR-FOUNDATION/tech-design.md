# TEAM-COORDINATOR-FOUNDATION 技术方案

---
id: TEAM-COORDINATOR-FOUNDATION
status: deferred
prd: ./prd.md
risk: Full
created: 2026-05-12
updated: 2026-05-12
---

## TL;DR

三件事一起做：(1) V70 `UPDATE t_agent SET name='Coordinator' WHERE name='Main Assistant'` 不改 id，FE/BE 文案同步替换；(2) `AgentLoopEngine.runInternal` 在 collab 上下文下自动出 `collab_member_started` + `collab_member_progress` 事件，事件流过 `ChatEventBroadcaster` 走现有 user-channel WS；(3) Dashboard 新增 `/collab-runs/:id` 树状看板订阅事件实时刷新。`t_session` 加 4 列持久化最新 progress（`last_progress_at TIMESTAMPTZ` 对应 Java `Instant`）让 server 重启 / 用户刷新页面后看板能 fetch snapshot 重建。

> 状态说明：本方案曾在 2026-05-12 r2 Judge final 后达到 `design-ready`，但同日因用户判断当前架构下 ROI 不足而整体转为 `deferred`。保留本文作为后续复活时的设计记录，不代表当前待实施。

> **r1 spec review 后修订（r2 / 2026-05-12）**：4 个 blocker（LoopContext 缺字段 / 跨线程无事务 / executor 未指定 / FE WS dispatch 漏）+ 13 个 warning 全部消化进本文档；nit 折叠到 [/tmp/nits-followup-TEAM-COORDINATOR-FOUNDATION.md](file:///tmp/nits-followup-TEAM-COORDINATOR-FOUNDATION.md)。详见 §评审记录。

## 关键决策

| ID | 决策 | 理由 | 替代方案 |
| --- | --- | --- | --- |
| **D1** | 进度事件粒度：每 **loop iter 开始** + 每 **tool 开始结束**（两者都发）| 用户痛点既要"看子 agent 在干啥（tool 粒度）"也要"看 loop 推进（iter 粒度）"；tool 段间 iter 切换是关键信号 | A. 仅 iter（漏掉长 tool 内的状态）/ B. 仅 tool（漏掉 loop iter 推进信号）|
| **D2** | 进度事件载荷字段：`{collabRunId, handle, sessionId, iter, currentTool, tokensUsed, timestamp}`。**移除 `runtimeStatus`**（r1 architect W6）：progress 在飞 ≡ running，状态由 spawned/finished 事件隐式表达；snapshot DTO 保留 `runtimeStatus`（从 SessionEntity 取）。startedAt 不在 WS 事件，FE 从 snapshot 缓存（N3）| iter 反映 loop 推进、currentTool 反映"正在干啥"、tokensUsed 反映资源消耗 | 极简载荷只发 sessionId + 状态变更 — 看板要 2 次 fetch 补齐反而更耗 |
| **D3** | Rename 策略：**直接 update 现有 row**（同 agent id），改 name + 自描述文本 | 改 id 会破坏现有 t_session.agent_id 外键引用 + collab leader binding；name 是显示标签不是身份 | A. 新建 Coordinator + 老 row 标 deprecated — 兼容代码到处分支 / B. 不 rename 只改 dashboard 文案 — DB 与 UI 不一致拖延债 |
| **D4** | 看板路由：**独立路由 `/collab-runs/:collabRunId`** | leader session 可能跑多次 collab run，按 run 维度组织看板比按 session 嵌入更清晰；可独立分享 URL | 内嵌 `/sessions/:id` tab — leader session 1:N collab run 时混乱 |
| **D5** | 看板数据加载：**进入页面 fetch snapshot + WS 增量**；snapshot 字段持久化到 t_session 列 | 纯 WS 流方案 server 重启 / 用户刷新后看板回空白；持久化最新 progress 让 snapshot 可重建 | A. 纯 WS 流（重启即空）/ B. 每 1s 全量轮询 fetch（流量大 + 反应慢） |
| **D6** | 进度节流（完整合同）：节流 key = `sessionId + "\|" + iter + "\|" + (currentTool ?? "_null_")`；window = wall-clock 1s **滑动窗口**；同 key 1s 内 emit ≤1 次；token-only 变化（同 key）单独 throttle 1s ≤1 次；DB 持久化每 sessionId 每 5s 最多 1 次。`ProgressThrottler` 为单例 `@Component`，AgentLoopEngine 通过 setter 注入 | LLM tool loop 可能秒级多次更新 tokens，全转发打爆 WS；t_session 高频 UPDATE 浪费 DB IO | A. 不节流（流量风险）/ B. 仅广播状态 change（漏 token 累积曲线）|
| **D7** | Leader 在看板：**作为根节点显示**（与 member 平级渲染但 root 位置 + 特殊 badge "Coordinator"）| 用户直觉看 collab run 就是看"主从结构"；leader 不独立成元数据是因为 leader 也在跑 loop / 用 tool 也有 progress | 元数据形式（仅 collab run 顶部 banner 不显 leader 节点）— 看不出 leader 当前在 review 子 agent 结果 |
| **D8** | Rename 兼容：FE / BE / 文档 grep "Main Assistant" 字串全替换；**历史 chat message 内文不追溯**；archive 文档保留原 wording 防 git diff 污染 | 历史 chat 是用户对话产物，改了等于改用户历史 | 字串迁移工具 SQL 改 t_session_message 内容 — 风险高收益低 |
| **D9** | FE collab 事件 store 用 **TanStack Query cache `setQueryData` + 模块级 EventTarget**，**不引入 zustand / redux**（r2 NI-1）| SkillForge dashboard 当前 grep `package.json` 无 zustand/redux 依赖；frontend.md "项目当前无 Redux/Zustand"；引入新 dep 跟现有 hook 模式（`useChatMessages` / `useEvalTasks` 皆基于 TanStack Query）不一致 | A. 引入 zustand 写 `useCollabRunStore` —— 新 dep 违反规则 / B. React Context + useReducer —— App 级 Provider 嵌套 + re-render 风险，比 EventTarget 重 |

## 架构

### 数据流

```
LLM stream / tool execution
       │
       ▼
AgentLoopEngine.runInternal (collab 上下文 检测 LoopContext.collabRunId != null)
       │
       ├─ 第 1 loop iter 前：broadcaster.collabMemberStarted(...)
       │
       ├─ 每 loop iter 开始：broadcaster.collabMemberProgress(iter, null, tokens, "running")
       │                       └─ 节流（D6）
       │
       ├─ tool 开始：broadcaster.collabMemberProgress(iter, toolName, tokens, "running")
       │
       └─ tool 结束：broadcaster.collabMemberProgress(iter, null, tokens, "running")
                              │
                              ├─ WebSocket 推送 (现有 channel)
                              │
                              └─ ProgressPersister (新增) 节流写 t_session.current_iter/tool/tokens/last_progress_at
                                     ▲
                                     │ fetch
GET /api/collab-runs/{id}/snapshot ──┴──► CollabRunController (新增)
       │
       ▼
Dashboard /collab-runs/:id (CollabRunDashboard.tsx)
       │
       ├─ on mount: fetch snapshot → 渲染初始树
       │
       └─ 订阅 WS → 增量更新节点 currentTool / tokens / status
```

### 模块边界

- **skillforge-core**：`AgentLoopEngine.runInternal` 加 progress 钩子点（仅在 `LoopContext.collabRunId != null` 触发），`LoopContext` 加 `collabRunId` + `handle` 字段，`ChatEventBroadcaster` 接口加 2 个方法 + non-blocking javadoc。**不动** LlmProvider / Skill / Tool / Compact 任何子系统
- **skillforge-server**：新增 `CollabRunController.snapshot` + `CollabProgressPersister`（节流 + 调度）+ `CollabProgressWriteService`（`@Transactional` DB UPDATE 委托）+ `progressWriteExecutor` Bean + V70 migration；`SessionEntity` 加 4 列；`SessionRepository` 加按 collabRunId 批量查 + projection；`ChatService.chatAsync` 加一行 `preCtx.setCollabRunId(...)` + `preCtx.setHandle(...)`
- **skillforge-dashboard**：新增 `pages/CollabRunDashboard.tsx` + `api/collabRuns.ts` + `useCollabRunWebSocket` hook（订阅现有 user-channel 已 dispatch 的 collab 事件，**不开新 socket**）；扩展现有 `useChatWsEventHandler` dispatch 加 progress / started 两类事件；`App.tsx` 注册新路由；现有 SessionDetail 加"查看协作详情"按钮（条件渲染）

### 关键 wiring（r1 BLOCKER-1 修复）

```
1. LoopContext 加字段:
   private String collabRunId;
   private String handle;
   + getter/setter

2. ChatService.chatAsync (现有 preCtx 构建块附近):
   preCtx.setCollabRunId(session.getCollabRunId());   // 已读 session.getCollabRunId 但未透传
   preCtx.setHandle(session.getCollabRunId() != null
       ? agentRoster.resolveHandle(session.getCollabRunId(), session.getId())
       : null);

3. AgentLoopEngine.runInternal 读 ctx.getCollabRunId() / ctx.getHandle()
   （isCollab = collabRunId != null && handle != null 双条件）

4. AgentRoster 加方法 resolveHandle(collabRunId, sessionId)
   反查 handle → sessionId 映射的反向：sessionId → handle
   （现有 listMembers(collabRunId) 返 Map<handle, sessionId>，可以基于这个加反向查询）
```

## 后端改动

### B1 — V70 migration `V70__rename_main_assistant_to_coordinator_and_session_progress.sql`

**实施前置 verify 动作（r1 W4）**：先在本地 dev DB 跑 `SELECT behavior_rules FROM t_agent WHERE name='Main Assistant'`，确认 V27 实际 behavior_rules JSON 含字面量 "你是主 Agent"；若不含（例如 "你是主Agent" 无空格 / 其他变体），按实际命中字符串调整下面 REPLACE 第二参数，或改 `jsonb_set` 直接重写 customRules 数组。

**r2 NEW-3 修复**：原 r2 提交的 `DO $$ ... RAISE EXCEPTION` 自验块是 PostgreSQL 专属语法，H2 无法解析。SkillForge `pom.xml` 含 H2 runtime dep（虽然主要 IT 走 testcontainers Postgres + zonky embedded-postgres，但 H2 仍可能在某些 slice 路径被 Spring Boot autoconfigure 触发）。**移除 SQL 内自验**，自验断言落到 `V70MigrationIT`（已在测试计划，走 testcontainers Postgres）。

```sql
-- F1.1 + F1.2 rename（SQL 保持 H2 兼容 — 仅 UPDATE + ALTER TABLE，无 DO $$）
UPDATE t_agent
SET name = 'Coordinator',
    behavior_rules = REPLACE(behavior_rules, '你是主 Agent', '你是 Coordinator')
WHERE name = 'Main Assistant';

-- F4.2 t_session 加 progress 持久化列（全 nullable，无 backfill）
-- r1 W2: last_progress_at 必须 TIMESTAMP WITH TIME ZONE 对应 Java Instant（java.md footgun #2）
ALTER TABLE t_session ADD COLUMN current_iter INT;
ALTER TABLE t_session ADD COLUMN current_tool VARCHAR(64);
ALTER TABLE t_session ADD COLUMN tokens_used BIGINT;
ALTER TABLE t_session ADD COLUMN last_progress_at TIMESTAMP WITH TIME ZONE;

-- snapshot 按 collab_run_id 查 member；保险起见加索引
CREATE INDEX IF NOT EXISTS idx_session_collab_run ON t_session (collab_run_id) WHERE collab_run_id IS NOT NULL;
```

**SessionEntity Java 字段类型**（按 java.md footgun #2 新字段一律 `Instant`）：

| 列 | Java 类型 | 备注 |
| --- | --- | --- |
| `current_iter` | `Integer` (nullable) | — |
| `current_tool` | `String` (nullable) | `@Column(length = 64)` |
| `tokens_used` | `Long` (nullable) | — |
| `last_progress_at` | `Instant` (nullable) | **不要用 `LocalDateTime`** |

### B2 — `ChatEventBroadcaster` 扩展（含 non-blocking 合同 javadoc）

```java
// skillforge-core/src/main/java/com/skillforge/core/engine/ChatEventBroadcaster.java
public interface ChatEventBroadcaster {
    // existing
    void collabMemberSpawned(String collabRunId, String handle, String sessionId, String agentName);
    void collabMemberFinished(String collabRunId, String handle, String status, String summary);
    void collabRunStatus(String collabRunId, String status);

    /**
     * Emitted once at first loop iter of a collab member session.
     *
     * <p><b>Contract</b>: implementations MUST be non-blocking / fire-and-forget.
     * MUST NOT throw out of engine. Use async dispatch internally if needed.
     * Engine 线程不能被 broadcaster 拖慢。
     */
    default void collabMemberStarted(String collabRunId, String handle, String sessionId) {}

    /**
     * Emitted at each loop iter start + each tool start/end.
     * Payload {@code runtimeStatus} 字段已移除（r1 W6）— progress 在飞即 running。
     *
     * <p><b>Contract</b>: non-blocking / fire-and-forget. MUST NOT throw out of engine.
     * 节流 / 持久化由 ProgressThrottler + CollabProgressPersister 处理，broadcaster 自身仅做 WS 推送。
     */
    default void collabMemberProgress(String collabRunId, String handle, String sessionId,
                                     int iter, String currentTool, long tokensUsed) {}
}
```

Server 端 `WebSocketChatEventBroadcaster` 覆盖默认实现，序列化成 JSON 推送 user-channel（**复用现有 channel**，不开新 socket）。

### B3 — `AgentLoopEngine.runInternal` 加钩子点

> 触碰核心文件。需 reviewer 显式审 [`persistence-shape-invariant.md`](../../../../.claude/rules/persistence-shape-invariant.md) 和 [`identity-column-on-rewrite.md`](../../../../.claude/rules/identity-column-on-rewrite.md) —— 本次改动**不动** Message 拼装、不改 t_session_message 任何列，仅在 loop iter 之间发事件，理论上不触发这两条不变量，但 reviewer 必须核实。

```java
// 简化伪码（r1 修复：runtimeStatus 已移除 / collabRunId+handle 来自 LoopContext）
private LoopResult runInternal(..., LoopContext ctx) {
    boolean isCollab = ctx != null
        && ctx.getCollabRunId() != null
        && ctx.getHandle() != null;
    String collabRunId = isCollab ? ctx.getCollabRunId() : null;
    String handle = isCollab ? ctx.getHandle() : null;
    String sessionId = ctx != null ? ctx.getSessionId() : null;

    if (isCollab && broadcaster != null) {
        broadcaster.collabMemberStarted(collabRunId, handle, sessionId);
    }

    for (int iter = 0; iter < maxLoops; iter++) {
        if (isCollab) {
            emitProgress(collabRunId, handle, sessionId, iter, null, totalTokens);
        }
        // ...existing loop body...
        for (ToolUseBlock toolUse : toolUses) {
            if (isCollab) {
                emitProgress(collabRunId, handle, sessionId, iter, toolUse.getName(), totalTokens);
            }
            ToolResult result = dispatcher.execute(toolUse, ...);
            if (isCollab) {
                emitProgress(collabRunId, handle, sessionId, iter, null, totalTokens);
            }
        }
    }
}

private void emitProgress(String collabRunId, String handle, String sessionId,
                          int iter, String currentTool, long tokensUsed) {
    // 节流（D6）：通过 ProgressThrottler 组件
    if (throttler.shouldEmit(sessionId, iter, currentTool)) {
        broadcaster.collabMemberProgress(collabRunId, handle, sessionId, iter, currentTool, tokensUsed);
        progressPersister.recordAsync(sessionId, iter, currentTool, tokensUsed);
    }
}
```

### B4 — `LoopContext` 加 `collabRunId` + `handle` 字段（**r1 BLOCKER-1 修复**）

主会话核实：当前 `LoopContext` 既无 `collabRunId` 也无 `handle` 字段；`ChatService.chatAsync` 读了 `session.getCollabRunId()` 但**没透传到** `preCtx`。本节修补完整：

```java
// skillforge-core/.../engine/LoopContext.java 加两个字段
public class LoopContext {
    // ...existing fields...
    private String collabRunId;   // collab member 才有；非 collab session 为 null
    private String handle;        // collab member 的 handle（如 "research-jwt"）；非 collab 为 null

    public String getCollabRunId() { return collabRunId; }
    public void setCollabRunId(String collabRunId) { this.collabRunId = collabRunId; }
    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
}
```

### B4.5 — `ChatService.chatAsync` 内 preCtx wiring（**r1 BLOCKER-1 修复关键点**）

在现有 preCtx 构建块（同 `preCtx.setExcludedSkillNames(...)` 位置附近）追加：

```java
// ChatService.chatAsync 内
String collabRunId = session.getCollabRunId();
preCtx.setCollabRunId(collabRunId);
if (collabRunId != null) {
    preCtx.setHandle(agentRoster.resolveHandle(collabRunId, session.getId()));
}
```

### B4.6 — `AgentRoster` 加反向查询

`AgentRoster.listMembers(collabRunId)` 现返 `Map<handle, sessionId>`。新增 `resolveHandle(collabRunId, sessionId)`：

```java
public String resolveHandle(String collabRunId, String sessionId) {
    Map<String, String> members = listMembers(collabRunId);
    return members.entrySet().stream()
        .filter(e -> sessionId.equals(e.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
}
```

非 collab 上下文 / handle null：`isCollab` 复合条件确保 broadcaster 不被触发。

### B5 — `CollabRunController.snapshot`（新增 endpoint）

**与现有 `/members` endpoint 职责区分（r1 W5）**：

| Endpoint | 用途 | 返回字段 |
| --- | --- | --- |
| `GET /api/collab-runs/{id}/members`（**existing**）| 通用 list / 跟现有 dashboard collab page 兼容 | handle / sessionId / runtimeStatus / agentId / depth / title |
| `GET /api/collab-runs/{id}/snapshot`（**new**） | 看板专属聚合视图，含 4 新 progress 列 + run 元信息 + isLeader | run 元数据 + members[{handle, sessionId, agentName, runtimeStatus, currentIter, currentTool, tokensUsed, startedAt, lastProgressAt, isLeader, agentId}] |

```java
// skillforge-server/src/main/java/com/skillforge/server/controller/CollabRunController.java
@GetMapping("/{id}/snapshot")
public CollabRunSnapshotResponse snapshot(@PathVariable("id") String collabRunId,
                                          @CurrentUserId Long callerUserId) {
    CollabRunEntity run = collabRunService.getRun(collabRunId);
    if (run == null) throw new NotFoundException(...);

    // 鉴权（r1 W3）：caller userId == leader session 的 userId
    SessionEntity leader = sessionService.getSession(run.getLeaderSessionId());
    if (!Objects.equals(leader.getUserId(), callerUserId)) {
        throw new ForbiddenException("collab run not accessible to caller");
    }

    List<SessionEntity> members = sessionRepository.findByCollabRunId(collabRunId);
    return CollabRunSnapshotResponse.from(run, members, agentRoster.listMembers(collabRunId));
}
```

**鉴权决策（r1 W3）**：caller `userId` 等于 collab run 的 **leader session 的 userId** 即可访问。**不需**对每个 member 独立校验（member 必然属同一 user，由 spawnMember 路径保证 - `chatAsync` spawn 时透传父 session userId）。新增 IT 用例：跨用户调用 → 403。

### B6 — `CollabProgressPersister` + `CollabProgressWriteService` 双层（**r1 BLOCKER-2/3 修复**）

**问题**：r1 java reviewer 指出 `executor.submit(() -> sessionRepository.updateProgress(...))` 跨线程 Spring 事务 ThreadLocal 不传播，`@Modifying @Query` 没有活跃 transaction → `TransactionRequiredException`。同时 spec 写"existing executor"未指定哪个，可能误用 `chatLoopExecutor`（loop 专用）导致 loop 与 IO 互相阻塞。

**修复**：

```java
// 1. 独立 IO executor Bean，与 chatLoopExecutor 隔离
// skillforge-server/.../config/SkillForgeConfig.java 新增
@Bean(name = "progressWriteExecutor", destroyMethod = "shutdown")
public ThreadPoolExecutor progressWriteExecutor() {
    ThreadPoolExecutor exec = new ThreadPoolExecutor(
        2, 4,                              // core 2 / max 4
        60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(256),    // queue 满即 reject（log + drop，不阻塞 engine）
        new NamedThreadFactory("progress-write-"),
        new ThreadPoolExecutor.DiscardOldestPolicy()  // 进度过期数据扔旧的
    );
    return exec;
}

// 2. CollabProgressWriteService — 真正做 @Transactional DB UPDATE 的 Service
@Service
public class CollabProgressWriteService {
    private final SessionRepository sessionRepository;

    public CollabProgressWriteService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public void writeProgress(String sessionId, int iter, String currentTool,
                              long tokensUsed, Instant at) {
        sessionRepository.updateProgress(sessionId, iter, currentTool, tokensUsed, at);
    }
}

// 3. CollabProgressPersister — 只做节流 + 调度，UPDATE 委托给 Service
@Component
public class CollabProgressPersister {
    private static final Logger log = LoggerFactory.getLogger(CollabProgressPersister.class);
    private static final Duration WRITE_INTERVAL = Duration.ofSeconds(5);

    // r1 W1 + r2 NEW-1 修复：加 eviction 防 OOM
    // expireAfterWrite（不是 expireAfterAccess）— 每次 recordAsync put 后从该刻起 5min idle 才 evict；
    // expireAfterAccess 会被 getIfPresent 不停 reset TTL，活 session 永不过期，违背 idle 语义
    // collab_member_finished event listener 主动 evict（CollabRunService.onMemberCompleted）
    private final Cache<String, Instant> lastWriteBySession = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10_000)   // 硬上限
            .build();

    private final CollabProgressWriteService writeService;
    private final ThreadPoolExecutor executor;

    public CollabProgressPersister(CollabProgressWriteService writeService,
                                    @Qualifier("progressWriteExecutor") ThreadPoolExecutor executor) {
        this.writeService = writeService;
        this.executor = executor;
    }

    public void recordAsync(String sessionId, int iter, String currentTool, long tokensUsed) {
        Instant now = Instant.now();
        // r2 NW-1 acceptable race：getIfPresent + put 非原子，并发同 sessionId 可能放过两次。
        // 但 updateProgress 是 idempotent SET-style UPDATE — 最多重复一次写，无数据正确性问题。
        // 不用 Caffeine.asMap().compute() 显式锁是因为竞争收益不抵复杂度成本。
        Instant last = lastWriteBySession.getIfPresent(sessionId);
        if (last != null && Duration.between(last, now).compareTo(WRITE_INTERVAL) < 0) return;
        lastWriteBySession.put(sessionId, now);
        // DiscardOldestPolicy 让 execute() 不抛 RejectedExecutionException（policy 在 executor 配置层
        // 静默 evict 最旧 queued task）。不写 catch 块；如改为 AbortPolicy 再加 catch。
        executor.execute(() -> {
            try {
                writeService.writeProgress(sessionId, iter, currentTool, tokensUsed, now);
            } catch (Exception e) {
                log.warn("collab progress write failed sessionId={}, err={}", sessionId, e.toString());
            }
        });
    }

    /** Collab member finish 时主动 evict，避免 long-running server 内存累积。 */
    public void evictSession(String sessionId) {
        lastWriteBySession.invalidate(sessionId);
    }
}

// 4. CollabRunService.onMemberCompleted 末尾显式调 evictSession（r2 NW-3）
//    （现有 onMemberCompleted 方法依赖注入 CollabProgressPersister）
//
//    public void onMemberCompleted(String collabRunId, String sessionId) {
//        // ...existing finalization logic (broadcast, mark run as completed if all done)...
//        progressPersister.evictSession(sessionId);   // ← 新增
//    }
//
//    测试断言（新增 IT case）：mock CollabProgressPersister，invoke onMemberCompleted，
//    verify(progressPersister, times(1)).evictSession(sessionId);

// 5. SessionRepository 加 JPQL（注意 @Modifying 必须配 @Transactional，由 Service 层提供）
@Modifying
@Query("UPDATE SessionEntity s SET s.currentIter = :iter, s.currentTool = :tool, " +
       "s.tokensUsed = :tokens, s.lastProgressAt = :at WHERE s.id = :sessionId")
int updateProgress(@Param("sessionId") String sessionId,
                    @Param("iter") int iter,
                    @Param("tool") String currentTool,
                    @Param("tokens") long tokensUsed,
                    @Param("at") Instant at);
```

**关键点**：
- `CollabProgressWriteService` 是 `@Transactional` 真持有者；事务上下文由 Spring 在该 service 方法入口建立（在 executor 线程内）—— 跨线程 `@Transactional` 这样工作是 OK 的，因为 Spring 事务建立在**方法调用栈**而非进入 ThreadLocal 之前的状态
- `lastWriteBySession` 用 Caffeine 而非 `ConcurrentHashMap`，TTL=5min + 主动 evict 双重保险
- 独立 `progressWriteExecutor` 跟 `chatLoopExecutor` 隔离
- queue 满 RejectedExecutionException 不抛出到 engine（log 后 drop）
- broadcaster + persister 合在一起在 `AgentLoopEngine.emitProgress` 同步调用，但都是 fire-and-forget

## 前端改动

### F0 — `App.tsx` 路由注册（**r1 W12 修复**）

在路由配置文件加：

```tsx
<Route path="/collab-runs/:collabRunId" element={<CollabRunDashboard />} />
```

### F1 — 新页面 `CollabRunDashboard.tsx`

```tsx
// pages/CollabRunDashboard.tsx 骨架（r2 NI-3 修复 — 与 F2 单参 hook 签名对齐）
export default function CollabRunDashboard() {
  const { collabRunId } = useParams<{ collabRunId: string }>();
  // F2 hook 内部已让 WS 增量直接 patch TanStack Query cache；这里 useQuery 就是单一真相源
  const { data: snapshot } = useCollabRunSnapshot(collabRunId!);
  useCollabRunWebSocket(collabRunId!);  // 副作用：订阅 WS 增量 → patch query data
  return <CollabRunTree run={snapshot} />;   // snapshot 类型 CollabRunSnapshot | undefined
}
```

**说明**：F1 骨架与 F2 hook 签名严格对齐。`useCollabRunSnapshot` 是对 `fetchCollabRunSnapshot` 的 TanStack Query 封装（`queryKey: ['collab-run', id]`）；`useCollabRunWebSocket` 是 effect-only hook（无返回值），WS 增量由其内部 `queryClient.setQueryData(['collab-run', id], patch)` 写回 cache，`useCollabRunSnapshot` 自动重渲。

### F2 — `useCollabRunWebSocket` hook + 扩展现有 `useChatWsEventHandler` dispatch（**r1 BLOCKER-4 + r2 NI-1 修复**）

**问题**：r1 typescript reviewer 指出 `useChatWsEventHandler` 现有 dispatch（hook ts 第 314-319 行）只覆盖 `collab_member_spawned / collab_member_finished / collab_run_status` 3 个，**新加的 progress / started 事件不会被分发**。r2 typescript reviewer 又指出 zustand 当前**不在** SkillForge FE 项目（confirmed by grep package.json + frontend.md "项目当前无 Redux/Zustand"），引入 zustand 是新 dep 违反规则。

**修复方案（r2）**：用 TanStack Query `queryClient.setQueryData` 直接 patch `['collab-run', id]` cache，**不引入 zustand**：

```ts
// 1. 扩展 useChatWsEventHandler dispatch list (现有 hook 文件第 ~314 行)
//    把新事件转发到模块级 emitter（不用 zustand，用最轻量 EventTarget 或 mitt 风格）
if (
    evt.type === 'collab_member_spawned' ||
    evt.type === 'collab_member_finished' ||
    evt.type === 'collab_member_started' ||   // ← 新增
    evt.type === 'collab_member_progress' ||  // ← 新增
    evt.type === 'collab_run_status' ||
    evt.type === 'collab_message_routed'
) {
    collabEventBus.emit(evt);
    // ...既有 chat-specific 分支保留...
}

// 2. collabEventBus（api/collabRuns.ts 内或单独文件）— 模块级 EventTarget
//    不引入第三方 store lib，仅 wrap browser-native EventTarget
const target = new EventTarget();
export const collabEventBus = {
  emit: (evt: CollabWsEvent) => target.dispatchEvent(new CustomEvent('collab', { detail: evt })),
  on: (handler: (evt: CollabWsEvent) => void) => {
    const wrapped = (e: Event) => handler((e as CustomEvent).detail);
    target.addEventListener('collab', wrapped);
    return () => target.removeEventListener('collab', wrapped);
  },
};

// 3. useCollabRunWebSocket — effect-only hook，订阅事件 → 用 queryClient.setQueryData patch cache
export function useCollabRunWebSocket(collabRunId: string): void {
  const queryClient = useQueryClient();
  useEffect(() => {
    const unsub = collabEventBus.on(evt => {
      // discriminated union 内 switch，按事件 type 增量 merge
      if (!('collabRunId' in evt) || evt.collabRunId !== collabRunId) return;
      queryClient.setQueryData<CollabRunSnapshot>(['collab-run', collabRunId], prev => {
        if (!prev) return prev;
        return applyCollabEvent(prev, evt);   // pure reducer，返回新 snapshot
      });
    });
    return unsub;   // cleanup 解订阅（frontend.md WS cleanup footgun 防御）
  }, [collabRunId, queryClient]);
}

// 4. useCollabRunSnapshot — 普通 TanStack Query 封装
export function useCollabRunSnapshot(collabRunId: string) {
  return useQuery({
    queryKey: ['collab-run', collabRunId],
    queryFn: () => fetchCollabRunSnapshot(collabRunId),
    refetchOnWindowFocus: false,
  });
}

// 5. applyCollabEvent — 纯 reducer，可独立单测
export function applyCollabEvent(snap: CollabRunSnapshot, evt: CollabWsEvent): CollabRunSnapshot {
  switch (evt.type) {
    case 'collab_member_spawned': /* push new member */ ...
    case 'collab_member_started':
    case 'collab_member_progress': /* update member by sessionId */ ...
    case 'collab_member_finished': /* set runtimeStatus + outputSummary */ ...
    case 'collab_run_status': /* set run.status */ ...
    default: const _exhaustive: never = evt; return snap;
  }
}
```

**关键决策**：
- **复用现有 user-channel WS connection**，不开新 socket（W9）
- **不引入 zustand**（r2 NI-1）— 用 TanStack Query cache 作单一真相源（与 SkillForge 现有 `useChatMessages` / `useEvalTasks` 等 hook 模式一致）
- `collabEventBus` 是 module-level browser-native `EventTarget` 轻量包装，**无新 dep**
- `useCollabRunWebSocket` cleanup `unsub()` 解订阅（frontend.md WS cleanup footgun 防御）
- `applyCollabEvent` 是纯函数 reducer，独立单测覆盖 5 个事件类型 exhaustive switch
- WS connection 生命周期由现有 `useChatWsEventHandler`（顶层 hook，App 级 mount）持有
- TS 类型见 F4 `CollabWsEvent` discriminated union

### F3 — `CollabRunTree` 组件 + 节点视觉

- Coordinator 节点居顶，子 agent 横向铺开
- 节点 status badge 色：running 蓝 / completed 绿 / failed 红 / stuck 橙
- stuck 判定：`Date.now() - lastProgressAt > 30_000`（前端定时器每 5s 重算）
- 节点子元素：handle / agentName / current iter / current tool / tokens
- 点击节点 → `navigate('/sessions/' + sessionId)`

### F4 — SessionDetail 加"查看协作详情"按钮 + TS 类型补齐

**r1 W13 修复**：实施前置 grep `dto/SessionResponse.java` 确认 `collabRunId` 已暴露到 FE；当前 SessionEntity 有 `collabRunId` 字段但 DTO 可能未透传。**若未透传**：FE 改动加一行后端 DTO 字段补齐（无 schema 改动），加在本节内：

```java
// dto/SessionResponse.java（如缺）
public record SessionResponse(..., String collabRunId, ...) {
    public static SessionResponse from(SessionEntity s) {
        return new SessionResponse(..., s.getCollabRunId(), ...);
    }
}
```

FE 按钮：仅当 `session.collabRunId != null` 显示，链接到 `/collab-runs/:collabRunId`。

**TS 类型定义（r1 W10 修复）**：在 `api/collabRuns.ts` 显式 export：

```ts
export interface CollabMemberSnapshot {
  handle: string;
  sessionId: string;
  agentId: number;
  agentName: string;
  runtimeStatus: 'running' | 'idle' | 'error' | 'completed';
  currentIter: number | null;
  currentTool: string | null;
  tokensUsed: number | null;
  startedAt: string;          // ISO Instant
  lastProgressAt: string | null;
  isLeader: boolean;
}

export interface CollabRunSnapshot {
  collabRunId: string;
  leaderSessionId: string;
  status: 'RUNNING' | 'COMPLETED' | 'CANCELLED';
  createdAt: string;
  completedAt: string | null;
  members: CollabMemberSnapshot[];
}

// WS 事件 discriminated union
export type CollabWsEvent =
  | { type: 'collab_member_spawned'; collabRunId: string; handle: string; sessionId: string; agentName: string }
  | { type: 'collab_member_started'; collabRunId: string; handle: string; sessionId: string }
  | { type: 'collab_member_progress'; collabRunId: string; handle: string; sessionId: string;
      iter: number; currentTool: string | null; tokensUsed: number }
  | { type: 'collab_member_finished'; collabRunId: string; handle: string; status: string; summary: string }
  | { type: 'collab_run_status'; collabRunId: string; status: 'RUNNING' | 'COMPLETED' | 'CANCELLED' };

export async function fetchCollabRunSnapshot(collabRunId: string): Promise<CollabRunSnapshot> {
  // ...fetch wrapper...
}
```

### F5 — 文案扫荡 "Main Assistant" → "Coordinator"

`grep -rln "Main Assistant" skillforge-dashboard/src` 全替换；i18n 资源（如有，grep `locales/*.json`）同步。

## 数据模型 / Migration

### 新增 t_session 列

| 列名 | SQL 类型 | Java 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `current_iter` | INT | `Integer` (nullable) | NULL | 当前 loop iter（0-based）|
| `current_tool` | VARCHAR(64) | `String` (nullable) | NULL | 当前执行 tool 名；null 表示 between tools |
| `tokens_used` | BIGINT | `Long` (nullable) | NULL | 累计 tokens（input + output）|
| `last_progress_at` | **TIMESTAMP WITH TIME ZONE** | **`Instant`** (nullable) | NULL | 最近 progress 事件 wall-clock；**禁用 `LocalDateTime`**（java.md footgun #2）|

**rewrite preserve 不需要扩展**：这 4 列是 **business / derived** 不是 identity 列，按 [`identity-column-on-rewrite.md`](../../../../.claude/rules/identity-column-on-rewrite.md) 列分类表，t_session 行的 rewrite 不涉及（rewriteMessages 只走 t_session_message）。本需求**不动** t_session_message。

### 索引

`idx_session_collab_run` 如尚未存在则建（snapshot fetch 按 collab_run_id 查）。

## 错误处理 / 安全

- **进度事件高频不阻塞 LLM**：广播在 engine 线程 same call stack 内，但实现为 fire-and-forget（broadcaster 内部 async 或非阻塞），不能 throw 出 engine
- **节流必须在事件发射前**：避免 channel 拥塞；节流状态用 ConcurrentMap 不需事务
- **DB 写节流必须 async**：5s 间隔写 t_session 不能卡 engine
- **snapshot endpoint 鉴权**：`@PreAuthorize` 或现有 session 鉴权机制确认调用方有权访问 collabRunId 关联的 leader session
- **stuck 检测在 FE 做**：避免后端定时器扫表；FE 用 `lastProgressAt` 比对本地 `Date.now()` —— 时区不影响（都是 UTC instant 比较）
- **rename SQL 用 REPLACE() 字符串匹配**：仅替换字面 "你是主 Agent"，避免误替换其他文本
- **没有新外部输入**：snapshot endpoint 只 read，无 input 校验风险

## 实施计划

### Phase 1 — Plan 对抗（本期任务）

- [x] spec 落地（本文档 + index / mrd / prd）
- [ ] **当前正在做**：起 architect-reviewer + general-purpose reviewer 对抗 review spec（最多 2 轮）→ 评审结论写本节末
- [ ] reviewer 通过 → 进 Phase 2 Dev

### Phase 2 — Dev（评审通过后做）

并行 2 个 Dev（BE + FE）：

**BE Dev** 任务：
- [ ] V70 migration
- [ ] `ChatEventBroadcaster` 接口扩展 + WebSocket impl 覆盖
- [ ] `LoopContext` 加 handle 字段 + 调用方设值
- [ ] `AgentLoopEngine.runInternal` 加钩子点 + ProgressThrottler
- [ ] `CollabProgressPersister` 组件 + Repository 加 updateProgress
- [ ] `CollabRunController.snapshot` endpoint + DTO
- [ ] 单测 + IT（验收点列表）
- [ ] grep 替换 "Main Assistant"

**FE Dev** 任务：
- [ ] `api/collabRuns.ts` 加 `fetchCollabRunSnapshot`
- [ ] `pages/CollabRunDashboard.tsx` + `CollabRunTree` 组件
- [ ] `useCollabRunWebSocket` hook + 事件 reducer
- [ ] SessionDetail 加跳转按钮（条件渲染）
- [ ] grep 替换 "Main Assistant"
- [ ] 单测 + tsc 通过

### Phase 3 — Review 对抗

- java-reviewer + typescript-reviewer + code-reviewer 并行（diff-in-prompt）
- Judge Opus 仲裁
- Judge 通过 → Phase Final

### Phase Final

- 主会话亲跑 mvn test + npm build + browser e2e（Coordinator 派 2 真子 agent，看板能看到 progress 流）
- delivery-index + 需求包归档

## 测试计划

### 单元 / 集成测试

- [ ] `AgentLoopEngineTest.collabContext_emitsStartedAndProgressEvents`
- [ ] `AgentLoopEngineTest.nonCollabContext_emitsNoCollabEvents`（回归）
- [ ] `AgentLoopEngineTest.handleMissingButCollabRunIdSet_doesNotEmit`（防御性 — `isCollab` 双条件）
- [ ] `ProgressThrottlerTest`：同 (sessionId, iter, tool) 1s 内 ≥10 调用 → emit ≤1；token-only 变化同 key 1s ≤1；不同 (iter,tool) 不互相节流
- [ ] `CollabProgressPersisterTest`：5s 节流验证 + Caffeine TTL 验证（超过 5min idle 自动 evict）+ `evictSession` 主动清理
- [ ] `CollabProgressWriteServiceTest`：`@Transactional` 在 executor 线程内正确建立（用 testcontainers 真 DB 跑，断言 UPDATE 生效）
- [ ] `CollabRunControllerSnapshotIT`：起 collab + spawn 2 子 + curl snapshot
- [ ] `CollabRunControllerSnapshotAuthIT`：跨用户调用 snapshot → 403
- [ ] `SessionRepositoryUpdateProgressTest`：JPQL UPDATE 不影响其他列 + 同时验证 `last_progress_at` 写入是 `Instant` 不是 `LocalDateTime`
- [ ] `WebSocketChatEventBroadcasterTest`：`collabMemberProgress` 序列化 JSON 包含 7 字段（**无** `runtimeStatus`）
- [ ] `V70MigrationIT`：rename 后 SELECT `behavior_rules` 不再含 "主 Agent" 字符串
- [ ] `ChatServicePreCtxWiringTest`：collab session 进入 `chatAsync` 后 preCtx 含 `collabRunId` 和 `handle`；非 collab session 两字段为 null
- [ ] `AgentRosterResolveHandleTest`：sessionId → handle 反向查询正确 / 不存在返 null

### FE 测试

- [ ] `CollabRunDashboard.test.tsx` mock snapshot fetch + WS push → 节点正确渲染
- [ ] `useCollabRunStore.test.ts` applyEvent reducer：spawned + started + progress + finished 序列后 state 正确
- [ ] `useChatWsEventHandler.test.tsx` 扩展用例：progress / started 事件正确 dispatch 到 collabRunStore
- [ ] stuck detection：mock `lastProgressAt` 35s 前 → 节点 className 含 `.stuck`
- [ ] **timer cleanup（r1 W11）**：mount → unmount，断言 stuck detection `setInterval` 被 `clearInterval`
- [ ] TS 类型 compile-time 检查：`CollabRunSnapshot` / `CollabWsEvent` discriminated union 在 reducer 中无 `any`

### 浏览器 e2e

- [ ] `agent-browser` 起 Coordinator session → 用 chat 输入触发 TeamCreate × 2 → 跳 `/collab-runs/:id` → 断言 3 个节点
- [ ] 等子 agent 完成 → 节点变绿
- [ ] 刷新页面 → snapshot 重建后看板仍正确（D5 验证）

### 回归

- [ ] 跑一个普通 chat session（无 collab）→ 检查 WS 流无任何 collab_member_* 事件

## 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| **R1** progress 事件高频打爆 WS | dashboard 卡 / channel 拥塞 | D6 节流（1s 内同状态 1 次）+ DB 写 5s 节流 + broadcaster async |
| **R2** 触碰 `AgentLoopEngine.runInternal` 触发 persistence-shape 不变量 | 持久化 vs engine Message 字节漂移 → silent dup-append | 本次改动**仅在 loop iter 之间发事件**，不动 `messages.add` / Message 拼装；reviewer 显式审；新增 IT 跑 collab + 普通 chat 两条路径回归 Q2 / Q3 测试集 |
| **R3** rename 后 FE 硬编码漏改 | UI 局部还显示 "Main Assistant" | 全 repo grep + 验收点 F1.3 / F5；i18n 资源（如有）逐项查 |
| **R4** snapshot endpoint 鉴权遗漏 | 跨用户看别人 collab run | 复用现有 session 鉴权 + IT 覆盖跨用户 403 |
| **R5** t_session 4 个新列在高频 progress 下 IO 压力 | DB 慢 | `CollabProgressPersister` 5s 节流 + async + 只更新 4 列（轻量 UPDATE）+ collab_run_id 已有 idx；预期 collab run 同时跑 ≤25 个子，QPS 远低于现有 session_message append |
| **R6** Spec 漏掉某种 LLM provider 的边界 | 某 provider 下 tool 调用粒度跟主流不一致 | LoopContext.collabRunId 检测 + 事件发射逻辑在 `AgentLoopEngine` 统一处，所有 provider 共用；本需求**不**针对 provider 差异化处理 |
| **R7** stuck 30s 阈值是否合理 | 误报 / 漏报 | MVP 取 30s（人类等待感知阈值）；后续可加 yaml 配置（本期不做）|
| **R8** `progressWriteExecutor` queue 满策略副作用 | 进度持久化丢失（DiscardOldestPolicy 扔旧任务），用户刷新页面后看到滞后数据 | queue 256 + per-session 5s 节流 → 256 sessions × 12 events/min 容量；超出概率极低；WS 实时事件不依赖 DB 持久化，仅 fetch snapshot 时受影响 |
| **R9** `LoopContext` 加 `collabRunId`/`handle` 字段是 skillforge-core API 变更 | 跨模块兼容 | 字段加 setter/getter 默认 null；非 collab 上下文行为不变；现有 test mock 构造 LoopContext 后断言 ctx.getCollabRunId()==null 仍 pass。**向后兼容变更，不破坏现有 caller** |
| **R10** Caffeine 依赖引入 | 新增 maven 依赖 | `com.github.ben-manes.caffeine:caffeine` 是 Spring Boot starter 已传递依赖（cache abstraction 默认 impl），无需新增 dependency；如未引入则加到 skillforge-server pom |

## 评审记录

### r1（2026-05-12，Plan 对抗 1 / 2）

| Reviewer | 模型 | 结论 | Blocker | Warning | Nit | 报告 |
| --- | --- | --- | --- | --- | --- | --- |
| architect | Sonnet | NEEDS_FIX_R2 | 1 (LoopContext 缺 collabRunId 字段) | 4 (map eviction / REPLACE 脆弱 / snapshot vs members 边界 / D2 runtimeStatus 硬编码) + D6 throttler 合同 + java#2 时间类型 | 3 (WS reconnect / API 兼容 note / startedAt 缓存) | `/tmp/spec-review-architect-r1.md` |
| java-reviewer | Sonnet | NEEDS_FIX_R2 | 2 (跨线程无事务 / executor 未指定) | 4 (LoopContext.handle 注入路径 / Map OOM / 鉴权谓词 / TIMESTAMP 类型) | 0 | `/tmp/spec-review-java-r1.md` |
| typescript-reviewer | Sonnet | NEEDS_FIX_R2 | 1 (新事件不在 useChatWsEventHandler dispatch 内) | 5 (WS URL+cleanup / fetch 返回类型 / stuck timer cleanup / App.tsx 路由 / getSession 含 collabRunId) | 0 | `/tmp/spec-review-typescript-r1.md` |

**Judge ruling** (`/tmp/spec-judge-r1.md`, Opus 主会话)：综合 NEEDS_FIX_R2 — 4 个真 blocker（去重合并），13 个 warning（去重合并）。Nit 5 项折叠到 `/tmp/nits-followup-TEAM-COORDINATOR-FOUNDATION.md`，不入 spec 循环。

**Blocker 修复点对照表**：

| Blocker | 修复位置 | 状态 |
| --- | --- | --- |
| B1 LoopContext 缺 collabRunId/handle | tech-design B4 + B4.5 + B4.6 (3 节) | ✅ |
| B2 CollabProgressPersister 跨线程无事务 | tech-design B6（拆 `CollabProgressWriteService` 持 `@Transactional`）| ✅ |
| B3 executor 未指定 | tech-design B6（新建 `progressWriteExecutor` Bean 独立 IO 线程池）| ✅ |
| B4 新事件不在 WS dispatch | tech-design F2（扩展 `useChatWsEventHandler` dispatch + zustand collabRunStore）| ✅ |

**Warning 修复点对照表**：

| Warning | 修复位置 | 状态 |
| --- | --- | --- |
| W1 Map eviction | tech-design B6（Caffeine TTL 5min + `evictSession` 主动调用）| ✅ |
| W2 TIMESTAMPTZ + Instant | tech-design B1 SQL + 数据模型表 | ✅ |
| W3 snapshot 鉴权谓词 | tech-design B5（caller userId == leader userId + IT 用例）| ✅ |
| W4 behavior_rules REPLACE 脆弱 | tech-design B1（前置 verify + DO $$ migration 自验）| ✅ |
| W5 snapshot vs members 边界 | tech-design B5 头部对比表 | ✅ |
| W6 runtimeStatus 硬编码 | tech-design D2（载荷移除该字段）| ✅ |
| W7 ProgressThrottler 合同 | tech-design D6（完整 key/window/规则说明）| ✅ |
| W8 broadcaster non-blocking | tech-design B2（接口 javadoc）| ✅ |
| W9 useCollabRunWebSocket URL+cleanup | tech-design F2（复用 user-channel，zustand 自动管理）| ✅ |
| W10 fetchCollabRunSnapshot 类型 | tech-design F4（导出 `CollabRunSnapshot` / `CollabWsEvent` discriminated union）| ✅ |
| W11 stuck timer cleanup | 测试计划 FE 加 cleanup case | ✅ |
| W12 App.tsx 路由注册 | tech-design F0（新节）| ✅ |
| W13 getSession 含 collabRunId | tech-design F4（前置 grep + DTO 字段补齐 fallback）| ✅ |

### r2（2026-05-12，Plan 对抗 2 / 2）

| Reviewer | 模型 | 结论 | 新 Blocker | 新 Warning | Nit | 报告 |
| --- | --- | --- | --- | --- | --- | --- |
| architect | Sonnet | **PASS_WITH_WARNINGS** | 0 | NW-1 race + NW-3 evict 显式 wiring | NW-2 DiscardOldestPolicy 死代码 | `/tmp/spec-review-architect-r2.md` |
| java-reviewer | Sonnet | NEEDS_FIX_R3 | **NEW-3 (DO $$ H2 兼容)** | NEW-1 expireAfterAccess 语义错 / NEW-2 R8 文字精度 | 0 | `/tmp/spec-review-java-r2.md` |
| typescript-reviewer | Sonnet | NEEDS_FIX_R3 | **NI-3 (F1/F2 签名不一致)** | NI-1 zustand 违反 frontend.md | 0 | `/tmp/spec-review-typescript-r2.md` |

**r1 issues 复审**：✅ 全部 19/19 项确认修复（architect 7/7 + java 6/6 + typescript 6/6）

**Judge ruling**（`/tmp/spec-judge-r2.md`，Opus 主会话）：

按 pipeline.md「循环上限 = 2 轮，第 2 轮 Judge 仍不过 → 回主会话决策（已砍第 3 轮）」。两个新 blocker 均为 r2 spec 修订自引入的**机械错误**（不是架构分歧）：

- NEW-3 (DO $$ H2): 一次性 SQL 行为，移除自验块 + 把 assertion 落到已有 V70MigrationIT
- NI-3 (F1 skeleton): 上一轮重定义 hook 后忘记同步 F1 调用方

主会话作为最终 Judge 直接 apply 5 项 fix（2 blocker + 3 warning），1 项 nit 折叠 follow-up。**不再过 r3 reviewer**（pipeline rule）。

**r2 修复对照表**：

| 编号 | 修复点 | 位置 | 状态 |
| --- | --- | --- | --- |
| F-NEW-3 | 移除 V70 SQL DO $$，断言移到 V70MigrationIT | §B1 | ✅ |
| F-NI-3 | §F1 useCollabRunWebSocket 调用签名改单参 + 返回值结构调整 | §F1 | ✅ |
| F-NEW-1 | Caffeine `expireAfterAccess` → `expireAfterWrite` | §B6 | ✅ |
| F-NW-3 | §B6 step 4 evictSession 改显式代码 + 加测试 case | §B6 + 测试计划 | ✅ |
| F-NI-1 | 移除 zustand，用 TanStack Query `setQueryData` + EventTarget event bus | §F2 + §D9 | ✅ |
| NW-1 acceptable | check-then-act race，加注释说明 idempotent UPDATE 容忍 | §B6 注释 | ✅ |
| NW-2 nit | DiscardOldestPolicy + catch 死代码 → 移除 catch 块 | §B6 直接修 | ✅ |
| NEW-2 minor | R8 文字精度（acceptable，spec 文字层面） | 跳过 | skip |

**spec status: design-draft → design-ready**

### r3 / 后续

**不跑 r3**（pipeline rule）。spec 待用户批准进 Phase 2 Dev。


