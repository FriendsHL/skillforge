# SEC-2 — Lifecycle Hook 来源分类与防绕过架构

> **状态**：已实现（2026-04-25）。此前会话中断时仍标为“无代码改动”，实际本轮已完成三源隔离、agent-authored hook 绑定/审批闭环、前端三段展示，并补齐安全约束。
> **背景**：SEC-2 安全条目（todo.md SEC-2），V1 已调整为不收窄。
> **关联**：N3 Lifecycle Hook 体系（`docs/design-n3-lifecycle-hooks.md`）、P1 Skill 自进化、P4 Code Agent CompiledMethod 审批流、P11 Agent 发现与权限、P15 自省工具层。

---

## 1. 背景与问题

### 1.1 触发场景

讨论 "session 结束自动跑 memory 压缩" 这类**平台默认行为**该如何在 hook 体系内实现时，发现现有架构没有 "系统 hook" 的概念。

### 1.2 代码现状（2026-04-25 核查）

- `HookEntry`（`skillforge-core/.../hook/HookEntry.java`）只有 `handler` / `timeoutSeconds` / `failurePolicy` / `async` / `displayName` 五个字段，**没有 `source` / `isSystem` / `authorAgentId`**。
- `LifecycleHooksConfig` 直接是 `Map<HookEvent, List<HookEntry>>`，序列化往返 `t_agent.lifecycle_hooks` JSON 列。
- `AgentService.updateAgent`（L63-95）对 `lifecycleHooks` 只校验大小/语义，收到 PUT **无条件 `setLifecycleHooks()` 覆盖**。
- 前端 `JsonMode.tsx` 是裸 TextArea，用户保存时整段替换。
- `LifecycleHookDispatcher` 通过 `agentDef.getLifecycleHooks().entriesFor(event)` 拿到列表逐条执行，不区分来源。

### 1.3 三类潜在 hook 来源

未来一年内可预见会有三种来源的 hook，对信任级别 / 写入路径 / 删除权 / 生命周期的需求**根本不同**：

| Source | 信任级别 | 谁能写 | 谁能删 | 生命周期 |
| --- | --- | --- | --- | --- |
| **`system`** | 最高 | 平台代码 / 平台 migration | 平台 only | 跟版本走 |
| **`user`** | 中 | UI / API PUT | 用户本人 | 用户随意改 |
| **`agent`** | 低（LLM 生成） | agent 通过 tool_use 提交 | 审批后定 + 用户可关 | 自进化（version / parentHookId / successRate） |

### 1.4 安全问题

如果三类混存进 `agent.lifecycleHooks`：

- 用户 `curl PUT /api/agents/{id}` 一把梭 → 系统 / agent hook 全没了
- 用户在 JSON 模式手改 → 同上
- UI 上做 "灰卡只读" 只是装饰，绕过路径全开
- agent-authored hook 没有审批门，LLM 当场生效，生产事故风险高

### 1.5 目标

1. **防绕过**：用户的 PUT/JSON 操作物理上无法删除或修改 system / 已审批的 agent hook
2. **透明度**：UI 必须**显示但锁定** system / agent hook，让用户知道后台在跑什么
3. **审批流**：agent 写的 hook 默认 `pending`，必须人或上级 agent 显式 approve 才进入 dispatch
4. **可追溯**：agent hook 必须能查到作者 agentId、生成 sessionId、版本链
5. **V1 闭环**：本次同步实现 agent-authored hook 的查询 / 提交绑定 / 审批后 dispatch；agent 只能创建 `PENDING` 变更，不能绕过审批直接生效

---

## 2. 三种候选架构

### 方案 A：完全存储分离（优先方案）

```
t_agent.lifecycle_hooks (JSONB)        ← 只存 user hook
SystemHookRegistry (代码内 @Component)  ← 只存 system hook（启动时注册）
t_agent_authored_hook (新表)           ← 只存 agent hook + 审批状态
```

