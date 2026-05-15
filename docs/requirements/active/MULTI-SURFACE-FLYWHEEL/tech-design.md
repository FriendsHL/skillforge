# MULTI-SURFACE-FLYWHEEL V4 技术方案

---
id: MULTI-SURFACE-FLYWHEEL
status: ratified
mode: full
created: 2026-05-15
updated: 2026-05-15
---

## §1 总览

V4 把飞轮第二个可优化 surface —— **behavior_rule** —— 接入完整的"自动归因 → A/B 评测 → canary
灰度 → promote → 反向回路"管线，并基于 V2 / V3 已交付的 `SkillAbEvalService` +
`PromptImproverService` 实际形态抽出 `OptimizableSurface<V>` 接口 + `AbstractAbEvalRunner<V>`
Template Method，把 3 个 surface 的 A/B 评测共同骨架收口。

**lifecycle_hook 推 V5**（5 ratify #1）—— 现行 hook 改动频率季度级，ROI 低于 behavior_rule。
等真有较高编辑频率信号再加。

**phase 拆分**：

- **Phase 1.0** ✅ Phase 1.0 调研 + 5 ratify 锁定（2026-05-15）
- **Phase 1.1** ⏳ behavior_rule surface 落地 + OptimizableSurface 接口填实 + 3 实现类 + JPA IT
- **Phase 1.2** ⏳ AbstractAbEvalRunner Template Method 抽取 + 重构现有 2 service 共用骨架
- **Phase 1.3** ⏳ CanaryAllocator 泛型化 + behavior_rule canary 接入 + V3 attribution dispatchBehaviorRuleSurface
- **Phase 1.4** ⏳ Dashboard behavior rule panel（复用 V2 canary panel 模板）
- **Phase Final** ⏳ e2e + 真启 cron 跑一遍 + 归档

---

## §0 现状证据（Phase 1.0 已校对）

### 0.1 复用清单

| 复用项 | 文件路径 | 用途 |
|---|---|---|
| V2 `SkillAbEvalService.createAndTrigger` | `skillforge-server/src/main/java/com/skillforge/server/improve/SkillAbEvalService.java` line 121 | V4 抽 `OptimizableSurface<V>.injectForSandbox` + `judgeAndCompare` hook 的参考实现 |
| V3 `PromptImproverService.startImprovement` | `.../improve/PromptImproverService.java` line 180 | V4 抽 `OptimizableSurface<V>.createCandidate` 参考；BehaviorRuleImproverService 直接 clone 这个模板 |
| V3 `PromptVersionEntity` + `PromptAbRunEntity` | `.../entity/` | behavior_rule schema 完全镜像这两张表 |
| V1 N2 `BehaviorRuleRegistry` | `skillforge-server/.../skill/BehaviorRuleRegistry.java` | startup 加载 `behavior-rules.json` 内置规则；V4 扩 `getActiveVersion(agentId)` DB 查询 + fallback 到 startup config |
| V1 `OptimizableSurface<V>` 空接口骨架 | V1 落地 | V4 才填实，6 个 method 签名见 §2.1 |
| V2 `CanaryAllocator` | `.../canary/CanaryAllocator.java` | V4 改泛型 `<V>` 接受任意 surface（Phase 1.3 做，Phase 1.1 不动） |
| V3 `AttributionApprovalService.runCandidateGeneration` | `.../attribution/AttributionApprovalService.java` | V4 加 `dispatchBehaviorRuleSurface` 分支 |
| V3 `t_optimization_event` 状态机 | V80 已落地 | V4 不动 schema，behavior_rule 走 surface_type='behavior_rule' 路径 |

### 0.2 核心文件清单（V4 全程 0 行 diff，Iron Law）

跟 V3 一致：

- `skillforge-server/.../entity/SessionEntity.java`
- `skillforge-server/.../entity/SessionMessageEntity.java`
- `skillforge-server/.../service/ChatService.java`
- `skillforge-server/.../service/SessionService.java`
- `skillforge-server/.../service/CompactionService.java`
- `skillforge-core/.../engine/AgentLoopEngine.java`
- `skillforge-core/.../engine/Message.java`
- `skillforge-server/.../tool/GetTraceTool.java`

