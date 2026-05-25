package com.skillforge.server.improve.behavior;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig.CustomRule;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.EvalDatasetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — orchestrator for the
 * {@code with_vs_without} A/B run on a candidate
 * {@link BehaviorRuleVersionEntity}. Persists a
 * {@link BehaviorRuleAbRunEntity}, splits the dataset into target +
 * regression subsets, and computes dual-criteria deltas.
 *
 * <p><b>Why a parallel implementation instead of extending
 * {@code AbstractAbEvalRunner<V>}</b> (r1-FIX, java-design WARN): the V4
 * template assumes a single delta over the eval set. Dual-criteria here needs
 * <i>two</i> deltas (target vs regression), which would require teaching the
 * template surface that "delta may be multi-dimensional" — breaking
 * single-pass-rate. V1 keeps a parallel lifecycle; V2 will extract a shared
 * {@code AbstractDualCriteriaRunner} once a second dual-criteria surface
 * materialises.
 *
 * <p>The {@link #startAbForVersion} caller is expected to be inside an
 * outer {@code @Transactional} method. We register an
 * {@link TransactionSynchronization#afterCommit() afterCommit} hook to
 * schedule {@link #runAsync} <i>after</i> the abRun row commits, mirroring
 * the {@code SkillAbEvalService} R4 fix — without it, the coordinator pool
 * thread races the outer tx commit and {@code findById(abRunId)} returns
 * empty → silent skip → row stuck PENDING.
 */
@Service
public class BehaviorRuleAbEvalService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorRuleAbEvalService.class);

    /**
     * Dual-criteria target floor: candidate must beat baseline on the target
     * subset by at least this many percentage points. Fallback path
     * (target subset empty) bypasses this check entirely.
     *
     * <p>TODO(V2): move to {@code BehaviorRuleAbConfig @ConfigurationProperties}
     * for per-rule-type / per-agent overrides (e.g. style rule may set TARGET=5).
     */
    public static final double TARGET_DELTA_THRESHOLD_PP = 10.0;

    /**
     * Dual-criteria regression floor: candidate may regress on the regression
     * subset by up to this many percentage points (negative). Anything
     * worse blocks promotion.
     */
    public static final double REGRESSION_DELTA_FLOOR_PP = -3.0;

    private final BehaviorRuleVersionRepository versionRepository;
    private final BehaviorRuleAbRunRepository abRunRepository;
    private final EvalScenarioDraftRepository scenarioRepository;
    private final EvalDatasetService evalDatasetService;
    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final BehaviorRuleVersionToCustomRulesMapper rulesMapper;
    private final AbEvalPipeline abEvalPipeline;
    private final ChatEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final AgentRoleResolver agentRoleResolver;
    private final ExecutorService coordinatorExecutor;

    public BehaviorRuleAbEvalService(
            BehaviorRuleVersionRepository versionRepository,
            BehaviorRuleAbRunRepository abRunRepository,
            EvalScenarioDraftRepository scenarioRepository,
            EvalDatasetService evalDatasetService,
            AgentRepository agentRepository,
            AgentService agentService,
            BehaviorRuleVersionToCustomRulesMapper rulesMapper,
            AbEvalPipeline abEvalPipeline,
            ChatEventBroadcaster broadcaster,
            ObjectMapper objectMapper,
            AgentRoleResolver agentRoleResolver,
            @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor) {
        this.versionRepository = versionRepository;
        this.abRunRepository = abRunRepository;
        this.scenarioRepository = scenarioRepository;
        this.evalDatasetService = evalDatasetService;
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.rulesMapper = rulesMapper;
        this.abEvalPipeline = abEvalPipeline;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
        this.agentRoleResolver = agentRoleResolver;
        this.coordinatorExecutor = coordinatorExecutor;
    }

    /**
     * Persist a fresh {@link BehaviorRuleAbRunEntity} (PENDING) and schedule
     * {@link #runAsync} to fire after the outer transaction commits.
     *
     * @param candidateVersionId       candidate behavior_rule version id
     * @param overrideDatasetVersionId optional dataset version override; null →
     *                                 agent default via {@link EvalDatasetService}
     * @return abRunId of the newly-created row
     * @throws IllegalStateException if candidate is not in STATUS_CANDIDATE or
     *                               no dataset can be resolved
     */
    @Transactional
    public String startAbForVersion(String candidateVersionId, String overrideDatasetVersionId) {
        BehaviorRuleVersionEntity candidate = versionRepository.findById(candidateVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "BehaviorRuleVersion not found: " + candidateVersionId));
        if (!BehaviorRuleVersionEntity.STATUS_CANDIDATE.equals(candidate.getStatus())) {
            throw new IllegalStateException("Only candidate versions can start A/B: id="
                    + candidateVersionId + " state=" + candidate.getStatus());
        }

        // INV-6: mark any in-flight run for this candidate SUPERSEDED so the
        // retry-guard idempotency invariant holds (at most one PENDING/RUNNING
        // per candidate version).
        abRunRepository.findFirstByCandidateVersionIdAndStatusIn(
                        candidateVersionId,
                        List.of(BehaviorRuleAbRunEntity.STATUS_PENDING,
                                BehaviorRuleAbRunEntity.STATUS_RUNNING))
                .ifPresent(prior -> {
                    prior.setStatus(BehaviorRuleAbRunEntity.STATUS_SUPERSEDED);
                    prior.setCompletedAt(Instant.now());
                    abRunRepository.save(prior);
                    log.info("[BehaviorRuleAb] superseded prior A/B run id={} for candidateVersionId={}",
                            prior.getId(), candidateVersionId);
                });

        // Dataset resolution: override > agent default. Failure here is loud —
        // a misconfigured agent with no default dataset should never silently
        // run a no-op A/B that produces null deltas.
        String datasetVersionId = (overrideDatasetVersionId != null && !overrideDatasetVersionId.isBlank())
                ? overrideDatasetVersionId
                : evalDatasetService.findDefaultVersionIdForAgent(candidate.getAgentId());
        if (datasetVersionId == null) {
            throw new IllegalStateException(
                    "No dataset version available for agent: " + candidate.getAgentId()
                            + " (and no override supplied)");
        }

        BehaviorRuleAbRunEntity abRun = new BehaviorRuleAbRunEntity();
        abRun.setId(UUID.randomUUID().toString());
        abRun.setAgentId(candidate.getAgentId());
        // Baseline version id: candidate's recorded baseline if any, else
        // sentinel "" (V1 only supports with_vs_without where baseline = "no
        // candidate rule injected", not a separate persisted version).
        abRun.setBaselineVersionId(candidate.getBaselineVersionId() == null
                ? "" : candidate.getBaselineVersionId());
        abRun.setCandidateVersionId(candidateVersionId);
        abRun.setStatus(BehaviorRuleAbRunEntity.STATUS_PENDING);
        // r2-BE-2: do NOT set startedAt here. RUNNING is the meaningful transition
        // for that timestamp (set in runAsync). PENDING rows are pre-execution;
        // FE renders startedAt as nullable. Note the BehaviorRuleAbRunEntity
        // @PrePersist defaults null → Instant.now() (V82-era column is NOT NULL);
        // tightening to true-null PENDING requires a V117 NULLABILITY migration
        // — backlog item, not in r2 scope. The user-facing improvement here is
        // removing the explicit double-write code smell flagged in r1 review.
        abRun.setDatasetVersionId(datasetVersionId);
        abRun.setAbRunKind(BehaviorRuleAbRunEntity.KIND_WITH_VS_WITHOUT);
        abRunRepository.save(abRun);

        // ★ r1-FIX (afterCommit race): mirror SkillAbEvalService R4 pattern.
        // We're inside @Transactional; submitting to executor here can race
        // outer tx commit → runAsync's findById returns empty → row stays
        // PENDING. Register afterCommit hook so submit happens AFTER commit.
        final String abRunId = abRun.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            coordinatorExecutor.execute(() -> runAsync(abRunId));
                        }
                    });
        } else {
            // No active tx (e.g. direct test invocation) → safe to submit immediately.
            coordinatorExecutor.execute(() -> runAsync(abRunId));
        }
        return abRunId;
    }

    /**
     * Async runner — looks up the abRun, runs both sides via
     * {@link AbEvalPipeline#runWithExplicitDefs}, computes dual-criteria
     * deltas, persists results. Package-private for unit-test access (Mockito
     * can spy this directly without reflection).
     */
    void runAsync(String abRunId) {
        BehaviorRuleAbRunEntity abRun = abRunRepository.findById(abRunId).orElse(null);
        if (abRun == null) {
            log.warn("[BehaviorRuleAb] runAsync: abRun not found id={} (possible race or already deleted)",
                    abRunId);
            return;
        }
        try {
            abRun.setStatus(BehaviorRuleAbRunEntity.STATUS_RUNNING);
            abRun.setStartedAt(Instant.now());
            abRunRepository.save(abRun);
            broadcastStage(abRun, "ab_running", null);

            BehaviorRuleVersionEntity candidate = versionRepository.findById(abRun.getCandidateVersionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Candidate version disappeared mid-run: " + abRun.getCandidateVersionId()));
            AgentEntity agent = agentRepository.findById(Long.valueOf(candidate.getAgentId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Agent not found: " + candidate.getAgentId()));
            AgentDefinition baseDef = agentService.toAgentDefinition(agent);

            // FLYWHEEL-AB-AGENT-AWARE-DATASET V1: replace the V1
            // candidate.target_trigger_tags-based split with an agent-role-
            // aware subset (target = scenarios matching the rule_owner_agent's
            // role, regression = 'general' minus target ids). candidate's
            // targetTriggerTags field is intentionally retained on the entity
            // but no longer consulted here — see field-level @Deprecated note
            // for V2 cleanup path.
            String ownerRole = agentRoleResolver.resolveRole(agent);

            List<EvalScenarioEntity> targetSubset;
            List<EvalScenarioEntity> regressionSubset;
            if (AgentRoleConstants.GENERAL.equals(ownerRole)) {
                // UC-4 (prd.md): owner role is GENERAL → fallback to regression-
                // only mode over the full dataset (matches BEHAVIOR-RULE-AB-EVAL
                // V1 fallback semantics).
                targetSubset = List.of();
                regressionSubset = scenarioRepository.findAllByDatasetVersionId(abRun.getDatasetVersionId());
                log.info("[BehaviorRuleAb] ownerRole=general for versionId={} agentId={} → "
                                + "regression-only mode (full dataset, {} scenarios)",
                        candidate.getId(), candidate.getAgentId(), regressionSubset.size());
            } else {
                targetSubset = scenarioRepository.findByDatasetVersionAndAgentRoles(
                        abRun.getDatasetVersionId(), new String[]{ownerRole});
                Set<String> targetIds = targetSubset.stream()
                        .map(EvalScenarioEntity::getId)
                        .collect(Collectors.toSet());
                if (targetIds.isEmpty()) {
                    // No scenarios match this role → regression-only fallback
                    // (full dataset). Keeps the run informative even before
                    // role-specific scenarios are seeded for a new agent.
                    regressionSubset = scenarioRepository.findAllByDatasetVersionId(
                            abRun.getDatasetVersionId());
                    log.info("[BehaviorRuleAb] no scenarios match ownerRole={} for versionId={} "
                                    + "agentId={} → regression-only mode (full dataset, {} scenarios)",
                            ownerRole, candidate.getId(), candidate.getAgentId(),
                            regressionSubset.size());
                } else {
                    // r1-FIX (architect B2 / database W2): in-Java filter over
                    // the GENERAL subset replaces a native NOT IN (:excludeIds)
                    // query to dodge Hibernate 6 + Spring Data JPA
                    // Collection<String> NOT IN binding footguns. V1 scale
                    // ≤49 scenarios; O(n) filter is trivial. V2 ≥1000 scale
                    // can promote to CTE / NOT EXISTS server-side filter.
                    List<EvalScenarioEntity> generalAll = scenarioRepository
                            .findByDatasetVersionAndAgentRoles(
                                    abRun.getDatasetVersionId(),
                                    new String[]{AgentRoleConstants.GENERAL});
                    regressionSubset = generalAll.stream()
                            .filter(s -> !targetIds.contains(s.getId()))
                            .toList();
                    log.info("[BehaviorRuleAb] role-aware subset: ownerRole={} target_n={} "
                                    + "regression_n={} (versionId={} agentId={})",
                            ownerRole, targetSubset.size(), regressionSubset.size(),
                            candidate.getId(), candidate.getAgentId());
                }
            }
            abRun.setTargetCount(targetSubset.size());
            abRun.setRegressionCount(regressionSubset.size());

            AgentDefinition baselineDef  = stripCandidateRule(baseDef, candidate);
            AgentDefinition candidateDef = injectCandidateRule(baseDef, candidate);

            // Combine subsets for one batch — order matters for the per-subset
            // delta math below (targetSubset first, then regressionSubset).
            List<EvalScenarioEntity> all = new ArrayList<>(targetSubset.size() + regressionSubset.size());
            all.addAll(targetSubset);
            all.addAll(regressionSubset);
            if (all.isEmpty()) {
                throw new IllegalStateException(
                        "No scenarios resolved for datasetVersionId=" + abRun.getDatasetVersionId());
            }

            List<AbScenarioResult> perScenario = abEvalPipeline.runWithExplicitDefs(
                    abRun.getId(), all, baselineDef, candidateDef);

            // Per-subset pass rates. Build id sets for O(1) membership.
            Set<String> targetIds = new HashSet<>();
            for (EvalScenarioEntity e : targetSubset) targetIds.add(e.getId());
            Set<String> regressionIds = new HashSet<>();
            for (EvalScenarioEntity e : regressionSubset) regressionIds.add(e.getId());

            double targetBaseline    = passRateOf(perScenario, targetIds, Side.BASELINE);
            double targetCandidate   = passRateOf(perScenario, targetIds, Side.CANDIDATE);
            double regressionBaseline  = passRateOf(perScenario, regressionIds, Side.BASELINE);
            double regressionCandidate = passRateOf(perScenario, regressionIds, Side.CANDIDATE);

            // INV-4: fallback mode → targetDeltaPp = null. Regression delta
            // computed over full dataset (which is also regressionSubset here).
            abRun.setTargetDeltaPp(targetSubset.isEmpty() ? null
                    : (targetCandidate - targetBaseline));
            abRun.setRegressionDeltaPp(regressionCandidate - regressionBaseline);

            // Legacy global pass rates (weighted by subset sizes) for FE
            // backwards-compat (delta_pass_rate column was V4-era single delta).
            double globalBaseline = weightedAvg(
                    targetBaseline, targetSubset.size(),
                    regressionBaseline, regressionSubset.size());
            double globalCandidate = weightedAvg(
                    targetCandidate, targetSubset.size(),
                    regressionCandidate, regressionSubset.size());
            abRun.setBaselinePassRate(globalBaseline);
            abRun.setCandidatePassRate(globalCandidate);
            abRun.setDeltaPassRate(globalCandidate - globalBaseline);

            try {
                abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(perScenario));
            } catch (JsonProcessingException ex) {
                // Best-effort: a serialization failure shouldn't kill an
                // otherwise-successful A/B run. FE will see null results JSON
                // and fall back to per-rate columns.
                log.warn("[BehaviorRuleAb] failed to serialize perScenario results: {}", ex.getMessage());
            }
            abRun.setStatus(BehaviorRuleAbRunEntity.STATUS_COMPLETED);
            abRun.setCompletedAt(Instant.now());
            abRunRepository.save(abRun);
            broadcastStage(abRun, "ab_completed", null);

            log.info("[BehaviorRuleAb] COMPLETED abRunId={} target_delta={} regression_delta={} "
                            + "global_delta={} targetCount={} regressionCount={}",
                    abRun.getId(), abRun.getTargetDeltaPp(), abRun.getRegressionDeltaPp(),
                    abRun.getDeltaPassRate(), abRun.getTargetCount(), abRun.getRegressionCount());
        } catch (Exception ex) {
            log.error("[BehaviorRuleAb] FAILED abRunId={}: {}", abRunId, ex.getMessage(), ex);
            abRun.setStatus(BehaviorRuleAbRunEntity.STATUS_FAILED);
            abRun.setFailureReason(truncate(ex.getMessage(), 2000));
            abRun.setCompletedAt(Instant.now());
            try {
                abRunRepository.save(abRun);
            } catch (Exception saveEx) {
                log.error("[BehaviorRuleAb] failed to persist FAILED state: {}", saveEx.getMessage());
            }
            broadcastStage(abRun, "ab_failed", ex.getMessage());
        }
    }

    /** AGENT_DEF deep-clone via JSON roundtrip — safer than shallow clone in
     *  the presence of mutable BehaviorRulesConfig nesting. r1-FIX (architect):
     *  shallow copy would share the BehaviorRulesConfig instance between
     *  stripCandidate + injectCandidate → mutual pollution. */
    private AgentDefinition cloneDef(AgentDefinition src) {
        try {
            String json = objectMapper.writeValueAsString(src);
            return objectMapper.readValue(json, AgentDefinition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("AgentDefinition deep-clone failed", ex);
        }
    }

    /** Build the baseline AgentDefinition: ensure no rule whose text matches
     *  the candidate's rule texts is present. The base agent normally doesn't
     *  carry the candidate (it's not yet promoted), but we belt-and-suspenders
     *  filter anyway so INV-2 (baseline truly without candidate) holds even
     *  if state drifted. */
    AgentDefinition stripCandidateRule(AgentDefinition base, BehaviorRuleVersionEntity v) {
        AgentDefinition copy = cloneDef(base);
        BehaviorRulesConfig rules = copy.getBehaviorRules();
        if (rules == null) {
            return copy;
        }
        List<CustomRule> candRules = rulesMapper.toCustomRules(v.getRulesJson());
        Set<String> candTexts = new HashSet<>();
        for (CustomRule r : candRules) {
            if (r != null && r.getText() != null) candTexts.add(r.getText());
        }
        List<CustomRule> existing = rules.getCustomRules() == null
                ? List.of()
                : rules.getCustomRules();
        List<CustomRule> filtered = new ArrayList<>(existing.size());
        for (CustomRule r : existing) {
            if (r == null || !candTexts.contains(r.getText())) {
                filtered.add(r);
            }
        }
        rules.setCustomRules(filtered);
        return copy;
    }

    /** Build the candidate AgentDefinition: append the candidate's rules. */
    AgentDefinition injectCandidateRule(AgentDefinition base, BehaviorRuleVersionEntity v) {
        AgentDefinition copy = cloneDef(base);
        BehaviorRulesConfig rules = copy.getBehaviorRules();
        if (rules == null) {
            rules = new BehaviorRulesConfig();
            copy.setBehaviorRules(rules);
        }
        List<CustomRule> combined = new ArrayList<>();
        if (rules.getCustomRules() != null) {
            combined.addAll(rules.getCustomRules());
        }
        combined.addAll(rulesMapper.toCustomRules(v.getRulesJson()));
        rules.setCustomRules(combined);
        return copy;
    }

    /** Side enum — replaces boolean isCandidate parameter (r1-FIX NIT). */
    private enum Side { BASELINE, CANDIDATE }

    /** Pass rate of {@code side} over the subset {@code ids}. Returns
     *  0.0 when {@code ids} is empty (caller treats this as "no signal"). */
    private double passRateOf(List<AbScenarioResult> results, Set<String> ids, Side side) {
        if (ids.isEmpty()) {
            return 0.0;
        }
        int passed = 0;
        int total = 0;
        for (AbScenarioResult r : results) {
            if (!ids.contains(r.scenarioId())) continue;
            total++;
            AbScenarioResult.RunResult run = side == Side.BASELINE ? r.baseline() : r.candidate();
            if (run != null && isPass(run)) {
                passed++;
            }
        }
        return total == 0 ? 0.0 : (double) passed / total * 100.0;
    }

    /** A scenario "passes" if its judge composite score crosses 0.5 (matches
     *  EvalJudgeOutput.isPass logic) AND the run did not error. Mirrors
     *  AbEvalPipeline pass-counting semantics. */
    private boolean isPass(AbScenarioResult.RunResult run) {
        if (run.status() == null || "ERROR".equals(run.status()) || "TIMEOUT".equals(run.status())) {
            return false;
        }
        return run.oracleScore() >= 0.5;
    }

    /** Weighted average; zero-weight subsets are excluded. */
    private double weightedAvg(double a, int wa, double b, int wb) {
        if (wa <= 0 && wb <= 0) return 0.0;
        if (wa <= 0) return b;
        if (wb <= 0) return a;
        return (a * wa + b * wb) / (double) (wa + wb);
    }

    /**
     * BEHAVIOR-RULE-AB-EVAL V1 — FE-BE contract C2 (locked-in shape):
     * <pre>
     * {
     *   "type":              "behavior_rule_ab_run_updated",
     *   "event":             "ab_running" | "ab_completed" | "ab_failed",
     *   "abRunId":           "<uuid>",
     *   "candidateVersionId":"<uuid>",
     *   "status":            "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "SUPERSEDED"
     * }
     * </pre>
     *
     * <p>Routed via {@link ChatEventBroadcaster#userEvent} — A/B is async with
     * no session scope, so the user channel is the only viable fan-out target.
     * Falls back to a no-op (with diagnostic log at DEBUG) when
     * {@code triggeredByUserId} is null (e.g. auto-trigger path that hasn't
     * resolved the trigging operator yet) — the FE can recover by polling
     * {@code GET /latest-ab-run}, so silent skip is acceptable.
     */
    private void broadcastStage(BehaviorRuleAbRunEntity abRun, String event, String error) {
        if (broadcaster == null) return;
        try {
            // LinkedHashMap so the JSON field order matches the contract above
            // (cosmetic, but easier for FE devs eyeballing payloads).
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "behavior_rule_ab_run_updated");
            payload.put("event", event);
            payload.put("abRunId", abRun.getId());
            payload.put("candidateVersionId", abRun.getCandidateVersionId());
            payload.put("status", abRun.getStatus());
            if (error != null) payload.put("error", error);
            if (abRun.getTriggeredByUserId() != null) {
                broadcaster.userEvent(abRun.getTriggeredByUserId(), payload);
            } else {
                log.debug("[BehaviorRuleAb] broadcast skipped (no triggeredByUserId) "
                        + "event={} abRunId={} — FE will see via polling /latest-ab-run",
                        event, abRun.getId());
            }
        } catch (Exception ex) {
            log.warn("[BehaviorRuleAb] broadcast failed (non-fatal) event={} abRunId={}: {}",
                    event, abRun.getId(), ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