`LifecycleHookDispatcher` 在 `dispatch()` 入口三路合并：

```java
List<HookEntry> merge(HookEvent event, AgentDefinition agentDef) {
    var system = systemHookRegistry.entriesFor(event, agentDef);  // 全局或 per-agent
    var user   = agentDef.getLifecycleHooks().entriesFor(event);
    var agent  = agentAuthoredHookRepo.findActive(agentDef.getId(), event);  // approved only
    return concat(system, user, agent);  // 顺序：system → user → agent
}
```

**优点**：
- 防绕过最彻底——curl 改 `t_agent.lifecycle_hooks` **碰不到** system / agent hook
- 三类各自的元数据自然隔离（system 不需要 authorAgentId、agent 不需要平台版本号）
- 删除权天然分离（删 user hook 不影响其他两路）
- agent hook 的审批 / 自进化版本链有独立表更舒服

**缺点**：
- dispatcher 需要改成"合并三路"，触碰核心文件
- system hook 注册机制要设计（代码 vs DB 配置 vs YAML）
- 新增表 + Flyway migration

### 方案 B：单存储 + source 标记

```java
class HookEntry {
    Source source = Source.USER;       // 默认 user，向后兼容
    String authorAgentId;              // 仅 source=AGENT
    ReviewState reviewState;           // 仅 source=AGENT
    Long parentHookId;                 // 自进化链，仅 source=AGENT
    // ...原有字段
}
```

`AgentService.updateAgent` 做 diff 校验：

```java
// 用户 PUT 进来的新 list
var incoming = req.getLifecycleHooks();
// DB 当前的 list
var current = entity.getLifecycleHooks();
// 校验 source != USER 的条目必须保持不变
validateNonUserEntriesUnchanged(current, incoming);
```

**优点**：
- 不新增表
- dispatcher 改动小（单 list 直接遍历）

**缺点**：
- **每个写入点都要不漏校验**——`AgentService.updateAgent` / Flyway seed / agent self-write API / CLI 导入
- 漏一处就回到原点（防绕过失败）
- system / agent hook 的元数据被挤进同一个 entry 类，schema 浮肿
- agent 自进化的版本链放在 entry 上不自然

### 方案 C：A + B 混合（system 分离 + agent 留在 agent 表 + user 留 JSON）

= 方案 A 升级版。**就是我们的目标方案**——本节作为命名澄清。

---

## 3. 推荐方案：方案 A

理由：

1. **防绕过靠物理隔离比靠校验稳**——多个写入点漏一个就破防
2. **三类 hook 元数据需求差异大**，强行同住一个 `HookEntry` 反而难维护
3. **agent hook 的审批流和自进化版本链需要独立表才不别扭**（参考 P4 CompiledMethod / P1 Skill）
4. dispatcher 的"三路合并"是一次性改动，比"散在各处的写入点校验"长期维护成本低

代价：1 张新表 + dispatcher 改"合并"逻辑 + UI 改三段展示。是值得的。

---

## 4. 详细设计（方案 A）

### 4.1 数据模型

#### 4.1.1 system hook：代码内注册

不进 DB。新增 `SystemHookRegistry` 接口和默认实现：

```java
// skillforge-core/.../hook/SystemHookRegistry.java
public interface SystemHookRegistry {
    /** 返回某 agent + event 命中的系统 hook（按注册顺序）。 */
    List<HookEntry> entriesFor(HookEvent event, AgentDefinition agentDef);
}

// skillforge-server/.../hook/SystemHookRegistryImpl.java
@Component
public class SystemHookRegistryImpl implements SystemHookRegistry {
    private final List<SystemHookProvider> providers;  // Spring 自动注入

    @Override
    public List<HookEntry> entriesFor(HookEvent event, AgentDefinition agentDef) {
        return providers.stream()
            .flatMap(p -> p.entriesFor(event, agentDef).stream())
            .toList();
    }
}

// 单个系统 hook 的注册接口
public interface SystemHookProvider {
    List<HookEntry> entriesFor(HookEvent event, AgentDefinition agentDef);
    default String displayId() { return getClass().getSimpleName(); }
    default String description() { return ""; }
}
```