V4 重构 SkillAbEvalService + PromptImproverService **不属于核心文件**（V4 触碰 OK，但要保现有
测试全绿，零行为漂移）。

### 0.3 V4 触碰的现有文件

| 文件 | 改动 |
|---|---|
| `SkillAbEvalService.java` | Phase 1.2 改用 `AbstractAbEvalRunner<SkillEntity>` 继承；现有签名 + 行为保留 |
| `PromptImproverService.java` | Phase 1.2 改用 `AbstractAbEvalRunner<PromptVersionEntity>` 继承 |
| `CanaryAllocator.java` | Phase 1.3 改泛型 `<V>` |
| `BehaviorRuleRegistry.java` | Phase 1.1 扩 `getActiveVersion(agentId)` DB 查询 + cache，保留 startup baseline fallback |
| `AttributionApprovalService.java` | Phase 1.3 加 `dispatchBehaviorRuleSurface` 分支 |

### 0.4 Phase 1.0 证伪步骤

1. ✅ grep `SkillAbEvalService.createAndTrigger` 看真实方法签名（参数 5 个）+ runAbTestAsync 流程
2. ✅ grep `PromptImproverService.startImprovement` 同 signaturing 对照（参数 3 个 — 注意不一致）
3. ✅ grep `OptimizableSurface` 全 codebase，看 V1 留的空骨架
4. ✅ grep `BehaviorRuleRegistry` 看现行 startup 加载行为 + 添加 DB 查询的钩子点
5. ✅ grep `behavior-rules.json` 看内置规则形态
6. ✅ grep `CanaryAllocator allocate` 方法签名 + V2 V3 实际 caller

---

## §2 `OptimizableSurface<V>` 抽象

### 2.1 接口定义（6 method）

替换 V1 留的空骨架，按 V2 + V3 实际 service 形态抽：

```java
/**
 * V4 飞轮第二、三个 surface 接入时填实的统一抽象。每个 surface 实现负责
 *  - 加载 agent 当前激活的 baseline 版本（active SkillEntity / 当前 promptVersionId
 *    指向的 PromptVersionEntity / BehaviorRuleRegistry 返回的 active version row）
 *  - 根据 LLM 推理生成 candidate 版本实体
 *  - 在 A/B 评测沙箱中"注入" baseline / candidate（替换 agent 视角的 surface 内容）
 *  - 通过率 / 通过门槛达标后 promote candidate 为 active
 *  - 必要时回滚到上一个 active
 *
 * @param <V> surface 对应的版本实体类型（SkillEntity / PromptVersionEntity /
 *            BehaviorRuleVersionEntity）
 */
public interface OptimizableSurface<V> {

    /** "skill" / "prompt" / "behavior_rule"；匹配 t_optimization_event.surface_type 字段 */
    String surfaceType();

    /** 加载 agent 当前激活的 baseline 版本（每 agent + surface 唯一） */
    V loadActive(Long agentId);

    /** 加载指定版本 id 的实体（A/B 跑 candidate 时拿 candidate 行用） */
    V loadVersion(String versionId);

    /**
     * 根据 attribution 的 description / current baseline / agent 上下文，调 LLM 生成 candidate。
     * Phase 1.1 实现：BehaviorRuleSurface.createCandidate 调 defaultProvider（5 ratify #5）。
     *
     * @param baseline           当前 baseline 版本
     * @param improvementContext 来自 attribution event 的 description + change_type 等
     * @return 已持久化的 candidate 版本（status=candidate，未激活）
     */
    V createCandidate(V baseline, String improvementContext);

    /**
     * A/B 评测沙箱中替换 agent 视角的 surface 内容（baseline vs candidate）。例如
     *  - SkillSurface: 在 SandboxSkillRegistry 中把 active skill 内容替换为 version 的
     *  - PromptSurface: agent.systemPrompt 临时换成 candidate.content
     *  - BehaviorRuleSurface: BehaviorRuleRegistry 临时返回 candidate 版本规则
     */
    void injectForSandbox(SandboxContext ctx, V version);

    /**
     * candidate 通过 A/B + canary 后 promote 为 active。同时把上一个 active 标 retired。
     * 实现负责更新 surface 对应的 active 指针（agent.skill_ids / agent.promptVersionId /
     * BehaviorRule active 标志）+ 触发对应 promotion event（PromptPromotedEvent /
     * SkillAbCompletedEvent + promoted=true）。
     */
    void promote(V candidate);

    /** 回滚：把当前 active 标 retired，恢复上一个 production 版本指针。 */
    void rollback(V candidate);
}
```

