package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.eval.attribution.FailureAttribution;
import com.skillforge.server.improve.surface.PromptSurface;
import com.skillforge.server.improve.surface.SandboxContext;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.memory.context.MemoryContextSnapshot;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.2 — refactored to extend
 * {@link AbstractAbEvalRunner} of {@link PromptVersionEntity}. The public API
 * (startImprovement / startImprovementFromAttribution) is preserved bit-for-bit;
 * only the {@link #runImprovementAsync} internal body now delegates the A/B
 * eval + evaluateAndPromote sequence to {@code AbstractAbEvalRunner.run()},
 * which calls the 5 hooks implemented at the bottom of this file
 * (runEvalSet / judgeAndCompare / shouldPromote / promoteIfNeeded plus inject
 * via {@link PromptSurface}).
 *
 * <p>Behavior contract: identical to pre-Phase 1.2 — V3.1 synchronous LLM
 * fill on attribution path (REQUIRES_NEW + audit-trail rethrow) is preserved
 * unchanged; {@link AbEvalPipeline#run} is still the candidate-side eval
 * mechanism (the candidate-side {@code runEvalSet} hook delegates to it,
 * so abEvalPipeline.run still writes abRun fields exactly as before);
 * {@link PromptPromotionService#evaluateAndPromote} still owns the real
 * gate logic (delta threshold + 24h cooldown + decline tracking + paused
 * flag). The refactor is structural only; the existing
 * {@code PromptImproverServiceTest} + {@code PromptImproverServiceAttributionTest}
 * compile + pass with a mock {@link PromptSurface}.
 */
@Service
public class PromptImproverService extends AbstractAbEvalRunner<PromptVersionEntity> {

    private static final Logger log = LoggerFactory.getLogger(PromptImproverService.class);

    private static final Set<FailureAttribution> ELIGIBLE = Set.of(
            FailureAttribution.PROMPT_QUALITY, FailureAttribution.CONTEXT_OVERFLOW);

    private final AgentRepository agentRepository;
    private final EvalTaskRepository evalRunRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final PromptAbRunRepository promptAbRunRepository;
    private final AbEvalPipeline abEvalPipeline;
    private final PromptPromotionService promotionService;
    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;
    private final ExecutorService coordinatorExecutor;
    private final String defaultProviderName;

    /**
     * Phase 1.2 — per-run ephemeral state shared between
     * {@link #runImprovementAsync} (orchestrator) and the 5 hooks called via
     * {@link AbstractAbEvalRunner#run}. ThreadLocal because the service is a
     * @Service singleton but coordinatorExecutor may invoke
     * {@link #runImprovementAsync} concurrently on different threads.
     */
    private final ThreadLocal<PromptRunState> currentRun = new ThreadLocal<>();

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4: ephemeral-fallback dependencies used
     * only by {@link #runAbTestAgainst}. Nullable so existing tests that
     * don't exercise the auto-trigger path can pass {@code null} for these
     * five constructor params (Mockito @Mock supplies them in attribution
     * tests). Wired to non-null in production via Spring DI.
     */
    private final com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioRepository;
    private final com.skillforge.server.repository.OptimizationEventRepository optimizationEventRepository;
    private final com.skillforge.server.repository.PatternSessionMemberRepository patternSessionMemberRepository;
    private final com.skillforge.server.repository.SessionRepository sessionRepository;
    private final SessionScenarioExtractorService sessionScenarioExtractor;
    // W5 fix (Phase 1.4d, 2026-05-17) — separate bean lets @Transactional
    // (REQUIRES_NEW) actually take effect for ephemeral cleanup that runs in
    // async coordinatorExecutor finally (publisher tx is already committed).
    private final EphemeralScenarioCleanupService ephemeralScenarioCleanupService;
    // EVAL-DATASET-LAYER V1 (V109): optional collaborator for the new
    // dataset-version path. Nullable so existing Mockito tests with the
    // 17-arg ctor continue to compile; in production Spring DI calls the
    // newest @Autowired ctor below.
    private final com.skillforge.server.service.EvalDatasetService evalDatasetService;
    private final MemoryContextProvider memoryContextProvider;

    public PromptImproverService(AgentRepository agentRepository,
                                  EvalTaskRepository evalRunRepository,
                                  PromptVersionRepository promptVersionRepository,
                                  PromptAbRunRepository promptAbRunRepository,
                                  AbEvalPipeline abEvalPipeline,
                                  PromptPromotionService promotionService,
                                  LlmProviderFactory llmProviderFactory,
                                  ObjectMapper objectMapper,
                                  @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor,
                                  LlmProperties llmProperties,
                                  @Lazy PromptSurface promptSurface,
                                  PromptEvalService promptEvalService,
                                  com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioRepository,
                                  com.skillforge.server.repository.OptimizationEventRepository optimizationEventRepository,
                                  com.skillforge.server.repository.PatternSessionMemberRepository patternSessionMemberRepository,
                                  com.skillforge.server.repository.SessionRepository sessionRepository,
                                  SessionScenarioExtractorService sessionScenarioExtractor,
                                  EphemeralScenarioCleanupService ephemeralScenarioCleanupService) {
        this(agentRepository, evalRunRepository, promptVersionRepository, promptAbRunRepository,
             abEvalPipeline, promotionService, llmProviderFactory, objectMapper, coordinatorExecutor,
             llmProperties, promptSurface, promptEvalService, evalScenarioRepository,
             optimizationEventRepository, patternSessionMemberRepository, sessionRepository,
             sessionScenarioExtractor, ephemeralScenarioCleanupService, null, null);
    }

    /**
     * EVAL-DATASET-LAYER V1: full Spring-DI constructor. The 17-arg ctor above
     * delegates here with {@code evalDatasetService=null}, preserved for existing
     * unit-test wiring.
     */
    public PromptImproverService(AgentRepository agentRepository,
                                  EvalTaskRepository evalRunRepository,
                                  PromptVersionRepository promptVersionRepository,
                                  PromptAbRunRepository promptAbRunRepository,
                                  AbEvalPipeline abEvalPipeline,
                                  PromptPromotionService promotionService,
                                  LlmProviderFactory llmProviderFactory,
                                  ObjectMapper objectMapper,
                                  @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor,
                                  LlmProperties llmProperties,
                                  @Lazy PromptSurface promptSurface,
                                  PromptEvalService promptEvalService,
                                  com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioRepository,
                                  com.skillforge.server.repository.OptimizationEventRepository optimizationEventRepository,
                                  com.skillforge.server.repository.PatternSessionMemberRepository patternSessionMemberRepository,
                                  com.skillforge.server.repository.SessionRepository sessionRepository,
                                  SessionScenarioExtractorService sessionScenarioExtractor,
                                  EphemeralScenarioCleanupService ephemeralScenarioCleanupService,
                                  com.skillforge.server.service.EvalDatasetService evalDatasetService) {
        this(agentRepository, evalRunRepository, promptVersionRepository, promptAbRunRepository,
                abEvalPipeline, promotionService, llmProviderFactory, objectMapper, coordinatorExecutor,
                llmProperties, promptSurface, promptEvalService, evalScenarioRepository,
                optimizationEventRepository, patternSessionMemberRepository, sessionRepository,
                sessionScenarioExtractor, ephemeralScenarioCleanupService, evalDatasetService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PromptImproverService(AgentRepository agentRepository,
                                  EvalTaskRepository evalRunRepository,
                                  PromptVersionRepository promptVersionRepository,
                                  PromptAbRunRepository promptAbRunRepository,
                                  AbEvalPipeline abEvalPipeline,
                                  PromptPromotionService promotionService,
                                  LlmProviderFactory llmProviderFactory,
                                  ObjectMapper objectMapper,
                                  @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor,
                                  LlmProperties llmProperties,
                                  @Lazy PromptSurface promptSurface,
                                  PromptEvalService promptEvalService,
                                  com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioRepository,
                                  com.skillforge.server.repository.OptimizationEventRepository optimizationEventRepository,
                                  com.skillforge.server.repository.PatternSessionMemberRepository patternSessionMemberRepository,
                                  com.skillforge.server.repository.SessionRepository sessionRepository,
                                  SessionScenarioExtractorService sessionScenarioExtractor,
                                  EphemeralScenarioCleanupService ephemeralScenarioCleanupService,
                                  com.skillforge.server.service.EvalDatasetService evalDatasetService,
                                  MemoryContextProvider memoryContextProvider) {
        // @Lazy on promptSurface breaks the DI cycle: PromptSurface's @Lazy
        // injection of PromptImproverService bootstrap order. Super constructor
        // only stores the reference (no method call), so proxy is safe.
        //
        // Phase 1.2 reviewer-r1 fix: promptEvalService is the
        // EvalService<PromptVersionEntity> adapter that
        // AbstractAbEvalRunner.run() delegates to via runEvalSet (preserves
        // ratify #3 4-hook count). Not @Lazy because the reverse @Lazy
        // (PromptEvalService → PromptImproverService) breaks the cycle from
        // the other side.
        super(promptSurface, promptEvalService);
        this.agentRepository = agentRepository;
        this.evalRunRepository = evalRunRepository;
        this.promptVersionRepository = promptVersionRepository;
        this.promptAbRunRepository = promptAbRunRepository;
        this.abEvalPipeline = abEvalPipeline;
        this.promotionService = promotionService;
        this.llmProviderFactory = llmProviderFactory;
        this.objectMapper = objectMapper;
        this.coordinatorExecutor = coordinatorExecutor;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        this.evalScenarioRepository = evalScenarioRepository;
        this.optimizationEventRepository = optimizationEventRepository;
        this.patternSessionMemberRepository = patternSessionMemberRepository;
        this.sessionRepository = sessionRepository;
        this.sessionScenarioExtractor = sessionScenarioExtractor;
        this.ephemeralScenarioCleanupService = ephemeralScenarioCleanupService;
        this.evalDatasetService = evalDatasetService;
        this.memoryContextProvider = memoryContextProvider;
    }

    @Transactional
    public ImprovementStartResult startImprovement(String agentId, String evalRunId, long userId) {
        return startImprovement(agentId, evalRunId, userId, null);
    }

    /**
     * V3 ATTRIBUTION-AGENT Phase 1.3 — attribution-aware improvement entry.
     *
     * <p>Called by {@code AttributionApprovalService.approve} when a curator's
     * proposal targets {@code surface=prompt}.
     *
     * <p>Per ratify decision (2026-05-15): this path BYPASSES
     * {@link #checkEligibility} entirely — V3 enforces its own 24h pattern-level
     * cooldown via {@code t_optimization_event.cooldown_expires_at}, written by
     * {@code ProposeOptimizationTool}. Layering the existing
     * {@code agent.lastPromotedAt} 24h cooldown on top would double-gate (and
     * confuse the operator about which window is in effect). Risk gating
     * (low/medium/high → maybe future auto-reject of high) is reserved for
     * Phase 2; for now all approved attribution proposals proceed.
     *
     * <p>Existing {@link #startImprovement} signatures unchanged — this is a
     * new entry point.
     *
     * <p>Phase 1.3 scope: produces a {@link PromptVersionEntity} placeholder
     * (status=candidate, source="attribution", improvementRationale set,
     * sourceEvalRunId=null, baselinePassRate=null, content empty). Does NOT
     * create a {@link PromptAbRunEntity} — there's no eval run to anchor an A/B
     * baseline against. Phase 1.4+ wires the actual LLM candidate generation +
     * A/B trigger; for Phase 1.3 the version row exists so
     * {@code OptimizationEvent.candidatePromptVersionId} can link to it and
     * downstream timeline queries work.
     *
     * @param eventId               originating optimization event id (logged for audit)
     * @param agentId               target agent id (string per existing column type)
     * @param attributedDescription curator's change description (stored as
     *                              {@link PromptVersionEntity#getImprovementRationale()})
     * @param ownerId               approver user id (logged for audit)
     */
    /*
     * REQUIRES_NEW (Phase 1.3 reviewer fix — V2 W2 same lesson):
     * AttributionApprovalService.approve runs in @Transactional(REQUIRED) and
     * catches RuntimeException from this method to persist
     * stage=candidate_failed. If we JOIN that outer tx (default REQUIRED),
     * any prompt-version-write failure would mark the outer tx
     * setRollbackOnly → approve's candidate_failed save() would commit but
     * the surrounding tx still rolls back → operator never sees the failure.
     * REQUIRES_NEW gives this method an independent tx that commits or
     * rolls back without touching the outer one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImprovementStartResult startImprovementFromAttribution(Long eventId,
                                                                  String agentId,
                                                                  String attributedDescription,
                                                                  Long ownerId) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (attributedDescription == null || attributedDescription.isBlank()) {
            throw new IllegalArgumentException("attributedDescription is required and must be non-blank");
        }

        // Verify the agent exists; load fully so future Phase 1.4 LLM call has
        // currentPrompt available (we don't use it in 1.3 placeholder write,
        // but the lookup proves the FK is valid up front).
        //
        // PROMPT-IMPROVER-GENESIS-BASELINE fix (2026-05-23): switched
        // findById → findByIdForUpdate (PESSIMISTIC_WRITE) so concurrent
        // attribution-triggered approvals on the same agent serialize on the
        // agent row. Without the lock two near-simultaneous callers could both
        // observe "no prompt versions yet", both try to write v1 baseline, and
        // the second hits the uq_agent_version UNIQUE constraint (mirrors the
        // same lock pattern already in runAbTestAgainst).
        AgentEntity agent = agentRepository.findByIdForUpdate(Long.parseLong(agentId))
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

        // PROMPT-IMPROVER-GENESIS-BASELINE fix (2026-05-23): materialize v1
        // baseline FIRST when the agent has no prompt versions yet. Old logic
        // wrote candidate as v1 directly, so when the V6 listener later kicked
        // runAbTestAgainst the baseline-genesis path tried to also claim v1 →
        // uq_agent_version (agent_id, version_number) UNIQUE conflict. Correct
        // shape: v1=active genesis_baseline (mirrors current agent.system_prompt)
        // + v2=candidate (LLM-generated from attribution), so the downstream
        // A/B has a real baseline already present without re-fighting the
        // UNIQUE index. V106 backfills v1 baseline for any existing agent that
        // still lacks one.
        List<PromptVersionEntity> existingVersions =
                promptVersionRepository.findByAgentIdOrderByVersionNumberDesc(agentId);
        int nextVersion;
        if (existingVersions.isEmpty()) {
            String currentPrompt = agent.getSystemPrompt();
            if (currentPrompt == null || currentPrompt.isBlank()) {
                // Caller (AttributionApprovalService) catches RuntimeException
                // and writes stage=candidate_failed. IllegalStateException is
                // a RuntimeException, so the same audit path applies.
                throw new IllegalStateException("Agent " + agentId
                        + " has empty system_prompt — cannot materialize v1 baseline "
                        + "for genesis attribution path");
            }
            PromptVersionEntity baseline = new PromptVersionEntity();
            baseline.setId(UUID.randomUUID().toString());
            baseline.setAgentId(agentId);
            baseline.setVersionNumber(1);
            baseline.setStatus("active");
            baseline.setSource("genesis_baseline");
            baseline.setContent(currentPrompt);
            // createdAt left null — @CreatedDate auditing listener populates
            // on save; explicit set would race the listener.
            promptVersionRepository.save(baseline);
            agent.setActivePromptVersionId(baseline.getId());
            agentRepository.save(agent);
            log.info("[V6-FIX] Materialized v1 genesis_baseline for agentId={} "
                            + "(versionId={}, {} chars) before writing attribution candidate",
                    agentId, baseline.getId(), currentPrompt.length());
            nextVersion = 2;
        } else {
            nextVersion = existingVersions.get(0).getVersionNumber() + 1;
        }

        PromptVersionEntity version = new PromptVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setAgentId(agentId);
        version.setVersionNumber(nextVersion);
        version.setStatus("candidate");
        // "attribution" source distinguishes these versions from "auto_improve"
        // (eval-driven) and "manual" (operator-edited) in dashboard listings.
        version.setSource("attribution");
        // sourceEvalRunId / baselinePassRate intentionally null — there's no
        // eval-run baseline to compare against. The A/B at V3.2 will set its
        // own baseline by reading the agent's current production prompt.
        version.setImprovementRationale(attributedDescription.trim());

        // V3.1 (2026-05-15): synchronous LLM fill. Phase 1.3 originally wrote
        // content="" placeholder and deferred the actual generation to "Phase 1.4+
        // async", but Phase 1.4 ended up not wiring it (dogfood found: empty
        // candidate cannot be A/B'd, full breaker for the prompt-surface arm of
        // the flywheel). Generate inline here so candidate_ready truly means
        // "candidate has content + ready for A/B". On LLM failure the caller
        // (AttributionApprovalService) catches and writes stage=candidate_failed.
        try {
            String improved = generateCandidatePromptFromAttribution(agent, attributedDescription);
            version.setContent(improved);
        } catch (RuntimeException llmEx) {
            // Preserve audit trail of attempt: save row with content="" + log,
            // then rethrow so the outer approve() catches and writes
            // candidate_failed. Without saving the version row first we'd lose
            // the audit-trail eventId link.
            version.setContent("");
            promptVersionRepository.save(version);
            log.error("Attribution prompt-version LLM fill FAILED: versionId={} agentId={} eventId={}: {}",
                    version.getId(), agentId, eventId, llmEx.getMessage());
            throw llmEx;
        }

        promptVersionRepository.save(version);

        log.info("Attribution-derived prompt version created: versionId={} agentId={} eventId={} "
                        + "ownerId={} versionNumber={} contentLen={} (BYPASSING checkEligibility per ratify)",
                version.getId(), agentId, eventId, ownerId, nextVersion,
                version.getContent().length());

        // abRunId left null — caller (AttributionApprovalService) doesn't yet
        // create a PromptAbRunEntity; Phase 1.4+ wires that. agent.getId() is
        // returned as a string to match existing ImprovementStartResult shape.
        return new ImprovementStartResult(
                String.valueOf(agent.getId()),
                null,
                version.getId(),
                "PENDING");
    }

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.3 (2026-05-16) — called by
     * {@link com.skillforge.server.attribution.OptimizationEventAutoTriggerListener}
     * when an attribution event reaches {@code candidate_ready}. Runs an A/B
     * comparison between {@code baselineVersionId} (or the agent's current
     * active prompt when null) and {@code candidateVersionId}, using the
     * supplied {@code evalScenarioIds} (or the agent's held-out scenarios when
     * null).
     *
     * <p><b>Phase 1.3 scope</b>: signature + input validation + empty-scenarios
     * guard. The actual async fan-out (PromptAbRunEntity creation +
     * coordinator dispatch + baseline/candidate eval execution) is Phase 1.4's
     * job — Phase 1.4 will replace the {@link UnsupportedOperationException}
     * body with the real composition of {@code runImprovementAsync} internals
     * (per tech-design §服务层设计 #1). Throwing here means the listener's
     * catch path triggers an {@code ab_failed} WS broadcast, so dogfood can
     * observe the wiring before the Phase 1.4 implementation lands.
     *
     * @param agentId            agent ID (String form; tech-design §1 signature)
     * @param baselineVersionId  baseline prompt version UUID; {@code null} →
     *                           use the agent's current active prompt
     * @param candidateVersionId candidate prompt version UUID (required)
     * @param evalScenarioIds    explicit scenario IDs; {@code null} or empty →
     *                           use the agent's held-out scenario set. When
     *                           held-out is also empty, throws
     *                           {@link IllegalStateException} (Phase 1.4
     *                           ratify #4 will add the ephemeral fallback
     *                           from {@code pattern.members}).
     * @return the newly-created {@code PromptAbRunEntity.id}
     * @throws IllegalArgumentException if {@code candidateVersionId} is null
     *                                  or the candidate / baseline version
     *                                  cannot be found
     * @throws IllegalStateException    if no EvalScenarios are available for
     *                                  the agent (Phase 1.4 ephemeral
     *                                  fallback not yet implemented)
     * @throws UnsupportedOperationException Phase 1.3 stub; Phase 1.4 wires
     *                                       the async fan-out body
     */
    /**
     * EVAL-DATASET-LAYER V1 (★ r4 D3 fix ★): new param-object entry point that
     * additionally accepts an immutable {@code datasetVersionId}. Mutually
     * exclusive with {@code evalScenarioIds} (the record's compact constructor
     * enforces).
     *
     * <p>This method is the V1 forward-facing API; the legacy 4-arg overload
     * below is marked {@link Deprecated} and delegates here.
     */
    @Transactional
    public String runAbTestAgainst(AbEvalRunRequest req) {
        Objects.requireNonNull(req, "AbEvalRunRequest required");
        return runAbTestAgainstInternal(
                req.agentId(),
                req.baselineVersionId(),
                req.candidateVersionId(),
                req.evalScenarioIds(),
                req.datasetVersionId());
    }

    /**
     * Legacy 4-arg API. ★ r4 D2 fix ★: {@link Deprecated} {@code forRemoval}
     * so callers migrate to the {@link AbEvalRunRequest} record. Delegates
     * to the internal implementation with {@code datasetVersionId=null}.
     */
    @Deprecated(forRemoval = true, since = "EVAL-DATASET-LAYER V1")
    @Transactional
    public String runAbTestAgainst(String agentId,
                                   String baselineVersionId,
                                   String candidateVersionId,
                                   List<String> evalScenarioIds) {
        log.warn("PromptImproverService.runAbTestAgainst(4-arg) legacy overload invoked — "
                + "migrate to runAbTestAgainst(AbEvalRunRequest). agentId={}", agentId);
        return runAbTestAgainstInternal(agentId, baselineVersionId, candidateVersionId,
                evalScenarioIds, null);
    }

    private String runAbTestAgainstInternal(String agentId,
                                             String baselineVersionId,
                                             String candidateVersionId,
                                             List<String> evalScenarioIds,
                                             String datasetVersionId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (candidateVersionId == null || candidateVersionId.isBlank()) {
            throw new IllegalArgumentException("candidateVersionId is required");
        }
        PromptVersionEntity candidate = promptVersionRepository.findById(candidateVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Candidate prompt version not found: " + candidateVersionId));
        // Baseline resolution per Ratify #7-C — null = agent's current active
        // prompt (tracked via AgentEntity.activePromptVersionId, consistent
        // with PromptPromotionService.applyPromotion path).
        // W7 fix (Phase 1.4d, 2026-05-16) — parse agentId first; NumberFormatException
        // would otherwise bubble through controller 400-mapping as a 500.
        long agentIdLong;
        try {
            agentIdLong = Long.parseLong(agentId);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "agentId must be numeric, got: " + agentId, nfe);
        }
        String resolvedBaselineId = baselineVersionId;
        // PROMPT-IMPROVER-GENESIS-BASELINE (2026-05-21): use findByIdForUpdate
        // to serialize per-agent prompt-version writes — concurrent flywheel
        // triggers on the same agentId would otherwise produce 2 v1 genesis
        // rows in the race window between read-active-version and save (the
        // UNIQUE (agent_id, version_number) constraint would catch the
        // duplicate but only by 500'ing the second caller rather than letting
        // it observe the first caller's genesis row). Mirrors
        // PromptPromotionService.evaluateAndPromote's pattern.
        AgentEntity agent = agentRepository.findByIdForUpdate(agentIdLong)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (resolvedBaselineId == null || resolvedBaselineId.isBlank()) {
            resolvedBaselineId = agent.getActivePromptVersionId();
            if (resolvedBaselineId == null) {
                // GENESIS PATH: first time this agent enters the flywheel —
                // materialize v1 active baseline from t_agent.system_prompt
                // so the A/B has something to compare against. Without this
                // every agent's first attribution-triggered run dies at "no
                // active prompt version". The genesis row uses
                // source='genesis' (new source value; no CHECK constraint
                // on t_prompt_version.source per V4 schema) to distinguish
                // it from auto_improve / attribution / manual writes in
                // dashboard listings.
                //
                // The PESSIMISTIC_WRITE on the agent row above plus the
                // UNIQUE (agent_id, version_number) index on t_prompt_version
                // together prevent duplicate v1 rows under concurrent
                // attribute-triggered runAbTestAgainst calls.
                String currentPrompt = agent.getSystemPrompt();
                if (currentPrompt == null || currentPrompt.isBlank()) {
                    throw new IllegalStateException("Agent " + agentId
                            + " has neither active prompt version nor system_prompt — "
                            + "cannot bootstrap baseline");
                }
                PromptVersionEntity genesis = new PromptVersionEntity();
                genesis.setId(UUID.randomUUID().toString());
                // agentId here is the String form (schema agent_id is
                // VARCHAR(36)); agentIdLong is only used for AgentRepository
                // lookups. Do NOT pass agentIdLong here — would silently
                // produce a numeric-string in a VARCHAR column that other
                // lookups (e.g. findMaxVersionNumber) couldn't match.
                genesis.setAgentId(agentId);
                genesis.setVersionNumber(1);
                genesis.setStatus("active");
                genesis.setSource("genesis");
                genesis.setContent(currentPrompt);
                // createdAt left null — @CreatedDate auditing listener
                // populates it on save; explicit set would race the listener.
                promptVersionRepository.save(genesis);

                agent.setActivePromptVersionId(genesis.getId());
                agentRepository.save(agent);

                log.info("[PromptImprover] genesis baseline v1 created for agentId={} "
                                + "(versionId={}, {} chars, from t_agent.system_prompt)",
                        agentId, genesis.getId(), currentPrompt.length());
                resolvedBaselineId = genesis.getId();
            }
        }
        final String baselineId = resolvedBaselineId;
        PromptVersionEntity baselineVersion = promptVersionRepository.findById(baselineId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Baseline prompt version not found: " + baselineId));

        // EVAL-DATASET-LAYER V1: 3 paths for scenario resolution.
        //   (1) datasetVersionId (new) — resolve via EvalDatasetService bridge.
        //   (2) evalScenarioIds (explicit) — caller knows exactly what to run.
        //   (3) ephemeral fallback (legacy) — held_out for agent or attribution
        //       pattern-derived ephemerals.
        // (1)/(2) are mutually exclusive at the AbEvalRunRequest record level;
        // here we just branch on which one is non-null.
        //
        // ★ V1 r2 UX fix (2026-05-24): attribution-triggered A/B run that
        // didn't specify datasetVersionId/scenarioIds explicitly auto-resolves
        // a default dataset via EvalDatasetService.findDefaultVersionIdForAgent
        // (prefers "mixed" → "baseline" pattern, agent-specific → global).
        // Without this, attribution path always ran ephemeral session_derived
        // → baseline_pass_rate=0% (see Event 122 first 3 retries before this fix).
        String resolvedDatasetVersionId = datasetVersionId;
        if ((resolvedDatasetVersionId == null || resolvedDatasetVersionId.isBlank())
                && (evalScenarioIds == null || evalScenarioIds.isEmpty())
                && evalDatasetService != null) {
            String autoPicked = evalDatasetService.findDefaultVersionIdForAgent(agentId);
            if (autoPicked != null) {
                log.info("Attribution A/B run for agentId={} auto-resolved default datasetVersionId={} "
                        + "(no caller-specified dataset/scenarios; legacy ephemeral fallback would "
                        + "have produced 0% baseline_pass_rate)", agentId, autoPicked);
                resolvedDatasetVersionId = autoPicked;
            }
        }
        final String effectiveDatasetVersionId = resolvedDatasetVersionId;

        List<EvalScenarioEntity> scenarios;
        List<String> ephemeralIds = null;
        if (effectiveDatasetVersionId != null && !effectiveDatasetVersionId.isBlank()) {
            if (evalDatasetService == null) {
                throw new IllegalStateException(
                        "datasetVersionId path requested but EvalDatasetService not wired");
            }
            scenarios = evalDatasetService.getScenariosForVersion(effectiveDatasetVersionId);
        } else if (evalScenarioIds != null && !evalScenarioIds.isEmpty()) {
            scenarios = evalScenarioRepository.findAllById(evalScenarioIds);
        } else {
            scenarios = evalScenarioRepository.findByAgentIdAndSplit(agentId, "held_out");
            if (scenarios.isEmpty()) {
                EphemeralBatch batch = buildEphemeralScenariosForPromptCandidate(candidateVersionId);
                scenarios = batch.scenarios();
                ephemeralIds = batch.ephemeralIds();
            }
        }
        if (scenarios.isEmpty()) {
            throw new IllegalStateException(
                    "No EvalScenarios available for agent " + agentId
                            + " and ephemeral fallback yielded none either");
        }

        // Create PromptAbRunEntity (unique-index gated against duplicate runs
        // on the same agent, mirroring startImprovement step 4).
        boolean hasActive = !promptAbRunRepository.findByAgentIdAndStatus(agentId, "PENDING").isEmpty()
                || !promptAbRunRepository.findByAgentIdAndStatus(agentId, "RUNNING").isEmpty();
        if (hasActive) {
            // Cleanup ephemeral before bailing — caller's retry path may want a clean slate.
            ephemeralScenarioCleanupService.cleanupEphemerals(ephemeralIds);
            throw new ImprovementConflictException("An A/B run is already active for agent " + agentId);
        }

        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId(UUID.randomUUID().toString());
        abRun.setAgentId(agentId);
        abRun.setPromptVersionId(candidate.getId());
        // EVAL-DATASET-LAYER V1 (V111): pin the run to its dataset version
        // snapshot for cross-run comparability. Set unconditionally — null on
        // the legacy/ephemeral path is intentional (acceptance #6 of MRD).
        abRun.setDatasetVersionId(effectiveDatasetVersionId);
        // No EvalTaskEntity for the baseline anchor — attribution path doesn't
        // have a prior eval run. Leave baselineEvalRunId null; downstream
        // Phase 1.4b AbEvalPipeline attribution overload will populate
        // baselinePassRate by re-evaluating the active prompt fresh against
        // the same scenarios.
        promptAbRunRepository.save(abRun);

        final String abRunId = abRun.getId();
        final List<String> capturedEphemeralIds = ephemeralIds;
        final String capturedDatasetVersionId = effectiveDatasetVersionId;
        // F4 fix (Phase 2 r2, code reviewer HIGH-2): pass IDs, NOT entity
        // references. This @Transactional method commits after return; if the
        // async runnable were to hold direct entity refs from the outer tx,
        // those entities would be detached when the lambda runs on the
        // coordinator pool thread → JPA mutations would silently no-op and
        // abRun would stay PENDING forever. Re-load in the async block so the
        // entities are attached to the lambda's own tx (or no tx, in which
        // case AbEvalPipeline handles its own persistence via its repo refs).
        // Mirrors the existing startImprovement → runImprovementAsync pattern.
        final List<String> capturedScenarioIds = scenarios.stream()
                .map(EvalScenarioEntity::getId).toList();
        final String capturedAgentId = String.valueOf(agent.getId());
        final String capturedBaselineId = baselineId;
        final String capturedCandidateId = candidateVersionId;
        Runnable asyncTask = () -> {
            // DIAG-2026-05-23: silent-failure forensics — log entry + every step
            // + uncaught exception (Future-swallowing makes async lambdas
            // invisible in normal operation).
            log.info("[AttrAB-async] ENTRY abRunId={} candidateId={} baselineId={} agentId={} scenarioIds={}",
                    abRunId, capturedCandidateId, capturedBaselineId, capturedAgentId, capturedScenarioIds.size());
            try {
                PromptAbRunEntity reloadedAbRun = promptAbRunRepository.findById(abRunId)
                        .orElseThrow(() -> new RuntimeException(
                                "AB run not found in async reload: " + abRunId));
                log.info("[AttrAB-async] reloaded abRun status={} for abRunId={}",
                        reloadedAbRun.getStatus(), abRunId);
                PromptVersionEntity reloadedCandidate = promptVersionRepository.findById(capturedCandidateId)
                        .orElseThrow(() -> new RuntimeException(
                                "Candidate prompt version not found in async reload: " + capturedCandidateId));
                PromptVersionEntity reloadedBaseline = promptVersionRepository.findById(capturedBaselineId)
                        .orElseThrow(() -> new RuntimeException(
                                "Baseline prompt version not found in async reload: " + capturedBaselineId));
                AgentEntity reloadedAgent = agentRepository.findById(Long.parseLong(capturedAgentId))
                        .orElseThrow(() -> new RuntimeException(
                                "Agent not found in async reload: " + capturedAgentId));
                List<EvalScenarioEntity> reloadedScenarios =
                        evalScenarioRepository.findAllById(capturedScenarioIds);
                log.info("[AttrAB-async] all reloads OK — invoking abEvalPipeline.run "
                        + "abRunId={} scenarios={} datasetVersionId={}",
                        abRunId, reloadedScenarios.size(), capturedDatasetVersionId);
                if (capturedDatasetVersionId != null && !capturedDatasetVersionId.isBlank()) {
                    // EVAL-DATASET-LAYER V1: new dataset-version overload also
                    // back-writes actualBaselinePassRate on the version row.
                    abEvalPipeline.run(reloadedAbRun, reloadedCandidate, reloadedBaseline,
                            reloadedAgent, capturedDatasetVersionId);
                } else {
                    // Legacy/ephemeral path — scenarios list is the source of truth.
                    abEvalPipeline.run(reloadedAbRun, reloadedCandidate, reloadedBaseline,
                            reloadedAgent, reloadedScenarios);
                }
                log.info("Attribution A/B run {} dispatched + completed via AbEvalPipeline",
                        abRunId);
            } catch (Throwable t) {
                log.error("[AttrAB-async] UNCAUGHT exception abRunId={} — Future "
                        + "would have swallowed this silently", abRunId, t);
                throw t;
            } finally {
                ephemeralScenarioCleanupService.cleanupEphemerals(capturedEphemeralIds);
            }
        };

        // Fix 2026-05-23 (Event 122 retry verify): defer the submit until the
        // outer @Transactional that just INSERTed the PromptAbRunEntity row
        // commits. Otherwise the coord thread races the listener thread's
        // commit and findById(abRunId) returns Optional.empty under READ
        // COMMITTED → orElseThrow → silent Future-swallowed RuntimeException
        // → row stays PENDING forever (jstack showed coord-0 in WAITING).
        // Pattern mirrors Spring's standard AFTER_COMMIT semantics already
        // used by SkillAbCompletedEventPublisher (different mechanism but
        // same intent: don't touch DB state from another thread before our
        // tx is visible).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    coordinatorExecutor.submit(asyncTask);
                }
            });
        } else {
            // Defensive fallback: not in an active tx (e.g. unit test calling
            // bare service). Submit immediately — caller has presumably
            // already committed any DB writes the lambda depends on, or
            // they're using mocks.
            log.warn("[AttrAB-async] runAbTestAgainst invoked outside active "
                    + "@Transactional — submitting asyncTask without afterCommit "
                    + "synchronization (abRunId={}). This is fine for tests but "
                    + "in production indicates the @Transactional proxy was "
                    + "bypassed.", abRunId);
            coordinatorExecutor.submit(asyncTask);
        }
        return abRunId;
    }

    /**
     * Phase 1.4 Ratify #7-E helper — extract 3 ephemeral EvalScenarios from
     * the pattern members of the attribution event that produced the given
     * candidate prompt version UUID. Throws {@link IllegalStateException}
     * when the V88 sidecar link is missing or the pattern has no members.
     */
    private EphemeralBatch buildEphemeralScenariosForPromptCandidate(String candidateVersionId) {
        List<com.skillforge.server.entity.OptimizationEventEntity> events =
                optimizationEventRepository.findByCandidatePromptVersionUuid(candidateVersionId);
        if (events.isEmpty()) {
            throw new IllegalStateException(
                    "Ephemeral fallback: no OptimizationEvent linked to candidate prompt version "
                            + candidateVersionId + "; V88 sidecar populated?");
        }
        com.skillforge.server.entity.OptimizationEventEntity event = events.get(0);
        Long patternId = event.getPatternId();
        if (patternId == null) {
            throw new IllegalStateException(
                    "Ephemeral fallback: OptimizationEvent " + event.getId()
                            + " has no patternId; cannot extract member scenarios");
        }
        List<com.skillforge.server.entity.PatternSessionMemberEntity> members =
                patternSessionMemberRepository.findByPatternIdOrderByAddedAtDesc(
                        patternId, org.springframework.data.domain.PageRequest.of(0, 3));
        if (members.isEmpty()) {
            throw new IllegalStateException(
                    "Ephemeral fallback: pattern " + patternId + " has no members");
        }
        List<EvalScenarioEntity> ephemerals = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (com.skillforge.server.entity.PatternSessionMemberEntity member : members) {
            com.skillforge.server.entity.SessionEntity sess =
                    sessionRepository.findById(member.getSessionId()).orElse(null);
            if (sess == null) continue;
            EvalScenarioEntity ephemeral = sessionScenarioExtractor.extractFromSession(sess);
            if (ephemeral == null) continue;
            ephemeral.setStatus("ephemeral");
            ephemerals.add(evalScenarioRepository.save(ephemeral));
            ids.add(ephemeral.getId());
        }
        return new EphemeralBatch(ephemerals, ids);
    }

    // Phase 1.4d W5 fix (2026-05-17): in-class `cleanupEphemeralScenarios`
    // helper removed — cleanup now goes through {@link EphemeralScenarioCleanupService}
    // so @Transactional(REQUIRES_NEW) actually takes effect (Spring AOP proxy
    // ignores self-invocation, which is why the old in-class method silently
    // no-op'd the deleteAllById inside the async coordinator finally).

    /** Phase 1.4 internal container for ephemeral scenario batch + their IDs. */
    private record EphemeralBatch(List<EvalScenarioEntity> scenarios, List<String> ephemeralIds) {}

    @Transactional
    public ImprovementStartResult startImprovement(String agentId,
                                                   String evalRunId,
                                                   long userId,
                                                   String improvementSuggestion) {
        AgentEntity agent = agentRepository.findById(Long.parseLong(agentId))
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));
        EvalTaskEntity evalRun = evalRunRepository.findById(evalRunId)
                .orElseThrow(() -> new RuntimeException("Eval run not found: " + evalRunId));

        // 1. Check eligibility
        checkEligibility(agent, evalRun);

        // 2. Pre-check for active AB run to provide a clean 409 (DB unique index is the final guard)
        boolean hasActive = !promptAbRunRepository.findByAgentIdAndStatus(agentId, "PENDING").isEmpty()
                || !promptAbRunRepository.findByAgentIdAndStatus(agentId, "RUNNING").isEmpty();
        if (hasActive) {
            throw new ImprovementConflictException("An improvement run is already active for agent " + agentId);
        }

        // 3. Create PromptVersionEntity (content placeholder until LLM generates it)
        int nextVersion = promptVersionRepository.findMaxVersionNumber(agentId).orElse(0) + 1;
        PromptVersionEntity version = new PromptVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setAgentId(agentId);
        version.setContent(""); // placeholder, will be filled by LLM
        version.setVersionNumber(nextVersion);
        version.setStatus("candidate");
        version.setSource("auto_improve");
        version.setSourceEvalRunId(evalRunId);
        version.setBaselinePassRate(evalRun.getOverallPassRate());
        if (improvementSuggestion != null && !improvementSuggestion.isBlank()) {
            version.setImprovementRationale(improvementSuggestion.trim());
        }
        promptVersionRepository.save(version);

        // 4. Create PromptAbRunEntity (unique index on active runs prevents races)
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId(UUID.randomUUID().toString());
        abRun.setAgentId(agentId);
        abRun.setPromptVersionId(version.getId());
        abRun.setBaselineEvalRunId(evalRunId);
        abRun.setTriggeredByUserId(userId);
        promptAbRunRepository.save(abRun);

        // 5. Submit async improvement after this transaction commits
        String versionId = version.getId();
        String abRunId = abRun.getId();
        coordinatorExecutor.submit(() -> runImprovementAsync(versionId, abRunId, evalRunId, agentId));

        // 6. Return result
        return new ImprovementStartResult(agentId, abRun.getId(), version.getId(), "PENDING");
    }

    private void checkEligibility(AgentEntity agent, EvalTaskEntity evalRun) {
        if (!"COMPLETED".equals(evalRun.getStatus())) {
            throw new ImprovementIneligibleException("EVAL_NOT_COMPLETED");
        }

        if (evalRun.getPrimaryAttribution() == null || !ELIGIBLE.contains(evalRun.getPrimaryAttribution())) {
            throw new ImprovementIneligibleException("INELIGIBLE_ATTRIBUTION");
        }

        if (agent.getLastPromotedAt() != null
                && agent.getLastPromotedAt().isAfter(Instant.now().minusSeconds(24 * 3600))) {
            throw new ImprovementIneligibleException("COOLDOWN_ACTIVE");
        }

        if (agent.isAutoImprovePaused()) {
            throw new ImprovementIneligibleException("AUTO_IMPROVE_PAUSED");
        }
    }

    /**
     * Async improvement orchestrator — Phase 1.2 refactor. Generates the
     * candidate via LLM (PRESERVED), then delegates the A/B eval +
     * promote-decision sequence to {@link AbstractAbEvalRunner#run} via
     * {@link ThreadLocal}-shared run state. The 5 hooks at the bottom of
     * this class wrap the existing {@link AbEvalPipeline#run} +
     * {@link PromptPromotionService#evaluateAndPromote} calls without
     * changing their semantics.
     *
     * <p>Behavior preserved bit-for-bit:
     * <ul>
     *   <li>generateCandidatePrompt LLM fill order unchanged</li>
     *   <li>abEvalPipeline.run still writes abRun.status RUNNING → COMPLETED,
     *       baselinePassRate, candidatePassRate, deltaPassRate, scenarioResults</li>
     *   <li>promotionService.evaluateAndPromote still applies its 4 gates
     *       (delta threshold / promoted-today / 24h cooldown / paused)</li>
     *   <li>FAILED handling on outer catch reloads abRun to avoid overwriting
     *       a COMPLETED row set by the pipeline (same as pre-1.2)</li>
     * </ul>
     */
    private void runImprovementAsync(String versionId, String abRunId, String evalRunId, String agentId) {
        try {
            // Reload fresh entities from DB (we're in a new thread/transaction context)
            PromptVersionEntity version = promptVersionRepository.findById(versionId)
                    .orElseThrow(() -> new RuntimeException("Version not found: " + versionId));
            PromptAbRunEntity abRun = promptAbRunRepository.findById(abRunId)
                    .orElseThrow(() -> new RuntimeException("AB run not found: " + abRunId));
            EvalTaskEntity evalRun = evalRunRepository.findById(evalRunId)
                    .orElseThrow(() -> new RuntimeException("Eval run not found: " + evalRunId));
            AgentEntity agent = agentRepository.findById(Long.parseLong(agentId))
                    .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

            // 1. Generate candidate prompt via LLM (PRESERVED bit-for-bit).
            String candidatePrompt = generateCandidatePrompt(agent, evalRun, version.getImprovementRationale());
            version.setContent(candidatePrompt);
            promptVersionRepository.save(version);

            // 2. Build a synthetic baseline placeholder so the AbstractAbEvalRunner
            //    template (which requires non-null baseline + candidate) can run.
            //    V3 doesn't have a "baseline PromptVersionEntity" concept — the
            //    baseline data lives in evalRun.scenarioResultsJson. The placeholder
            //    is never persisted; the hooks use object-reference equality (via
            //    PromptRunState.baseline) to discriminate baseline vs candidate.
            PromptVersionEntity baselinePlaceholder = new PromptVersionEntity();
            baselinePlaceholder.setId("baseline-placeholder-" + agentId);
            baselinePlaceholder.setAgentId(agentId);
            baselinePlaceholder.setContent(agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "");
            baselinePlaceholder.setStatus("active");

            // 3. Set up per-run state for hooks.
            PromptRunState state = new PromptRunState(baselinePlaceholder, version, abRun, agent, evalRun);
            currentRun.set(state);
            try {
                // 4. Invoke the 5-step template. Per spec §3.1 hook order
                //    (ratified #3): inject(baseline) → runEvalSet(baseline) →
                //    inject(candidate) → runEvalSet(candidate) → judgeAndCompare →
                //    shouldPromote → promoteIfNeeded.
                //    - inject*: PromptSurface stashes version in session registry (no-op for eval)
                //    - runEvalSet(baseline): returns precomputed rate from evalRun
                //    - runEvalSet(candidate): delegates to abEvalPipeline.run() (preserves all writes)
                //    - shouldPromote: returns true (real gate lives in PromotionService)
                //    - promoteIfNeeded: calls promotionService.evaluateAndPromote (real gate)
                SandboxContext ctx = new SandboxContext(agent.getId(), abRunId, null);
                run(abRunId, baselinePlaceholder, version, ctx);
            } finally {
                currentRun.remove();
            }
        } catch (Exception e) {
            log.error("Improvement async run failed for agent {}: {}", agentId, e.getMessage(), e);
            // Reload abRun to avoid overwriting a COMPLETED status that may have been set by the pipeline
            promptAbRunRepository.findById(abRunId).ifPresent(fresh -> {
                if (!"COMPLETED".equals(fresh.getStatus())) {
                    fresh.setStatus("FAILED");
                    fresh.setFailureReason(e.getMessage());
                    fresh.setCompletedAt(Instant.now());
                    promptAbRunRepository.save(fresh);
                }
            });
        }
    }

    // ────────── AbstractAbEvalRunner hook implementations (Phase 1.2) ──────────

    /**
     * Hook 1 — eval-set runner. Discriminates baseline vs candidate by
     * <i>object reference equality</i> against {@link PromptRunState#baseline}.
     * For baseline side, returns a precomputed {@link EvalRun} reading from
     * the historical evalRun's scenarioResultsJson (placeholder rate uses
     * {@code evalRun.getOverallPassRate()} since AbEvalPipeline's held-out
     * recomputation also runs on the candidate-side delegate and writes the
     * real value into abRun; the template's EvalRun is internal-only and not
     * persisted). For candidate side, delegates to {@link AbEvalPipeline#run}
     * which writes all abRun fields exactly as the pre-1.2 path.
     */
    /**
     * Eval-set runner (public so {@link PromptEvalService} adapter can
     * delegate here). Phase 1.2 reviewer-r1 fix: NOT @Override (template's
     * runEvalSet is non-abstract now, delegating to injected EvalService).
     */
    public AbstractAbEvalRunner.EvalRun runEvalSetInternal(SandboxContext ctx, PromptVersionEntity version) {
        PromptRunState state = currentRun.get();
        if (state == null) {
            throw new IllegalStateException(
                    "PromptImproverService.runEvalSetInternal called outside runImprovementAsync orchestration "
                            + "(currentRun ThreadLocal is empty)");
        }
        if (version == state.baseline) {
            // Baseline side — read precomputed rate from evalRun. The held-out
            // recomputation that abEvalPipeline.run does internally happens on
            // the candidate-side delegate below (zero behavior drift); the
            // value returned here is only used by the template's internal
            // Comparison record, never persisted.
            double baselineRate = state.evalRun != null
                    ? state.evalRun.getOverallPassRate() : 0.0;
            String evalRunId = state.evalRun != null ? state.evalRun.getId() : null;
            return new AbstractAbEvalRunner.EvalRun(evalRunId, baselineRate, 0);
        }
        // Candidate side — delegate to abEvalPipeline which preserves all
        // existing abRun writes (RUNNING → COMPLETED, baselinePassRate,
        // candidatePassRate, deltaPassRate, scenarioResults, candidate
        // version delta/abRunId writes).
        abEvalPipeline.run(state.abRun, version, state.evalRun, state.agent);
        double candidateRate = state.abRun.getCandidatePassRate() != null
                ? state.abRun.getCandidatePassRate() : 0.0;
        return new AbstractAbEvalRunner.EvalRun(state.abRun.getId(), candidateRate, 0);
    }

    @Override
    protected AbstractAbEvalRunner.Comparison judgeAndCompare(AbstractAbEvalRunner.EvalRun baseline,
                                                              AbstractAbEvalRunner.EvalRun candidate) {
        return new AbstractAbEvalRunner.Comparison(baseline.passRate(), candidate.passRate(),
                candidate.passRate() - baseline.passRate());
    }

    @Override
    protected boolean shouldPromote(AbstractAbEvalRunner.Comparison comparison) {
        // V3 design: the real gate logic lives inside PromotionService
        // (delta threshold + promoted-today + 24h cooldown + paused). Always
        // invoke promoteIfNeeded so PromotionService can apply its own gates;
        // those gates may reject without promoting. Returning true here is
        // NOT "always promote" — it's "always invoke the gate".
        return true;
    }

    @Override
    protected void promoteIfNeeded(PromptVersionEntity candidate,
                                    AbstractAbEvalRunner.Comparison comparison) {
        PromptRunState state = currentRun.get();
        if (state == null) {
            throw new IllegalStateException(
                    "PromptImproverService.promoteIfNeeded called outside runImprovementAsync orchestration");
        }
        // V3 PromotionService.evaluateAndPromote owns:
        //  - delta < threshold rejection (with decline tracking)
        //  - promoted-today rejection
        //  - 24h cooldown rejection
        //  - paused-agent rejection
        //  - atomic deprecate-old + activate-candidate + update-agent flow
        //  - PromptPromotedEvent publish
        promotionService.evaluateAndPromote(state.abRun.getId(),
                String.valueOf(state.agent.getId()));
    }

    /**
     * Per-run ephemeral state shared between {@link #runImprovementAsync} and
     * the 5 hooks. Held in a {@link ThreadLocal} keyed by execution thread.
     * The {@code baseline} field is a transient placeholder (never persisted)
     * used purely for object-reference discrimination inside
     * {@link #runEvalSet}.
     */
    private record PromptRunState(
            PromptVersionEntity baseline,
            PromptVersionEntity candidate,
            PromptAbRunEntity abRun,
            AgentEntity agent,
            EvalTaskEntity evalRun) {}

    /**
     * V3.1 attribution-path candidate generator. Mirrors the rule structure of
     * {@link #generateCandidatePrompt} but without the {@link EvalTaskEntity}
     * dependency — attribution flow only has the curator's
     * {@code attributedDescription} as failure context (no scenario-level pass
     * rates, no eligible-failure breakdown). System prompt + temperature kept
     * identical so the two paths produce comparable candidates.
     */
    private String generateCandidatePromptFromAttribution(AgentEntity agent,
                                                          String attributedDescription) {
        String currentPrompt = agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "";
        String memoryBlock = loadRenderedMemoryContext(agent.getOwnerId(), attributedDescription);
        if (memoryBlock.isBlank()) {
            memoryBlock = "(none)";
        }

        String systemPrompt = """
                You are an expert prompt engineer. Your task is to analyze an attribution \
                report from a curator agent and generate an improved system prompt.

                Rules:
                - Output ONLY the improved system prompt text, nothing else
                - Preserve the core intent and capabilities of the original prompt
                - Focus on addressing the specific failure pattern described
                - Keep the prompt concise and actionable
                - Do not add meta-commentary or explanations""";

        String userMessage = String.format("""
                Current system prompt:
                ---
                %s
                ---

                Attribution analysis (from curator agent):
                %s

                Relevant long-term memory context:
                %s

                Generate an improved system prompt that addresses the failure pattern \
                described above.""",
                currentPrompt,
                attributedDescription.trim(),
                memoryBlock);

        LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
        if (provider == null) {
            throw new RuntimeException("No LLM provider available for prompt generation");
        }

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(systemPrompt);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage));
        request.setMessages(messages);
        request.setMaxTokens(2000);
        request.setTemperature(0.3);

        LlmResponse response = provider.chat(request);
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("LLM returned empty candidate prompt for attribution flow");
        }
        return content.trim();
    }

    private String loadRenderedMemoryContext(Long userId, String attributedDescription) {
        if (memoryContextProvider == null || userId == null) {
            return "";
        }
        try {
            MemoryContextSnapshot snapshot = memoryContextProvider.load(userId, attributedDescription);
            return snapshot != null && snapshot.rendered() != null ? snapshot.rendered().trim() : "";
        } catch (RuntimeException e) {
            log.warn("Attribution prompt memory context load failed userId={}: {}", userId, e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private String generateCandidatePrompt(AgentEntity agent,
                                           EvalTaskEntity evalRun,
                                           String improvementSuggestion) {
        String currentPrompt = agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "";

        // Build failure analysis from scenarioResultsJson
        StringBuilder failureAnalysis = new StringBuilder();
        StringBuilder passExamples = new StringBuilder();

        if (evalRun.getScenarioResultsJson() != null) {
            try {
                List<Map<String, Object>> results = objectMapper.readValue(
                        evalRun.getScenarioResultsJson(),
                        new TypeReference<List<Map<String, Object>>>() {});

                List<Map<String, Object>> eligibleFailures = results.stream()
                        .filter(r -> {
                            String attr = (String) r.get("attribution");
                            return attr != null && ELIGIBLE.stream()
                                    .anyMatch(e -> e.name().equals(attr));
                        })
                        .filter(r -> !Boolean.TRUE.equals(r.get("pass")))
                        .toList();

                for (Map<String, Object> failure : eligibleFailures) {
                    failureAnalysis.append("- Scenario: ").append(failure.get("name"))
                            .append("\n  Task: ").append(failure.get("task"))
                            .append("\n  Attribution: ").append(failure.get("attribution"))
                            .append("\n  Score: ").append(failure.get("compositeScore"))
                            .append("\n");
                }

                List<Map<String, Object>> passes = results.stream()
                        .filter(r -> Boolean.TRUE.equals(r.get("pass")))
                        .limit(3)
                        .toList();

                for (Map<String, Object> pass : passes) {
                    passExamples.append("- Scenario: ").append(pass.get("name"))
                            .append(" (score: ").append(pass.get("compositeScore")).append(")\n");
                }
            } catch (Exception e) {
                log.warn("Failed to parse scenario results for prompt generation", e);
            }
        }

        String systemPrompt = """
                You are an expert prompt engineer. Your task is to analyze the failure patterns \
                from an AI agent's evaluation run and generate an improved system prompt.

                Rules:
                - Output ONLY the improved system prompt text, nothing else
                - Preserve the core intent and capabilities of the original prompt
                - Focus on addressing the specific failure patterns identified
                - Keep the prompt concise and actionable
                - Do not add meta-commentary or explanations""";

        String suggestionSection = (improvementSuggestion == null || improvementSuggestion.isBlank())
                ? ""
                : "\nAnalysis improvement suggestion:\n" + improvementSuggestion.trim() + "\n";

        String userMessage = String.format("""
                Current system prompt:
                ---
                %s
                ---

                Primary failure attribution: %s
                Overall pass rate: %.1f%%

                Failed scenarios (attributed to prompt quality / context overflow):
                %s

                Passing scenario examples:
                %s

                %s

                Generate an improved system prompt that addresses these failure patterns.""",
                currentPrompt,
                evalRun.getPrimaryAttribution() != null ? evalRun.getPrimaryAttribution().name() : "UNKNOWN",
                evalRun.getOverallPassRate(),
                failureAnalysis.length() > 0 ? failureAnalysis.toString() : "(none)",
                passExamples.length() > 0 ? passExamples.toString() : "(none)",
                suggestionSection);

        LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
        if (provider == null) {
            throw new RuntimeException("No LLM provider available for prompt generation");
        }

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(systemPrompt);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage));
        request.setMessages(messages);
        request.setMaxTokens(2000);
        request.setTemperature(0.3);

        LlmResponse response = provider.chat(request);
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("LLM returned empty candidate prompt");
        }
        return content.trim();
    }
}