`HookEntry` 上加一个**只读**的 `source` 字段（或者 dispatcher 在合并时给每条标 source，而不是改 `HookEntry` schema）。后者更干净，本设计采用后者：

```java
// dispatcher 内部数据结构，不进 JSON
record DispatchableHook(HookEntry entry, Source source, String sourceId) {}
```

#### 4.1.2 user hook：现有 `t_agent.lifecycle_hooks` JSON 不动

向后兼容，零迁移。

#### 4.1.3 agent hook：新表

```sql
-- V24__agent_authored_hook.sql
CREATE TABLE t_agent_authored_hook (
    id              BIGSERIAL PRIMARY KEY,
    target_agent_id VARCHAR(64) NOT NULL,         -- hook 注册到哪个 agent
    author_agent_id VARCHAR(64) NOT NULL,         -- 哪个 agent 写的
    author_session_id VARCHAR(64),                -- 哪次 session 产出
    event           VARCHAR(64) NOT NULL,         -- HookEvent.wireName()
    handler_json    TEXT NOT NULL,                -- 序列化后的 HookHandler
    timeout_seconds INT  NOT NULL DEFAULT 30,
    failure_policy  VARCHAR(16) NOT NULL DEFAULT 'CONTINUE',
    async           BOOLEAN NOT NULL DEFAULT FALSE,
    display_name    VARCHAR(128),
    review_state    VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING|APPROVED|REJECTED|RETIRED
    review_note     TEXT,
    reviewed_by     VARCHAR(64),                  -- userId 或 agentId
    reviewed_at     TIMESTAMPTZ,
    parent_hook_id  BIGINT,                       -- 自进化版本链
    usage_count     BIGINT NOT NULL DEFAULT 0,
    success_count   BIGINT NOT NULL DEFAULT 0,
    failure_count   BIGINT NOT NULL DEFAULT 0,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_review_state CHECK (review_state IN ('PENDING','APPROVED','REJECTED','RETIRED'))
);

CREATE INDEX idx_aah_active ON t_agent_authored_hook (target_agent_id, event)
    WHERE review_state = 'APPROVED' AND enabled = TRUE;
CREATE INDEX idx_aah_pending ON t_agent_authored_hook (target_agent_id, review_state)
    WHERE review_state = 'PENDING';
```

H2 兼容：`TIMESTAMPTZ` → `TIMESTAMP WITH TIME ZONE`，`BIGSERIAL` → `BIGINT AUTO_INCREMENT`。

### 4.2 dispatcher 合并逻辑

```java
// LifecycleHookDispatcherImpl.dispatch
public boolean dispatch(HookEvent event, Map<String,Object> input,
                        AgentDefinition agentDef, String sessionId, Long userId) {
    var systemHooks = systemHookRegistry.entriesFor(event, agentDef);
    var userHooks   = agentDef.getLifecycleHooks().entriesFor(event);
    var agentHooks  = agentAuthoredHookService.findActive(agentDef.getId(), event);

    // 合并顺序：system → user → agent
    var allEntries = new ArrayList<DispatchableHook>();
    systemHooks.forEach(e -> allEntries.add(new DispatchableHook(e, Source.SYSTEM, ...)));
    userHooks.forEach(e   -> allEntries.add(new DispatchableHook(e, Source.USER,   ...)));
    agentHooks.forEach(e  -> allEntries.add(new DispatchableHook(e, Source.AGENT,  ...)));

    return runChain(allEntries, ...);  // 现有 ChainDecision / SKIP_CHAIN 逻辑不变
}
```

**关键细节**：
- 每条 entry 在 trace span 上打 `hook.source` 标签，方便排查
- `failure_count` / `success_count` 统计只对 agent hook 维护（未来自进化用）
- system hook 命中失败时日志 ERROR 级 + 携带 `displayId()`，便于平台运维