**SandboxContext** 是 V4 引入的轻量 record：

```java
public record SandboxContext(
        Long agentId,
        String sessionId,         // sandbox 跑的 isolated session
        SandboxSurfaceFactory<?> factory  // surface 替换的 fan-out 钩子
) {}
```

### 2.2 三个实现类（Phase 1.1 落地）

| 实现类 | V | active 加载 | createCandidate 路径 | promote 路径 |
|---|---|---|---|---|
| `SkillSurface` | `SkillEntity` | `agent.skill_ids` JSON + skillRepository.findActive | `SkillDraftService.createDraftFromAttribution` (V3 落地) → 调用方负责 merge 进 SkillEntity | `SkillAbEvalService.promoteCandidate` (V2 落地) |
| `PromptSurface` | `PromptVersionEntity` | `agent.promptVersionId` → repository | `PromptImproverService.generateCandidatePromptFromAttribution` (V3.1 commit `91c3108` 落地) | `PromptPromotionService.evaluateAndPromote` |
| `BehaviorRuleSurface` | `BehaviorRuleVersionEntity` (V82 新表) | `BehaviorRuleRegistry.getActiveVersion(agentId)` (V4 新方法) | `BehaviorRuleImproverService.generateCandidate` (V4 新方法，clone PromptImprover 模板) | `BehaviorRulePromotionService.promote` (V4 新) |

### 2.3 SurfaceRegistry

```java
@Component
public class SurfaceRegistry {
    private final Map<String, OptimizableSurface<?>> bySurfaceType = new HashMap<>();

    public SurfaceRegistry(List<OptimizableSurface<?>> surfaces) {
        for (OptimizableSurface<?> s : surfaces) {
            bySurfaceType.put(s.surfaceType(), s);
        }
    }

    @SuppressWarnings("unchecked")
    public <V> OptimizableSurface<V> get(String surfaceType) {
        OptimizableSurface<?> s = bySurfaceType.get(surfaceType);
        if (s == null) {
            throw new IllegalArgumentException("Unknown surface type: " + surfaceType);
        }
        return (OptimizableSurface<V>) s;
    }
}
```

Spring 自动注入所有 `OptimizableSurface<?>` 实现类（@Component），按 `surfaceType()` 返回值
查表分发。`AttributionApprovalService` + `AbstractAbEvalRunner` 都通过这个 registry 拿对应
surface 的实现。

---

## §3 `AbstractAbEvalRunner<V>` Template Method

### 3.1 4 个 hook 顺序

V2 SkillAbEvalService.runAbTestAsync + V3 PromptImproverService.runImprovementAsync + AbEvalPipeline.run
现行序列对照后抽 4 hook，公共骨架在 abstract 类的 `final run()` 里：

```
final run(abRunId):
  1. loadAbRun (公共)
  2. inject baseline → 跑 baseline 那一组 evals
     → injectForSandbox(ctx, baseline)            [surface 特化]
     → runEvalSet(baselineEvalRun)                (公共)
  3. inject candidate → 跑 candidate 那一组 evals
     → injectForSandbox(ctx, candidate)           [surface 特化]
     → runEvalSet(candidateEvalRun)               (公共)
  4. 比对 + 决定 promote
     → judgeAndCompare(baselineRun, candidateRun) [surface 特化]
     → shouldPromote(comparison)                  [surface 特化]
     → if (shouldPromote) promoteIfNeeded(candidate) [surface 特化]
  5. 写 abRun.status + 发 publishedAbCompletedEvent (公共)
```

