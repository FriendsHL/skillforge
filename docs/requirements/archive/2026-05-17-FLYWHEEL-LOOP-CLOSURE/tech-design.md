# FLYWHEEL-LOOP-CLOSURE 技术方案

---
id: FLYWHEEL-LOOP-CLOSURE
status: delivered
prd: ./prd.md
risk: Mid → upgraded Full
mode: mid → upgraded full
created: 2026-05-16
completed: 2026-05-17
---

## TL;DR

修飞轮 ⑤ 4 缺口 + canary logic disable + V3.2 missing link fix。Mid 档 ~4-6 工作日。0 schema 重构 (1 简单 UPDATE migration)，复用 V3.1 + V4 全套已成熟 pattern。

## 现状证据 (2026-05-16 dogfood manual verify 暴露)

### 缺口 A: V3 attribution candidate 没 A/B endpoint

- `PromptImproveController.startImprovement` 唯一 endpoint 需要 `evalRunId` (eval-driven path)
- `PromptImproverService.startImprovementFromAttribution` (commit `cc87776` V3.1) 只创建 candidate version，**没触发 A/B**
- V3 commit `99df219` Phase 1.4 显式砍这层 wire (留作 backlog)

### 缺口 B+C: candidate_ready → A/B 没 EventListener

- `AttributionApprovalService.ALLOWED_TRANSITIONS` (V3 Phase 1.3) 含 `candidate_ready → ab_running` 边
- 但没人 fire `candidate_ready` stage_change event 到外部 listener
- `OptimizationEventStageListener` 存在但只 mirror SkillAbCompletedEvent / BehaviorRulePromotedEvent 等下游事件

### 缺口 D: SkillDraft attribution path 是 stub

- `SkillDraftService.createDraftFromAttribution` 写 `description / promptHint / extractionRationale`
- **skill_path / triggers / required_tools / 实际 SKILL.md content 留空**
- javadoc 注 "Approve flow's render step will attempt to synthesize a SKILL.md from description + promptHint" — 但这个 render step **从未实施**

### 缺口 F: system agent 没 EvalScenario

- `agent 9 attribution-curator` 历史 `t_eval_run.agent_definition_id='9'` 0 rows
- attribution-curator 自改进路径无 baseline scenario 可比

### V3.2 漏 link bug (附带)

- event 31 approve 后 `t_prompt_version` 写新 row id=`2c66e958` (BE log 确认)
- **但 `t_optimization_event.candidate_prompt_version_id=NULL`** (link 没 wire)
- 分析: `AttributionApprovalService.runCandidateGeneration` 内 dispatch 后没 `event.setCandidatePromptVersionId(versionId)`，PromptImproverService 返 ImprovementStartResult 但 AttributionApproval 没消费 promptVersionId
- 同款 bug 估计 skill / behavior_rule path 也有

## 范围决策