### 4.3 写入路径校验（防绕过）

| 操作 | system | user | agent |
| --- | --- | --- | --- |
| `PUT /api/agents/{id}` | 物理隔离，碰不到 | 全权 CRUD | 物理隔离，碰不到 |
| 前端 JSON 模式 | 物理隔离 | 全权编辑 | 物理隔离 |
| 平台启动 | `@Component` 自动注册 | — | — |
| Agent tool_use 写入 | 拒绝 | 拒绝 | 走 `RegisterHookSkill`（新 skill） |
| Approval API | 拒绝 | 拒绝 | `POST /api/agent-authored-hooks/{id}/approve` |

`AgentService.updateAgent` **完全不需要新校验**——user 之外的 hook 根本不在 `agent.lifecycleHooks` 里。

### 4.4 Agent 查询与提交 hook 的 skill（V1 落地）

```
GetAgentHooksTool:
  input:
    targetAgentId?: string
    targetAgentName?: string
    includeSources?: ["system", "user", "agent"]
  output:
    effectiveHooks:
      system: [...]
      user: [...]
      agentAuthored: [...]

RegisterHookSkill:
  input:
    targetAgentId?: string
    targetAgentName?: string
    event: HookEvent
    methodTarget: string   // immutable target, e.g. compiled:<id>:<versionHash> or builtin allowlist key
    description: string  (审核者会看)
  output:
    hookId: long
    reviewState: "PENDING"
    reviewUrl: string
```

存到 `t_agent_authored_hook` 时强制 `review_state=PENDING`，agent 不能直接发 APPROVED。

`RegisterHookSkill` / `ProposeHookBindingTool` 命名由 Plan 阶段定稿。V1 原则是：只允许绑定不可变且已审批的 method target，避免把未审代码或可变全局 ref 塞进 hook handler。

Plan r1 安全约束：

- agent-authored hook **不绑定可变 `methodRef`**；DB 中保存 method kind + method id + version/hash，展示层可带 `methodRef` 文案。
- V1 允许 active `CompiledMethod` 的不可变版本目标，或明确 allowlist 的 `builtin.*` method；`ScriptMethod` 当前创建后立即 active，缺少审批/版本化，V1 暂不允许作为 agent-authored hook target。
- Tool 输入不接受通用 `HookEntry` / `HookHandler` JSON，不接受 inline script，不接受 skill handler，只接受 method target + args。
- `authorAgentId` 从 `SkillContext.sessionId -> SessionEntity.agentId` 服务端解析，禁止由 tool input 指定。
- approve/reject 不暴露为 Tool；审批 API 不能信任 request body 里的 reviewer identity。
- 当前 `/api/auth/local-token` 允许 localhost 取 token，配合 Bash 无法严格区分“人点 UI”和“本地 agent curl API”。V1 不宣称抵抗恶意本地 agent，只保证 agent hook 不自动生效；真正的人/agent 权限隔离需要 P12 多用户/权限模型承接。

### 4.5 审批流（V1 落地）

- UI：新建 `/agents/{id}/pending-hooks` 页面，列出待审 hook + handler 详情 + 审批按钮
- API：`approve` / `reject` 接口，需要 user 权限（多用户模型上线后细分 admin）
- 审批后才出现在 dispatcher 合并列表里

### 4.6 UI 改动（SEC-2-fe）

`LifecycleHooksEditor` 拆三段展示：

```
┌─ 系统 Hooks (3) ▽ ───────────────────────┐
│ 🔒 [系统] auto-memory-extract            │
│ 🔒 [系统] activity-log                   │
│ 🔒 [系统] safety-skill-blocklist         │
└──────────────────────────────────────────┘
┌─ 你的 Hooks (2) ──────────────────────────┐
│ ✏ feishu-notify-on-error      [edit][x]  │
│ ✏ http-post-summary           [edit][x]  │
│ ＋ 添加 Hook                              │
└──────────────────────────────────────────┘
┌─ Agent 自动生成 (1 待审) ──────────────────┐
│ 🤖 [待审] auto-recover-on-timeout  [审批]│
└──────────────────────────────────────────┘
```