### 3.2 hook 签名

```java
public abstract class AbstractAbEvalRunner<V> {

    protected final OptimizableSurface<V> surface;

    protected AbstractAbEvalRunner(OptimizableSurface<V> surface) {
        this.surface = surface;
    }

    /** Template method — 子类不可 override */
    public final AbRunResult run(String abRunId, V baseline, V candidate,
                                 SandboxContext ctx) {
        // ...公共骨架，调下面 4 hook
    }

    /** Hook 1: 替换 sandbox 视角的 surface 内容（已在 OptimizableSurface 接口里） */
    // 直接调 surface.injectForSandbox(ctx, version)

    /** Hook 2: 跑完 baseline + candidate 后比对（surface 特化 — 不同 surface
     *  scoreboard 维度可能不同：skill 看 pass_rate, prompt 看 composite, behavior_rule
     *  看 violation_count 等） */
    protected abstract Comparison judgeAndCompare(EvalRun baseline, EvalRun candidate);

    /** Hook 3: 通过门槛判定（surface 特化 — 默认 pass_rate 差 ≥ 5pp 则 promote，但
     *  behavior_rule 可能用 violation 减少率） */
    protected abstract boolean shouldPromote(Comparison comparison);

    /** Hook 4: promote 路径（surface 特化 — 走 OptimizableSurface.promote） */
    protected abstract void promoteIfNeeded(V candidate, Comparison comparison);
}
```

### 3.3 现有代码 → 重构 map

| 现有方法 | Phase 1.2 重构后 |
|---|---|
| `SkillAbEvalService.runAbTestAsync` 200 行 | 继承 `AbstractAbEvalRunner<SkillEntity>` + 实现 4 hook 共 ~80 行；公共骨架走 abstract `run` |
| `PromptImproverService.runImprovementAsync` 150 行 | 继承 `AbstractAbEvalRunner<PromptVersionEntity>` + 4 hook ~60 行 |
| `AbEvalPipeline.run` 100 行 | 抽进 abstract `runEvalSet` 公共方法 |

零行为漂移目标：现有 `SkillAbEvalServiceTest` + `PromptAbRunIT` + `AbEvalPipelineTest` 全绿。
**重构前先把 Phase 1.1 behavior_rule surface 跑通**，验证 6-method 接口形态真 work，再 Phase
1.2 抽 Template Method —— 避免 plan.md "过早抽象 = 大失败" footgun。

---

## §4 behavior_rule schema + service

### 4.1 V82 Flyway migration

镜像 PromptVersion + PromptAbRun 表形态，专门给 behavior_rule。

```sql
-- V82__create_behavior_rule_versioning.sql

CREATE TABLE t_behavior_rule_version (
    id                      VARCHAR(36) PRIMARY KEY,
    agent_id                VARCHAR(36) NOT NULL,
    version_number          INT NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'candidate',
    -- 'candidate' / 'active' / 'retired' / 'rejected'
    rules_json              TEXT NOT NULL,
    -- JSON array: [{id, priority, when, then, rationale}], schema 跟现有
    -- behavior-rules.json 一致
    source                  VARCHAR(32) NOT NULL DEFAULT 'manual',
    -- 'manual' / 'attribution' / 'auto_improve'
    improvement_rationale   TEXT,
    source_event_id         BIGINT,   -- 对 t_optimization_event.id 弱关联（无 FK，避免跨模块耦合）
    baseline_version_id     VARCHAR(36),  -- 基于哪个 baseline 改的
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    promoted_at             TIMESTAMPTZ
);

CREATE INDEX idx_brv_agent_status ON t_behavior_rule_version(agent_id, status);
CREATE INDEX idx_brv_active ON t_behavior_rule_version(agent_id) WHERE status = 'active';
-- partial UNIQUE：每个 agent 最多 1 个 active 行
CREATE UNIQUE INDEX uq_brv_one_active ON t_behavior_rule_version(agent_id) WHERE status = 'active';

CREATE TABLE t_behavior_rule_ab_run (
    id                       VARCHAR(36) PRIMARY KEY,
    agent_id                 VARCHAR(36) NOT NULL,
    baseline_version_id      VARCHAR(36) NOT NULL,
    candidate_version_id     VARCHAR(36) NOT NULL,
    baseline_eval_run_id     VARCHAR(36),   -- 可选，挂上对应 evalRun id
    status                   VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    baseline_pass_rate       DOUBLE PRECISION,
    candidate_pass_rate      DOUBLE PRECISION,
    delta_pass_rate          DOUBLE PRECISION,
    promoted                 BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reason           TEXT,
    ab_scenario_results_json TEXT,
    triggered_by_user_id     BIGINT,
    started_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at             TIMESTAMPTZ
);

CREATE INDEX idx_brar_agent_status ON t_behavior_rule_ab_run(agent_id, status);
CREATE INDEX idx_brar_candidate ON t_behavior_rule_ab_run(candidate_version_id);
```