| 决策 | 结论 | 理由 |
|---|---|---|
| canary 砍法 | **Logic disable 不删 code** (ratify #1) | 未来加回灰度 ~2 行改动；删 V2/V4 工作高风险 |
| SkillDraft auto-fill pattern | **跟 SkillDraftService.extractFromRecentSessions 同款 sync LLM** (ratify #2，2026-05-16 Phase 1.0 修正) | 复用 SkillDraftService 内已有的 `EXTRACT_PROVIDER_NAME="xiaomi-mimo"` + `EXTRACT_MODEL="mimo-v2.5-pro"` 常量 (L65-66) + LlmProviderFactory 注入 + maxTokens=4000。注：V3.1 PromptImproverService 用的是 `defaultProviderName` + maxTokens=2000 不硬编 mimo，跟 SkillDraft 现有路径不同 → 选 SkillDraft 自己 service 内一致的模板更干净。REQUIRES_NEW / audit-trail rethrow 与 V3.1 同款保留。|
| A/B trigger 机制 | **`@EventListener`** (ratify #3) | 跟 V3.2 SkillAbCompletedEvent / V4 BehaviorRulePromotedEvent 同款 pattern, 及时 |
| system agent eval fallback | **`/run-ab` endpoint 加 fallback 从 pattern.members 生临时 scenario** (ratify #4 同期做) | 让 attribution-curator self-improve 路径也能跑通 ~1 天 leg |
| ALLOWED_TRANSITIONS 改法 | **加 ab_passed → promoted 直接边** (保留 ab_passed → canary_started 向后兼容) | V3 attribution 路径走新边，未来加回 canary 走老边 |
| V3.2 link 接通 (UUID 旁路列) | **V88 加 nullable `candidate_prompt_version_uuid` + `candidate_skill_draft_uuid` VARCHAR(36) 旁路列** (ratify #5，2026-05-16 Phase 1.0 修正) | Phase 1.0 证伪发现"V3.2 漏 setBack"实际是 type mismatch：`PromptVersion.id`/`SkillDraftEntity.id` 是 String UUID，老 `candidatePromptVersionId/candidateSkillId` 是 Long，AttributionApprovalService L346-349 / L328-331 已有显式注释 "can't fit UUID into BIGINT — log link for now"。最小干预：加 2 个 nullable VARCHAR(36) 旁路列，旧 Long 列留空（UUID surface 表示），0 数据迁移 + 向后兼容。behavior_rule 已有 `candidate_behavior_rule_version_id VARCHAR(36)` (V83) 不动。|
| Phase 1.3/1.4 边界 (dispatch* 实现节奏) | **Phase 1.3 dispatch* 实现为 placeholder log，Phase 1.4 替换 log → service call + ephemeral fallback** (ratify #6，2026-05-16 Phase 1.3 mini-review 修正) | Phase 1.3 mini-review 抓到 dispatch* 直接调 service stub method (`PromptImproverService.runAbTestAgainst` / `SkillDraftService.startAbTestFromDraft`)，但 service stub 在 `evalScenarioIds=null` 时 throw → listener catch → broadcast ab_failed。attribution path 永远 null scenarios → Phase 1.6 dogfood 满屏假 ab_failed。修法：listener dispatch* 改成 placeholder log "PHASE 1.4 PENDING"，service stub method (signature + input validation) **保留**作为 endpoint signature lock。Phase 1.4 实施时只替换 dispatch* method body 内的 log call 为真 service call + ratify #4 ephemeral scenario fallback，0 接口变化 / 0 测试断签名变。|
| Phase 1.4 升 Full 档 + a/b 拆批 | **Phase 1.4a (本期已交付) ~40% + Phase 1.4b (后续) ~60%** (ratify #7，2026-05-16 dev NEEDS_CONTEXT 调研 + 主动拆批) | Phase 1.4 brief 1.5d 估算被 dev 调研证伪（AbEvalPipeline 紧绑 EvalTaskEntity / SkillDraftEntity 跟 SkillAbEvalService.createAndTrigger 不兼容 / Brief 假设 "compose existing" 无现成可用模板）。升 Full 档 + 5 design ratify (#7-A 加 AbEvalPipeline overload / #7-B SkillDraft 临时 promote / #7-C prompt baseline = active version / #7-D listener re-wire 真 service / #7-E ephemeral scenario fallback)。Phase 1.4a 真交付 PromptImproverService.runAbTestAgainst + listener re-wire + SkillDraftService.startAbTestFromDraft scaffold (synthetic abRunId 防 W1 flood)；Phase 1.4b 剩 AbEvalPipeline overload + promoteDraftToTransientSkill helper + 2 controllers + 6 endpoint tests + SkillDraftService 5 dep injection + 5 test 文件 constructor params。|
| SkillEntity transient identity 标识 | **`enabled=false` + dev 自选 metadata 字段 (非 `status` — SkillEntity 没此字段)** (ratify #7-B 修正，2026-05-16 Phase 1.4a 现场验证) | 原 ratify #7-B 写 `SkillEntity.status='ab_candidate'`，但 dev grep SkillEntity 0 hits 该字段（实际字段：`enabled / artifactStatus / rolloutStage`）。Phase 1.4b dev 自选最语义合适字段标识"A/B candidate 临时身份"：候选 (a) name 后缀 `_candidate_<uuid>` (已在 ratify #7-B 写) + (b) `artifactStatus="ab_candidate"` if VARCHAR 无 CHECK + (c) 加 `isTransientAbCandidate` boolean 字段 (要 V89 migration — 不推荐 ROI 不够)。胜出后 promote 路径复用 approveDraft 逻辑，败出后 delete by name 后缀 query。|

## 数据模型

### Migration V87 (disable metrics-collector cron)

```sql
-- V87__disable_canary_metrics_collector.sql

-- 2026-05-16: dogfood 单用户阶段暂时砍掉 canary 路径，metrics-collector
-- 跑出来的 t_canary_metric_snapshot 没人用。disable cron 防 LLM cost / DB 写
-- 浪费。t_canary_rollout / t_canary_metric_snapshot schema + V2 service code
-- 保留 dormant，未来加灰度时 UPDATE enabled=true 一行 reverse。

UPDATE t_scheduled_task SET enabled = false WHERE name = 'metrics-collector-hourly';
```

### Migration V88 (candidate_*_uuid 旁路列，ratify #5)

```sql
-- V88__add_candidate_uuid_sidecar_columns.sql

-- 2026-05-16: Phase 1.0 证伪发现 V3.2 "漏 setBack" 实际是 type mismatch
-- (PromptVersion.id/SkillDraftEntity.id 是 String UUID, 老 candidate_*_id 是 Long)。
-- 加 nullable VARCHAR(36) 旁路列，0 数据迁移 + 向后兼容。
-- UUID 路径写新列，旧 Long 列留 null 表示 UUID surface。
-- 老 candidate_*_id Long 列保留向后兼容（如未来 SkillDraft merge → SkillEntity 时仍 setBack BIGINT）。
-- behavior_rule 已有 VARCHAR(36) (V83) 不动。

ALTER TABLE t_optimization_event
    ADD COLUMN candidate_prompt_version_uuid VARCHAR(36) NULL,
    ADD COLUMN candidate_skill_draft_uuid    VARCHAR(36) NULL;
```

`OptimizationEventEntity` 加 2 字段：
```java
@Column(name = "candidate_prompt_version_uuid", length = 36)
private String candidatePromptVersionUuid;

@Column(name = "candidate_skill_draft_uuid", length = 36)
private String candidateSkillDraftUuid;
```

### Ephemeral EvalScenario (Fallback path)

ratify #4 fallback 路径生成的临时 EvalScenario，加新 status enum 值 'ephemeral':

```sql
-- 不需要 migration: t_eval_scenario.status 是 VARCHAR(32) 无 CHECK，可直接写 'ephemeral'
```

PromptImproverService.runAbTestAgainst 跑完后清理:
```java
finally {
    if (ephemeralScenarioIds != null) {
        scenarioRepository.deleteAllById(ephemeralScenarioIds);
    }
}
```

## 服务层设计

### 1. PromptImproverService.runAbTestAgainst (新)

```java
public AbRunStartResult runAbTestAgainst(
        String agentId,
        String baselineVersionId,    // default: current active
        String candidateVersionId,
        List<String> evalScenarioIds) {  // null/empty → fallback

    // 1. 拿 baseline (current active 或显式传入)
    PromptVersionEntity baseline = baselineVersionId != null
            ? promptVersionRepository.findById(baselineVersionId).orElseThrow()
            : promptVersionRepository.findActiveByAgentId(agentId).orElseThrow();
    PromptVersionEntity candidate = promptVersionRepository.findById(candidateVersionId).orElseThrow();

    // 2. 决定 scenarios
    List<EvalScenarioEntity> scenarios;
    List<String> ephemeralIds = null;
    if (evalScenarioIds != null && !evalScenarioIds.isEmpty()) {
        scenarios = scenarioRepository.findAllById(evalScenarioIds);
    } else {
        scenarios = scenarioRepository.findByAgentIdAndSplit(agentId, "held_out");
        if (scenarios.isEmpty()) {
            // Fallback (ratify #4): 从 pattern.members 抽 session 生临时 scenario
            scenarios = createEphemeralScenariosFromPatternMembers(candidate, 3);
            ephemeralIds = scenarios.stream().map(EvalScenarioEntity::getId).toList();
        }
    }

    // 3. 创建 PromptAbRunEntity + 异步 fan-out (复用现有 runImprovementAsync 模板)
    String abRunId = startAbRunAsync(agentId, baseline, candidate, scenarios, ephemeralIds);

    return new AbRunStartResult(abRunId, scenarios.size(), ephemeralIds != null);
}

// Fallback: 找 pattern.members 抽 N session → SessionScenarioExtractorService 生 临时 scenario
private List<EvalScenarioEntity> createEphemeralScenariosFromPatternMembers(
        PromptVersionEntity candidate, int n) {
    // 1. find optimization_event linked to this candidate
    OptimizationEventEntity event = optimizationEventRepository.findByCandidatePromptVersionUuid(candidate.getId());  // V88 加 UUID 旁路列 query
    if (event == null || event.getPatternId() == null) {
        throw new IllegalStateException("No EvalScenario for agent + no pattern linkage; cannot run A/B");
    }
    // 2. 拿 pattern.members N sessions
    List<String> sessionIds = patternSessionMemberRepository
            .findByPatternIdOrderByCreatedAt(event.getPatternId(), PageRequest.of(0, n))
            .stream().map(PatternSessionMemberEntity::getSessionId).toList();
    // 3. extractFromSession each + status='ephemeral'
    List<EvalScenarioEntity> ephemeral = new ArrayList<>();
    for (String sid : sessionIds) {
        SessionEntity session = sessionService.getSession(sid);
        EvalScenarioEntity scenario = scenarioExtractor.extractFromSession(session);  // 复数版的单数 helper
        scenario.setStatus("ephemeral");
        ephemeral.add(scenarioRepository.save(scenario));
    }
    return ephemeral;
}
```

### 2. SkillDraftService.createDraftFromAttribution 加 sync LLM fill

```java
// 复用 SkillDraftService 现有常量 (L65-66 已定义 by extractFromRecentSessions)
// private static final String EXTRACT_PROVIDER_NAME = "xiaomi-mimo";   // 已存在
// private static final String EXTRACT_MODEL = "mimo-v2.5-pro";          // 已存在
// LlmProviderFactory llmProviderFactory                                  // 已注入 (L75)

@Transactional(propagation = Propagation.REQUIRES_NEW)  // 防外层 rollback-only 污染 (现已有 L355)
public SkillDraftEntity createDraftFromAttribution(Long eventId, Long patternId,
                                                    String attributedDescription,
                                                    String expectedImpact,
                                                    String changeType,
                                                    Long ownerId,
                                                    String suggestedSkillName) {
    // ... 现有 validation + 构造 SkillDraftEntity ...

    try {
        // V6 新加: sync LLM fill (跟 V3.1 PromptImproverService.generateCandidatePromptFromAttribution 同款 pattern)
        SkillContentResult result = generateCandidateSkillMdFromAttribution(attributedDescription, expectedImpact);
        draft.setTriggers(result.triggers());
        draft.setRequiredTools(result.requiredTools());
        draft.setPromptHint(result.skillMdBody());  // inline 存 SKILL.md body (SkillDraftEntity 无 skill_path 列,
                                                    // 待 approveDraft 时 skillStorageService.allocate() 分配 path)
    } catch (RuntimeException llmEx) {
        // Audit-trail rethrow (跟 V3.1 同款): save with empty content + log + rethrow
        draft.setTriggers("");
        draft.setRequiredTools("");
        draft.setPromptHint("");
        skillDraftRepository.save(draft);
        log.error("Attribution skill-draft LLM fill FAILED: draftId={} eventId={} patternId={}: {}",
                draft.getId(), eventId, patternId, llmEx.getMessage());
        throw llmEx;  // 外层 AttributionApproval catch → stage=candidate_failed
    }

    return skillDraftRepository.save(draft);
}

// 跟 SkillDraftService.extractFromRecentSessions (L170-193) 同款 provider 选择 + LLM 调用模板
private SkillContentResult generateCandidateSkillMdFromAttribution(String attributedDescription, String expectedImpact) {
    LlmProvider provider = llmProviderFactory.getProvider(EXTRACT_PROVIDER_NAME);
    if (provider == null) {
        log.warn("Preferred provider {} unavailable, falling back to default {}", EXTRACT_PROVIDER_NAME, defaultProviderName);
        provider = llmProviderFactory.getProvider(defaultProviderName);
    }
    String prompt = buildSkillMdGenPrompt(attributedDescription, expectedImpact);
    LlmRequest req = LlmRequest.builder()
            .model(EXTRACT_MODEL)
            .messages(List.of(Message.user(prompt)))
            .maxTokens(4000)  // 跟 extractFromRecentSessions 同款 mimo-v2.5-pro reasoning 防吃光
            .temperature(0.3)
            .build();
    LlmResponse resp = provider.chat(req);
    return parseSkillMdOutput(resp.getContent());  // 期望 frontmatter 格式
}

// SkillContentResult record (3 字段 — 不含 skillPath 因 SkillDraftEntity schema 无此列；
// approveDraft → SkillEntity 时由 skillStorageService.allocate() 分配 path):
//   public record SkillContentResult(String triggers, String requiredTools, String skillMdBody) {}

// LLM 输出 prompt 例:
// ```
// 你是一个 SkillForge skill 优化器。基于以下 attribution 反馈，生成新 SKILL.md。
//
// Attribution 反馈:
// {attributedDescription}
//
// 期望影响:
// {expectedImpact}
//
// 输出严格 markdown frontmatter 格式 (不输 skill_path —— 由 approveDraft 阶段分配):
// ---
// triggers: [<trigger1>, <trigger2>]
// required_tools: [<tool1>, <tool2>]
// ---
// <实际 SKILL.md body 内容>
// ```
```

> **2026-05-16 Phase 1.1 Concern 5 ratify**：`SkillDraftEntity` 无 `skill_path` 列（dev 现场验证 0 hits）。SkillContentResult 改 3 字段 (triggers / requiredTools / skillMdBody)，LLM prompt 不再要求 `skill_path:` 行。skill_path 仍在 SkillEntity 上由 `approveDraft → skillStorageService.allocate()` 分配。**不需要 V89 加列**。
>
> **2026-05-16 Phase 1.1 C2 fix ratify (C2 解决)**：删 `generateCandidateSkillMdFromAttribution` 的 `SkillEntity parent` 第 1 参 + LLM prompt builder 内 "improve existing skill" if-branch + `createDraftFromAttribution` 内 `parentSkill` lookup（findFirstByOwnerIdAndNameAndEnabledTrue）— attribution 合成名永不匹配 = 真死代码。未来真做 "improve existing skill" 路径时干净加回来比保留无意义死分支清晰。

### 3. OptimizationEventStageChangeEvent + Listener

```java
// 新建 event class
public record OptimizationEventStageChangeEvent(
    Long eventId,
    String fromStage,
    String toStage,
    String surfaceType,
    Long agentId,
    Long patternId,
    String candidatePromptVersionUuid,  // V88 旁路列 (String UUID, ratify #5)
    String candidateSkillDraftUuid,     // V88 旁路列 (String UUID, ratify #5)
    String candidateBehaviorRuleVersionId
) {}

// 现有 AttributionApprovalService.runCandidateGeneration 内每次 stage 变 fire
applicationEventPublisher.publishEvent(new OptimizationEventStageChangeEvent(
    event.getId(), fromStage, toStage,
    event.getSurfaceType(), event.getAgentId(), event.getPatternId(),
    event.getCandidatePromptVersionId(),
    event.getCandidateSkillId(),
    event.getCandidateBehaviorRuleVersionId()
));

// 新建 listener
@Component
public class OptimizationEventAutoTriggerListener {

    @Async("abEvalLoopExecutor")
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStageCandidateReady(OptimizationEventStageChangeEvent event) {
        if (!"candidate_ready".equals(event.toStage())) return;
        if (!properties.isAutoTriggerAbOnCandidateReady()) return;

        switch (event.surfaceType()) {
            case "prompt":
                if (event.candidatePromptVersionUuid() == null) {
                    log.warn("candidate_ready event {} has no candidatePromptVersionUuid, skip auto A/B",
                            event.eventId());
                    return;
                }
                promptImproverService.runAbTestAgainst(
                        String.valueOf(event.agentId()),
                        null,  // baseline = current active
                        event.candidatePromptVersionUuid(),
                        null   // scenarios = agent default or fallback
                );
                break;
            case "skill":
                // similar
                break;
            case "behavior_rule":
                // V5.1 backlog (V4 不支持 dynamic candidate)
                log.info("behavior_rule auto-AB not supported (V5.1 backlog); skip eventId={}", event.eventId());
                break;
        }
    }
}
```

### 4. ALLOWED_TRANSITIONS 加 ab_passed → promoted 直接边

```java
// AttributionApprovalService.java
private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.ofEntries(
    // ... 现有边 ...
    Map.entry(STAGE_AB_PASSED, Set.of(
        STAGE_CANARY_STARTED,      // 老边: 多用户阶段 canary 路径 (保留)
        STAGE_PROMOTED             // 新边: dogfood 单用户阶段直接 published
    )),
    // ...
);
```

### 5. FE canary disable

**SkillAbPanel.tsx**:
```tsx
// 删 line 489-502 周围的 <CanaryPanel> embed 整段
```

**Insights.tsx**:
```tsx
// INSIGHTS_TABS 删 'canary' tab (如果有)
```

**BehaviorRuleEvolutionPanel.tsx**:
```tsx
// 删 <CanaryPanel embedSurface="behavior_rule" />
```

保留 api/canary.ts (未来加回时 import 复用)。

### 6. V3.2 link 接通 (用 V88 加的 UUID 旁路列)

```java
// AttributionApprovalService.runCandidateGeneration() 改:
case SURFACE_PROMPT -> {
    ImprovementStartResult result = dispatchPromptSurface(event, approverUserId);
    // V88 新加 candidate_prompt_version_uuid VARCHAR(36) 旁路列接 PromptVersion.id (String UUID)
    event.setCandidatePromptVersionUuid(result.promptVersionId());
    // 老 candidatePromptVersionId Long 列留 null (UUID surface 表示)
}
case SURFACE_SKILL -> {
    SkillDraftEntity draft = dispatchSkillSurface(event, approverUserId);
    // V88 新加 candidate_skill_draft_uuid VARCHAR(36) 旁路列接 SkillDraftEntity.id (String UUID)
    event.setCandidateSkillDraftUuid(draft.getId());
    // 老 candidateSkillId Long 列留 null；未来 SkillDraft merge → SkillEntity (BIGINT PK) 时
    // 再 setCandidateSkillId Long 表示已 merged surface
}
case SURFACE_BEHAVIOR_RULE -> {
    String versionId = dispatchBehaviorRuleSurface(event, approverUserId);
    event.setCandidateBehaviorRuleVersionId(versionId);  // VARCHAR(36) 早已就绪 (V83)
}
```

**Listener / Endpoint 读侧**: `OptimizationEventStageChangeEvent` record 字段全用 String UUID (`candidatePromptVersionUuid / candidateSkillDraftUuid`)。`OptimizationEventAutoTriggerListener` 优先读 UUID 旁路列，Long 列作未来 merged surface 信号。

## 实施计划

### Phase 1.0 — 证伪 + 红测试 (0.5 天)

- grep V3.1 PromptImproverService.startImprovementFromAttribution 完整 pattern (复用 template)
- 红测试: approve event id=31 → A/B 应该自动跑但现状不跑 (锁现状)

### Phase 1.1 — SkillDraft sync LLM fill (1 天)

- SkillDraftService 加 EXTRACT_PROVIDER_NAME / EXTRACT_MODEL 常量
- 加 generateCandidateSkillMdFromAttribution + parseSkillMdOutput
- 改 createDraftFromAttribution 加 sync LLM fill + audit-trail rethrow
- Test: SkillDraftServiceAttributionTest 加 LLM fill happy / fail audit-trail / parse output 3 case

### Phase 1.2 — V3.2 link bug fix + ALLOWED_TRANSITIONS 加边 (0.5 天)

- AttributionApprovalService.runCandidateGeneration 加 setCandidatePromptVersionUuid / setCandidateSkillDraftUuid (V88 旁路列) + behavior_rule 已有逻辑保留
- ALLOWED_TRANSITIONS 加 ab_passed → promoted 直接边
- Test: AttributionApprovalServiceTest 加 link 写回 regression case

### Phase 1.3 — OptimizationEventStageChangeEvent + Listener (1 天)

- 新建 OptimizationEventStageChangeEvent record + Publisher (inject ApplicationEventPublisher to AttributionApprovalService)
- 现有 stage 变更点 fire event (4 处 stage transition)
- 新建 OptimizationEventAutoTriggerListener @Async @EventListener
- Properties config `flywheel.auto-trigger-ab-on-candidate-ready` default true
- Test: OptimizationEventAutoTriggerListenerTest mock fire event 验 prompt/skill/behavior_rule 分支

### Phase 1.4 — /run-ab endpoint + fallback (1.5 天)

- PromptImproveController 加 `POST /api/agents/{id}/prompt-versions/{versionId}/run-ab`
- PromptImproverService.runAbTestAgainst (composition of existing internals)
- createEphemeralScenariosFromPatternMembers fallback
- SkillDraftController 加 `POST /api/skills/{id}/abtest-from-draft` (or extend existing)
- finally cleanup ephemeral scenarios
- Test: PromptImproveControllerTest + SkillDraftControllerTest /run-ab happy / 404 / fallback case

### Phase 1.5 — FE canary disable + V87 cron disable (0.5 天)

- 删 SkillAbPanel CanaryPanel embed
- 删 BehaviorRuleEvolutionPanel CanaryPanel embed
- 删 Insights canary tab (如有)
- V87 migration: UPDATE t_scheduled_task disable metrics-collector cron
- Test: FE SkillAbPanel.test "no canary panel" assertion / tsc + npm build

### Phase 1.6 — e2e dogfood verify (0.5-1 天)

- 启 server / 重启 FE
- approve event id=9 / 16 (prompt surface pending) → 自动跑到 promoted
- approve event id=12 (skill surface, reuse) → SkillDraft 真填 + 自动 A/B + auto-promote
- 验 system agent agent_id=9 attribution-curator 自改进路径用 pattern.members 生 临时 scenario 跑通
- 验 dashboard 不再显示 canary 任何 UI
- 验 t_canary_rollout 表存在但 0 new row

### Phase Final — 归档 (0.5 天)

- requirements active → archive (2026-05-1?-FLYWHEEL-LOOP-CLOSURE)
- delivery-index.md + README/todo.md 同步

## 风险与边界

### Mid Risk
- **ephemeral scenario LLM cost**：fallback 抽 3 session × extractFromSession 1 LLM call/session = 3 extra LLM calls per A/B run (system agent 路径)。dogfood 期可控
- **markdown frontmatter parsing**：LLM 输出 frontmatter 格式不稳；加 robust parser + 解析失败 fallback content="" + audit-trail rethrow
- **canary FE delete 漏处**：grep 仔细看现有 CanaryPanel 引用位置

### Low Risk
- ALLOWED_TRANSITIONS 加边 (旧边保留向后兼容)
- V87 migration 单 UPDATE 不破任何数据
- @EventListener async @REQUIRES_NEW 标准 pattern

## Iron Law 全程守住

- 核心 7+1 BE 文件 + 核心 3 FE 文件 git diff = 0
- 不动 V4 OptimizableSurface / AbstractAbEvalRunner 接口
- 不动 V5 SkillAbEvalService.runAbTest 核心逻辑 (复用)
- 不动 V2 CanaryRolloutService / CanaryAllocator code (dormant)
- persistence-shape-invariant + identity-column-on-rewrite 不触发

## 测试计划

- BE: mvn test → 预期 1712 + ~15 new = ~1727 BUILD SUCCESS
- FE: tsc + npm build EXIT=0
- 核心 7+1 + 3 FE 文件 0 diff
- Dogfood e2e: 真启 server + 真 approve + 真自动跑到 promoted (无 manual A/B button click)

## 评审记录

- 2026-05-16 创建 design-draft (基于 2026-05-16 dogfood 手动测试暴露 4 缺口 + user 决定 canary 暂时砍掉)
- 4 ratify 决策已 2026-05-16 与 user 锁定 (canary logic disable / SkillDraft fill 跟 V3.1 / @EventListener / system agent 同期做)