- 系统段：折叠默认收起，灰色卡片，只读，hover 显示 `description()`
- agent 段：分 PENDING / APPROVED 两子组，PENDING 黄色边框
- JSON 模式：**只能编辑 user 段**——前端把 system / agent hook 隔离展示在 JSON 编辑器之外，或编辑器锁定只读片段（具体 UX 待 Plan agent 定）

### 4.7 trace / 可观测

- `LIFECYCLE_HOOK` span 加 attributes：`hook.source` / `hook.source_id` / `hook.author_agent_id`（仅 agent）
- Dashboard Traces 页支持按 source 过滤
- 系统 hook 失败时单独告警通道（不和 user hook 混在一起，避免噪音）

---

## 5. 迁移与回滚

### 5.1 上线步骤

1. **Phase 1**：新增 `SystemHookRegistry` 接口 + 空实现 + dispatcher 合并逻辑（合并空列表 == no-op）
2. **Phase 2**：新增 `t_agent_authored_hook` 表 + Repository + Service（dispatcher 拉空列表 == no-op）
3. **Phase 3**：UI 拆三段展示（agent 段先空着）
4. **Phase 4**：注册第一个 system hook（如 `AutoMemoryExtractHook`）验证全链路
5. **Phase 5**：`GetAgentHooksTool` + `RegisterHookSkill` / `ProposeHookBindingTool` + 审批流 + Pending UI

每个 Phase 都是 backward-compatible，可以独立合入。

### 5.2 回滚路径

- 任一 Phase 出问题：把 dispatcher 合并改回 `userHooks only`，三方代码不影响 user hook 行为
- migration 是新增表，回滚直接 drop 即可，不影响 `t_agent`

### 5.3 数据迁移

无。`t_agent.lifecycle_hooks` 字段语义不变（一直就是 user hook）。

---

## 6. 测试策略

### 6.1 单元测试

- `SystemHookRegistryImplTest`：多 provider 顺序合并 / null-safe
- `LifecycleHookDispatcherImplTest`：三路合并顺序 / 某路空列表 / 某路抛异常不污染其他路
- `AgentAuthoredHookServiceTest`：findActive 只返回 APPROVED + enabled / parent_hook_id 链式查询
- `AgentServiceTest`：PUT /api/agents/{id} 不影响 t_agent_authored_hook（防绕过断言）

### 6.2 集成测试（Testcontainers）

- 端到端：注册 system hook → user hook → agent hook 三类，dispatcher 按预期顺序触发
- 防绕过：模拟 curl PUT 删 user hook 全量空 list，断言 system / agent hook 仍执行
- Trace span：断言每个 hook span 上 `hook.source` 标签正确

### 6.3 浏览器 e2e

- LifecycleHooksEditor 三段展示视觉断言
- JSON 模式下 system / agent hook 不在编辑器内
- Pending hook 审批后立即在 dispatch 列表出现

---

## 7. 风险与边界情况

| 风险 | 应对 |
| --- | --- |
| system hook 性能：每次 dispatch 都走 registry | provider 接口要求 O(1) lookup；必要时加 EnumMap 缓存 |
| agent hook 数量爆炸（agent 滥写） | per-agent 配额上限（如每个 agent 最多 50 条 active）+ 审批拒绝率监控 |
| system hook 升级 → 版本不兼容 | provider 接口加 `version()` 方法，dispatcher 跳过版本不匹配的条目并 log.error |
| agent hook handler 包含敏感数据（API key） | 审批 UI 高亮显示 handler 完整内容，禁止 LLM 在 handler 内写明文 secret（review skill 拦截） |
| 一个 agent 改另一个 agent 的 hook | 允许提交跨 agent 绑定提案，但服务端必须复用 P11 的 target 解析 / visibility / permission；普通 agent 只能写 `PENDING`，最终是否生效由审批决定 |
| 自进化链式回归（v3 不如 v2） | `successRate` 跌破阈值自动 RETIRED，参考 P1 Skill 的 Δ≥15pp 晋升机制 |
| 系统 hook **必须 per-agent disable**（user 想关掉某 system hook） | `t_agent_system_hook_override`（agentId, providerId, enabled）轻量表；dispatcher 合并时过滤；本次 Plan 阶段决定是否进 v1 |