### 4.2 BehaviorRuleImproverService (Phase 1.1)

完全镜像 PromptImproverService 模板：

```java
@Service
public class BehaviorRuleImproverService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImprovementStartResult startImprovementFromAttribution(
            Long eventId, String agentId, String attributedDescription, Long ownerId) {
        // 1. 加载当前 active version (BehaviorRuleSurface.loadActive)
        // 2. 同步调 LLM 生成 candidate rules JSON (V3.1 同款同步 LLM fill)
        // 3. 持久化 BehaviorRuleVersionEntity status=candidate, source='attribution'
        // 4. 返回 ImprovementStartResult(agentId, abRunId=null, candidateVersionId, "PENDING")
    }

    private String generateCandidateRulesFromAttribution(
            BehaviorRuleVersionEntity baseline, String attributedDescription) {
        // LLM 调用：current rules JSON + attribution rationale → improved rules JSON
        // 用 defaultProvider（5 ratify #5 锁）
    }
}
```

### 4.3 BehaviorRuleRegistry 扩展

**Phase 1.1 实施决策（dev judgment ratified by reviewer 2026-05-15）**：
`getActiveVersion(agentId)` 实际**实现在 server 层 `BehaviorRuleSurface.loadActive`**，
而**不是** `core` 模块的 `BehaviorRuleRegistry`。原因：

- `skillforge-core` 模块的 `BehaviorRuleRegistry` 不能引 server 层的
  `BehaviorRuleVersionRepository` + `BehaviorRuleVersionEntity`（JPA + server-only
  依赖）→ 反向依赖造成模块循环
- Phase 1.0 调研里 spec §4.3 假设两层可互相引用，落地时发现不成立
- Phase 1.1 落点：cache + DB 查询逻辑全在 `BehaviorRuleSurface`（server）；
  `BehaviorRuleRegistry`（core）现行 startup 加载 `behavior-rules.json` 行为完全保留
- Phase 1.3 `AttributionApprovalService.dispatchBehaviorRuleSurface` 调
  `BehaviorRuleSurface.loadActive()` 获取 baseline，**不**调 `BehaviorRuleRegistry`
- 下面这段保留作 "本来设想的 API 形态" 参考，**不**是当前实现

```java
@Component
public class BehaviorRuleRegistry {

    // 现有 startup 加载逻辑保留，作 fallback baseline
    private final Map<String, BehaviorRule> startupRules;

    private final BehaviorRuleVersionRepository versionRepository;
    private final Cache<Long, BehaviorRuleVersionEntity> activeVersionCache;

    /**
     * V4 新方法：查 DB 当前 agent 的 active behavior_rule version。
     * 没有 active 行就 fallback 到 startup 加载的内置规则。
     * Cache TTL 5min 避免每次 agent 调用都查 DB。
     */
    public BehaviorRuleVersionEntity getActiveVersion(Long agentId) {
        BehaviorRuleVersionEntity cached = activeVersionCache.getIfPresent(agentId);
        if (cached != null) return cached;

        BehaviorRuleVersionEntity active = versionRepository
                .findByAgentIdAndStatus(agentId.toString(), "active")
                .orElse(null);

        if (active != null) {
            activeVersionCache.put(agentId, active);
            return active;
        }
        // Fallback: 把内置规则包装成 ad-hoc version 返回（versionNumber=0, status='active'）
        return buildStartupBaseline(agentId);
    }
}
```

**启动性能**：startup baseline 仍走 application 启动时一次性加载（现行行为），DB 查询只在
runtime 按需触发，不延长 spring boot up 时间。

---

## §5 Canary rollout 泛型化 (Phase 1.3)

### 5.1 CanaryAllocator 改泛型

V2 现行 `allocate(sessionId, agentId, baselineSkillName)` 只支持 skill。Phase 1.3 改成：

```java
public class CanaryAllocator {
    public String allocate(String sessionId, Long agentId, String surfaceType,
                           String baselineIdentity) {
        // ... 现行 hash-based 分流逻辑保留
        // baselineIdentity 替代原 skill name —— skill 传 skill name, prompt 传
        // promptVersionId, behavior_rule 传 behavior_rule version id
    }
}
```

`t_session_annotation` 写 `annotation_type='canary_group_<surface_type>'`（V1 写 `canary_group`
保留，V4 加细分；查询时 union 两边）。

### 5.2 canary 互斥（5 ratify #4 锁）

保留 "**同 agent 1 active canary，跨所有 surface**" 约束（V2 partial UNIQUE 已实现）。这意味着：

- agent X 当前在 skill canary 中 → 不能同时起 prompt canary
- 防止多 surface 同时变动导致 confounding（多变量变动无法归因哪个生效）
- 串行：skill canary 完成 → promote → 再起 prompt canary

V5+ user simulator 后允许 true multi-arm trials 时再放开。

---

## §6 持久化 & identity 列不变量

### 6.1 不动 t_session_message

V4 不加任何 t_session_message identity 列，不触发 `identity-column-on-rewrite.md` Iron Law。

### 6.2 复用 V1 session_annotation

`canary_group` 写 t_session_annotation 这条已经在 V2 落地，V4 只是 annotation_type 加 surface
后缀。仍走 V1 W2 教训的 `INSERT ... ON CONFLICT ON CONSTRAINT DO NOTHING` 路径，不复发 PG
aborted-tx bug。

---

## §7 Phase 1.1 实施清单

### 7.1 代码改动

- [ ] `OptimizableSurface<V>.java` — 6-method 接口（替换 V1 空骨架）
- [ ] `SkillSurface.java` — 第一个实现（包裹现有 SkillAbEvalService + SkillDraftService 入口）
- [ ] `PromptSurface.java` — 第二个实现（包裹现有 PromptImproverService + PromptPromotionService）
- [ ] `BehaviorRuleSurface.java` — 第三个新实现
- [ ] `SurfaceRegistry.java` — Spring @Component
- [ ] `BehaviorRuleVersionEntity.java` + `BehaviorRuleAbRunEntity.java`
- [ ] `BehaviorRuleVersionRepository.java` + `BehaviorRuleAbRunRepository.java`
- [ ] `BehaviorRuleImproverService.java` — 同步 LLM fill 模板
- [ ] `BehaviorRuleRegistry.getActiveVersion(agentId)` 方法 + cache
- [ ] `AbstractAbEvalRunner<V>.java` — abstract class **骨架**（abstract method 声明 + 公共
  `run()` 模板），**Phase 1.1 暂不真接入** SkillAbEvalService / PromptImproverService，先验证
  接口形态

### 7.2 Schema

- [ ] V82 migration `V82__create_behavior_rule_versioning.sql`

### 7.3 测试

- [ ] `BehaviorRulePersistenceIT` (Testcontainers) 4 case：version write+read / ab_run 写入 /
  partial UNIQUE 跨 agent 同 surface / status 转换
- [ ] `BehaviorRuleImproverServiceTest` — Mockito 3 case (happy path / LLM 错失败 /
  REQUIRES_NEW tx 隔离 javadoc 注释)