---

## 8. 与现有功能的关系

- **N3 Lifecycle Hook**：本设计是 N3 的纯增强，所有现有 user hook 行为不变
- **P4 Code Agent CompiledMethod**：审批流可以借鉴 / 复用相同 pattern
- **P1 Skill 自进化**：agent hook 的 version / parentHookId / successRate 直接对齐 P1 设计
- **P15 Session Analyzer**：未来 Analyzer 可以分析 agent hook 的命中率 / 失败模式

---

## 9. Plan 阶段待 Planner 决议的开放问题

留给 Full Pipeline 的 Plan 阶段做 A/B 决断：

1. **system hook 注册机制**：纯代码 `@Component` vs 平台 YAML 配置 vs 平台 DB seed？三种各有取舍
2. **system hook per-agent override**：v1 是否做？做了多一张表，不做用户没有逃生口
3. **`HookEntry.source` 字段是否进 schema**：还是只在 dispatcher runtime 标？前者 trace 友好，后者 schema 干净
4. **JSON 编辑器三段隔离的具体 UX**：三个独立 JSON 块 vs 单 JSON 内只读片段
5. **agent hook 自进化版本表是否独立 `t_agent_authored_hook_version`**：还是 parent_hook_id 自引用够用
6. **审批流是否需要"上级 agent 审批"通道**（未来多 agent 协作场景）

---

## 10. 工作量预估

| 阶段 | 子任务 | 估时 |
| --- | --- | --- |
| Phase 1+2 | dispatcher 合并 + Registry + 新表 + Repository | 2-3 天 |
| Phase 3 | UI 拆三段 + 折叠 + 系统徽章 | 1.5-2 天 |
| Phase 4 | 注册 1-2 个真实 system hook + e2e | 1 天 |
| Phase 5 | GetAgentHooksTool + RegisterHookSkill/ProposeHookBindingTool + 审批流 + Pending UI | 3-5 天 |
| **v1 合计** | Phase 1-5 | **8-12 天** |

按 todo.md 的"穿插，独立 PR"节奏：V1 不收窄，Phase 5 纳入本次 Full Pipeline；实际落地可拆多个 PR，但语义上属于同一个 SEC-2 V1 闭环。

---

## 11. 决策待回顾点

- [x] 是否同意"方案 A 优先 + agent 通道进入 V1"
- [x] v1 范围：Phase 1-5 全部进入 V1，不再只预留 agent-authored hook
- [x] system hook per-agent disable 是否进 v1：不进 V1；如后续有强需求再加 `t_agent_system_hook_override`
- [x] Plan 阶段交给 Planner 出 A/B/C 完整对比，还是直接按本文落地：按本文方案 A 落地，补齐 V1 agent-authored 闭环

---

## 附：与 todo.md SEC-2 条目的对应

| todo.md 描述 | 本设计对应 |
| --- | --- |
| 方案 A（存储分离）vs B（source 标记） | §2 + §3 决策为方案 A |
| 触碰核心文件 → Full Pipeline | §4.2 dispatcher 改动 + §4.1 schema 新增确认 |
| Plan 阶段让 Planner 出 A/B 对比 | §9 开放问题留给 Plan |
| SEC-2-fe 折叠分组 + 系统徽章 | §4.6 UI 设计 |
| JSON 模式保护 | §4.3 + §4.6 |
| agent 自写 hook 集成进入 V1 | §1.3 + §4.4 + §4.5 |