- [ ] `OptimizableSurfaceRegistryTest` — 3 surface registration + lookup
- [ ] `SkillSurface` / `PromptSurface` 通过现有 SkillAbEvalServiceTest /
  PromptImproverServiceTest **复用**测试，不写新 test（adapter pattern 行为不漂移）

### 7.4 Phase 1.1 不做

- ❌ AbstractAbEvalRunner 真重构 SkillAbEvalService + PromptImproverService（Phase 1.2 做）
- ❌ CanaryAllocator 泛型（Phase 1.3 做）
- ❌ AttributionApprovalService.dispatchBehaviorRuleSurface（Phase 1.3 做）
- ❌ FE Dashboard behavior rule panel（Phase 1.4 做）
- ❌ BehaviorRuleAbEvalService eval pipeline 真接入（Phase 1.2 配合 Template Method 一起做）

---

## §8 Ratify Decisions（Phase 1.0 → Phase 1.1 Gate）

**5 个 ratify 2026-05-15 全 user 锁定，Phase 1.1 gate OPEN。**

| # | 决策 | 锁定结果 | 状态 |
|---|------|---------|------|
| 1 | lifecycle hook 是否纳入 V4 | **推 V5**（hook 改动季度级，ROI 不够单独建表） | ✅ Ratified 2026-05-15 |
| 2 | `OptimizableSurface<V>` 接口签名 | **6-method**（surfaceType / loadActive / loadVersion / createCandidate / injectForSandbox / promote / rollback）| ✅ Ratified 2026-05-15 |
| 3 | `AbstractAbEvalRunner<V>` hook 顺序 | **4-hook**（injectForSandbox → judgeAndCompare → shouldPromote → promoteIfNeeded） | ✅ Ratified 2026-05-15 |
| 4 | Canary 互斥 | **保留"同 agent 1 active canary 跨所有 surface"**（防 confounding） | ✅ Ratified 2026-05-15 |
| 5 | behavior_rule candidate LLM model | **同 PromptImprover 用 defaultProvider** | ✅ Ratified 2026-05-15 |

---

## §9 风险 & footgun

| 风险 | 缓解 |
|---|---|
| ⚠️ **过早抽象 = 大失败**（plan.md §V4 footgun #1）| Phase 1.1 只填 OptimizableSurface 接口 + 3 实现，**不**真重构 2 个现有 service。Phase 1.2 跑通后再抽 Template Method |
| 重构 `SkillAbEvalService` + `AbEvalPipeline` 行为漂移 | 现有测试必须全程保持绿（V1 经验：refactor 零行为漂移 + 现有 test 锁） |
| `behavior_rules.json` 内置规则库 → v0 baseline migration | 不迁移历史数据 — V82 schema 上线时 t_behavior_rule_version 空表，BehaviorRuleRegistry fallback 启动 baseline 保现行行为；首个 attribution-derived candidate 写入时建第一个 active 版本 |
| V2 `CanaryAllocator` 改泛型 → 现行 skill canary 路径退化 | Phase 1.3 做泛型时**保留 `allocate(sessionId, agentId, baselineSkillName)` 重载**作 deprecated wrapper，新版 `allocate(sessionId, agentId, surfaceType, baselineIdentity)` 接管；2 个 caller 同步迁移 |
| `BehaviorRuleRegistry` 加 DB 查询拖慢启动 | DB 查询 lazy on-demand + 5min cache，启动时只走 startup config 加载 |
| V3 attribution 已用 surface_type='behavior_rule' label，但 Phase 1.0 没真出过这种 pattern | 不阻塞 — V4 落地后 attribution 真出 behavior_rule label 才走 V4 接入流程；现行 V3 noise pattern (failure/skill/...) 不受影响 |

---

## §10 变更记录

- 2026-05-15：claude 初稿（Phase 1.0 调研报告衍生）；5 ratify 决策全 user 锁定（lifecycle hook→V5 / 6-method 接口 / 4-hook Template Method / canary 跨 surface 互斥 / behavior_rule LLM=defaultProvider）；Phase 1.1 scope 锁定
- 2026-05-15：tech-design 中文化（user 偏好）
