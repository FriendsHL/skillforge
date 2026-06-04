package com.skillforge.server.improve.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.improve.behavior.AgentRoleConstants;
import com.skillforge.server.improve.behavior.AgentRoleResolver;
import com.skillforge.server.repository.AgentEvolveAbRunRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 (§2.3) — orchestrates a whole-agent A/B
 * run (路 B / 指针元组 / 一个整体分). Mirrors {@code BehaviorRuleAbEvalService}'s
 * afterCommit-defer + supersede skeleton (§7 W5) but keeps {@link #runAsync} thin:
 * the score arithmetic lives in the pure {@link #computeDeltas} function.
 *
 * <p>Flow: {@link #startAgentAb} persists a PENDING {@link AgentEvolveAbRunEntity}
 * and defers {@link #runAsync} to after the outer tx commits (without the defer,
 * the coordinator thread races the commit → {@code findById} returns empty → row
 * stuck PENDING). {@code runAsync} applies the bundles → baseline/candidate defs →
 * {@link AbEvalPipeline#runWithDefs} (explain=true to match the prompt path, §7 B1)
 * → aggregates a single pass-rate → persists + broadcasts.
 *
 * <p><b>§7 W1 winner-carry-forward consistency</b>: a {@code skip_baseline} run
 * (non-null {@code cachedBaselineRate}) must prove the supplied baseline bundle
 * structurally equals the prior winner run's candidate bundle before trusting the
 * cached rate — otherwise the cached score could be attached to a never-measured
 * tuple. The check fails loud (no silent fallback).
 */
@Service
public class AgentEvolveAbEvalService {

    private static final Logger log = LoggerFactory.getLogger(AgentEvolveAbEvalService.class);

    /**
     * §8 子点② — vs-best regression floor (advisory wouldPromote): the candidate may
     * regress on the general/regression subset by at most this many percentage points
     * vs the current best. Hill-climb is incremental (vs best), so this is NOT the
     * behavior_rule +10pp absolute (vs-original) gate. Read by {@code GetAbResultTool}.
     */
    public static final double REGRESSION_FLOOR_PP = -3.0;

    /**
     * §8 子点② — vs-best target floor (advisory wouldPromote): the candidate must beat
     * the current best on the target subset by more than this. Strictly-positive
     * incremental gate (a candidate that merely ties best on its own role isn't kept).
     */
    public static final double TARGET_MIN_DELTA_PP = 0.0;

    private final AgentEvolveAbRunRepository abRunRepository;
    private final AgentRepository agentRepository;
    private final BundleApplicator bundleApplicator;
    private final AbEvalPipeline abEvalPipeline;
    private final EvalScenarioDraftRepository scenarioRepository;
    private final AgentRoleResolver agentRoleResolver;
    private final EvalDatasetService evalDatasetService;
    private final ChatEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final ExecutorService coordinatorExecutor;

    public AgentEvolveAbEvalService(
            AgentEvolveAbRunRepository abRunRepository,
            AgentRepository agentRepository,
            BundleApplicator bundleApplicator,
            AbEvalPipeline abEvalPipeline,
            EvalScenarioDraftRepository scenarioRepository,
            AgentRoleResolver agentRoleResolver,
            EvalDatasetService evalDatasetService,
            ChatEventBroadcaster broadcaster,
            ObjectMapper objectMapper,
            @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor) {
        this.abRunRepository = abRunRepository;
        this.agentRepository = agentRepository;
        this.bundleApplicator = bundleApplicator;
        this.abEvalPipeline = abEvalPipeline;
        this.scenarioRepository = scenarioRepository;
        this.agentRoleResolver = agentRoleResolver;
        this.evalDatasetService = evalDatasetService;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
        this.coordinatorExecutor = coordinatorExecutor;
    }

    /**
     * Persist a PENDING whole-agent A/B run and defer {@link #runAsync} to after
     * the outer transaction commits.
     *
     * @param candidateBundle    the candidate pointer tuple (must be non-null)
     * @param baselineBundle     the baseline / current-best pointer tuple (non-null)
     * @param agentId            the target agent (numeric id as String)
     * @param datasetVersionId   the immutable dataset snapshot to score on
     * @param cachedBaselineRate non-null (0..100) → CANDIDATE-ONLY winner-carry-forward;
     *                           null → measure both sides fresh
     * @return abRunId of the newly-created row
     */
    @Transactional
    public String startAgentAb(Bundle candidateBundle, Bundle baselineBundle, String agentId,
                               String datasetVersionId, Double cachedBaselineRate) {
        return startAgentAb(candidateBundle, baselineBundle, agentId, datasetVersionId,
                cachedBaselineRate, null);
    }

    /**
     * BC-M2b (§ explicit scenario-id target split, restores P2-b G1): when
     * {@code explicitTargetScenarioIds} is non-empty those ids form the TARGET
     * subset (the harvested bad-case scenarios) and the rest of the dataset is the
     * GENERAL (benchmark / regression) subset; when null/empty the run falls back
     * to the existing role-based split. Both subsets are always measured in the
     * same A/B (the run covers target ∪ general), so a "do nothing" candidate is
     * caught by the general subset losing benchmark score.
     *
     * <p>The explicit ids are NOT persisted on the run row — they are captured into
     * the deferred {@link #runAsync} call (in-memory). A crash before the deferred
     * run loses the whole PENDING run regardless (no resume path), so persisting
     * them would buy nothing.
     */
    @Transactional
    public String startAgentAb(Bundle candidateBundle, Bundle baselineBundle, String agentId,
                               String datasetVersionId, Double cachedBaselineRate,
                               List<String> explicitTargetScenarioIds) {
        if (candidateBundle == null) {
            throw new IllegalArgumentException("candidateBundle is required");
        }
        if (baselineBundle == null) {
            throw new IllegalArgumentException("baselineBundle is required");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        // B1 (Phase 3 review): the orchestrator (and the agent-surface TriggerAbEval)
        // need not supply a dataset version — default-resolve the agent's default
        // dataset (mirrors BehaviorRuleAbEvalService). Resolving from the SAME agent
        // default keeps it stable across rounds, which the §8 子点① same-dataset-
        // across-rounds invariant relies on. Fail loud only if none can be resolved.
        final String resolvedDatasetVersionId =
                (datasetVersionId != null && !datasetVersionId.isBlank())
                        ? datasetVersionId
                        : evalDatasetService.findDefaultVersionIdForAgent(agentId);
        if (resolvedDatasetVersionId == null || resolvedDatasetVersionId.isBlank()) {
            throw new IllegalStateException(
                    "No dataset version available for agent " + agentId
                            + " (none supplied and no agent default configured)");
        }
        // BUG-1 guard: an out-of-range cached rate would silently make every
        // candidate "lose" — fail fast (mirrors AbEvalRunRequest).
        if (cachedBaselineRate != null && (cachedBaselineRate < 0.0 || cachedBaselineRate > 100.0)) {
            throw new IllegalArgumentException(
                    "cachedBaselineRate must be in [0, 100] when non-null, got: " + cachedBaselineRate);
        }

        String candidateBundleJson = writeBundle(candidateBundle);
        String baselineBundleJson = writeBundle(baselineBundle);
        final boolean skipBaseline = cachedBaselineRate != null;

        // §7 W1: trust the cached rate ONLY if the incoming baseline bundle is the
        // prior winner's candidate bundle. No prior winner / mismatch → fail loud.
        String priorWinnerAbRunId = null;
        if (skipBaseline) {
            AgentEvolveAbRunEntity priorWinner = abRunRepository
                    .findFirstByAgentIdAndStatusOrderByCompletedAtDesc(
                            agentId, AgentEvolveAbRunEntity.STATUS_COMPLETED)
                    .orElseThrow(() -> new IllegalStateException(
                            "skip_baseline requested (cachedBaselineRate=" + cachedBaselineRate
                                    + ") but no prior COMPLETED agent A/B run exists for agent " + agentId
                                    + " to source the cached rate from"));
            Bundle priorCandidate = readBundle(priorWinner.getCandidateBundleJson());
            if (!baselineBundle.equals(priorCandidate)) {
                throw new IllegalStateException(
                        "skip_baseline cached-rate consistency violation (§7 W1): incoming baseline bundle "
                                + baselineBundle + " does not match prior winner (abRunId="
                                + priorWinner.getId() + ") candidate bundle " + priorCandidate
                                + " — refusing to attach cached rate to a never-measured tuple");
            }
            // §8 子点① guard: the cached per-subset baseline is recomputed by
            // partitioning the prior winner's per-scenario results with THIS run's
            // (dataset+role) id-sets. If the dataset version differs between the
            // winner run and now, that partition is over a mismatched scenario set →
            // biased baseline → false wouldPromote. Fail loud.
            if (!resolvedDatasetVersionId.equals(priorWinner.getDatasetVersionId())) {
                throw new IllegalStateException(
                        "skip_baseline cached-rate dataset mismatch (§8 子点①): this run's datasetVersionId="
                                + resolvedDatasetVersionId + " but prior winner (abRunId=" + priorWinner.getId()
                                + ") was scored on datasetVersionId=" + priorWinner.getDatasetVersionId()
                                + " — cannot reuse its per-subset rates across dataset versions");
            }
            priorWinnerAbRunId = priorWinner.getId();
        }

        // §7 W5 supersede-dedup skeleton: at most one in-flight run per
        // (agent, candidate bundle).
        abRunRepository.findFirstByAgentIdAndCandidateBundleJsonAndStatusInOrderByStartedAtDesc(
                        agentId, candidateBundleJson,
                        List.of(AgentEvolveAbRunEntity.STATUS_PENDING,
                                AgentEvolveAbRunEntity.STATUS_RUNNING))
                .ifPresent(prior -> {
                    prior.setStatus(AgentEvolveAbRunEntity.STATUS_SUPERSEDED);
                    prior.setCompletedAt(Instant.now());
                    abRunRepository.save(prior);
                    log.info("[AgentEvolveAb] superseded prior in-flight run id={} for agentId={}",
                            prior.getId(), agentId);
                });

        AgentEvolveAbRunEntity abRun = new AgentEvolveAbRunEntity();
        abRun.setId(UUID.randomUUID().toString());
        abRun.setAgentId(agentId);
        abRun.setCandidateBundleJson(candidateBundleJson);
        abRun.setBaselineBundleJson(baselineBundleJson);
        abRun.setDatasetVersionId(resolvedDatasetVersionId);
        abRun.setSkipBaseline(skipBaseline);
        abRun.setCachedBaselineRate(cachedBaselineRate);
        abRun.setPriorWinnerAbRunId(priorWinnerAbRunId);
        abRun.setStatus(AgentEvolveAbRunEntity.STATUS_PENDING);
        abRunRepository.save(abRun);

        final String abRunId = abRun.getId();
        // Defensive immutable copy captured by the deferred runAsync (in-memory; not
        // persisted — see method javadoc). null → role-based split fallback.
        final List<String> targetIds = (explicitTargetScenarioIds == null || explicitTargetScenarioIds.isEmpty())
                ? null : List.copyOf(explicitTargetScenarioIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            coordinatorExecutor.execute(() -> runAsync(abRunId, targetIds));
                        }
                    });
        } else {
            coordinatorExecutor.execute(() -> runAsync(abRunId, targetIds));
        }
        return abRunId;
    }

    /**
     * Async runner — applies the bundles, runs the whole-agent A/B, aggregates a
     * single pass-rate, persists results. Package-private for direct unit-test
     * invocation.
     */
    void runAsync(String abRunId) {
        runAsync(abRunId, null);
    }

    /**
     * Async runner with an optional explicit target scenario-id set (BC-M2b). When
     * non-null/non-empty it drives the target/general split; null falls back to the
     * role-based split. Package-private for direct unit-test invocation.
     */
    void runAsync(String abRunId, List<String> explicitTargetScenarioIds) {
        AgentEvolveAbRunEntity abRun = abRunRepository.findById(abRunId).orElse(null);
        if (abRun == null) {
            log.warn("[AgentEvolveAb] runAsync: abRun not found id={} (possible race or deleted)", abRunId);
            return;
        }
        try {
            abRun.setStatus(AgentEvolveAbRunEntity.STATUS_RUNNING);
            abRun.setStartedAt(Instant.now());
            abRunRepository.save(abRun);
            broadcastStage(abRun, "ab_running", null);

            Bundle candidateBundle = readBundle(abRun.getCandidateBundleJson());
            Bundle baselineBundle = readBundle(abRun.getBaselineBundleJson());
            Double cachedBaselineRate = abRun.getCachedBaselineRate();
            boolean skipBaseline = abRun.isSkipBaseline();

            AgentEntity agent = agentRepository.findById(Long.valueOf(abRun.getAgentId()))
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + abRun.getAgentId()));

            BundleApplicator.ApplyResult candidate = bundleApplicator.apply(agent, candidateBundle);
            AgentDefinition candidateDef = candidate.def();
            // Phase 4 (§10 #5): the candidate's in-memory skill defs must be injected
            // into the eval sandbox registry (candidate side only). The BASELINE arm
            // gets NO extra skills: it only actually RUNS in the fresh round
            // (cachedBaselineRate == null) where best = the original agent (all-null
            // bundle, no skillDraftId); carry-forward rounds skip the baseline arm
            // entirely, so the carried-forward skill (if any) lives on the candidate
            // side via candidateBundle.skillDraftId.
            List<com.skillforge.core.model.SkillDefinition> candidateExtraSkills = candidate.extraSkills();
            // When the baseline side is skipped its def is never executed; pass the
            // candidate def as a harmless non-null placeholder (runWithDefs ignores
            // the baseline def's run but still copies eval-overrides off it).
            AgentDefinition baselineDef = skipBaseline
                    ? candidateDef
                    : bundleApplicator.apply(agent, baselineBundle).def();

            // §8 R2 — role-aware target/general split keyed on the EVOLVED agent
            // (transplanted from BehaviorRuleAbEvalService.runAsync; the agent-level
            // candidate's owner IS the evolved agent, so W8's "no single owner"
            // concern doesn't apply). target = scenarios for this agent's role;
            // general = the rest (regression benchmark). GENERAL / no-match →
            // regression-only over the full dataset.
            RoleSplit split = resolveRoleSplit(agent, abRun.getDatasetVersionId(), explicitTargetScenarioIds);
            if (split.all().isEmpty()) {
                throw new IllegalStateException(
                        "No scenarios resolved for datasetVersionId=" + abRun.getDatasetVersionId());
            }

            // EVAL-429-ROBUSTNESS: load the prior winner's per-scenario results BEFORE
            // running the A/B, so computeDeltas can do true cross-round pairwise (a
            // scenario is measured only when BOTH this round's candidate AND the prior
            // winner's candidate were non-infra). Loaded + coverage-checked up front so
            // a stale/partial prior winner fails fast before the expensive eval.
            // §8 子点① guard (catches a role-flip that a dataset-version check alone
            // misses): the prior winner's measured scenario set MUST cover the current
            // target ∪ general union, else we'd compute a partial / biased baseline.
            List<AbScenarioResult> priorWinnerPerScenario = null;
            if (skipBaseline) {
                priorWinnerPerScenario = loadPriorWinnerPerScenario(abRun.getPriorWinnerAbRunId());
                assertPriorWinnerCoversSubsets(
                        priorWinnerPerScenario, split.targetIds(), split.generalIds(),
                        abRun.getPriorWinnerAbRunId());
            }

            // explain=true → byte-identical to the prompt path's judge call (§7 B1).
            // candidateExtraSkills injected into the CANDIDATE-side sandbox only (§10 #5).
            List<AbScenarioResult> perScenario = abEvalPipeline.runWithDefs(
                    abRun.getId(), split.all(), baselineDef, candidateDef, cachedBaselineRate, true,
                    candidateExtraSkills);

            // Global rates (Phase 1 semantics; trajectory chart uses these). The skip
            // path passes priorWinnerPerScenario so the baseline rate is the prior
            // winner's candidate-side measurement over the cross-round measured
            // intersection (D2 pairwise) — null fields when nothing was measured (D3).
            AgentAbDeltas deltas = computeDeltas(
                    perScenario, cachedBaselineRate, skipBaseline ? priorWinnerPerScenario : null);
            abRun.setBaselinePassRate(deltas.baselinePassRate());
            abRun.setCandidatePassRate(deltas.candidatePassRate());
            abRun.setDeltaPassRate(deltas.deltaPassRate());

            // §8 子点① + ② — per-subset vs-best deltas. When skipBaseline, the best's
            // per-subset rates come from the PRIOR WINNER ab_run's candidate side,
            // partitioned by the SAME (dataset+role) id-sets — NOT re-run. Fresh round
            // → both arms ran, so best = this run's baseline side.
            SubsetDeltas subset = computeSubsetDeltas(
                    perScenario, priorWinnerPerScenario, split.targetIds(), split.generalIds());
            abRun.setTargetDeltaPp(subset.targetDeltaPp());
            abRun.setRegressionDeltaPp(subset.regressionDeltaPp());
            // item 4: absolute per-subset rates (orchestrator's vs-original anchor).
            abRun.setCandidateTargetRate(subset.candidateTargetRate());
            abRun.setCandidateGeneralRate(subset.candidateGeneralRate());
            abRun.setBaselineTargetRate(subset.baselineTargetRate());
            abRun.setBaselineGeneralRate(subset.baselineGeneralRate());

            try {
                abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(perScenario));
            } catch (JsonProcessingException ex) {
                log.warn("[AgentEvolveAb] failed to serialize perScenario results: {}", ex.getMessage());
            }
            abRun.setStatus(AgentEvolveAbRunEntity.STATUS_COMPLETED);
            abRun.setCompletedAt(Instant.now());
            abRunRepository.save(abRun);
            broadcastStage(abRun, "ab_completed", null);

            log.info("[AgentEvolveAb] COMPLETED abRunId={} baseline={} candidate={} delta={} "
                            + "targetDelta={} regressionDelta={} target_n={} general_n={} skipBaseline={}",
                    abRun.getId(), deltas.baselinePassRate(), deltas.candidatePassRate(),
                    deltas.deltaPassRate(), subset.targetDeltaPp(), subset.regressionDeltaPp(),
                    split.targetIds().size(), split.generalIds().size(), skipBaseline);
        } catch (Exception ex) {
            log.error("[AgentEvolveAb] FAILED abRunId={}: {}", abRunId, ex.getMessage(), ex);
            abRun.setStatus(AgentEvolveAbRunEntity.STATUS_FAILED);
            abRun.setFailureReason(truncate(ex.getMessage(), 2000));
            abRun.setCompletedAt(Instant.now());
            try {
                abRunRepository.save(abRun);
            } catch (Exception saveEx) {
                log.error("[AgentEvolveAb] failed to persist FAILED state: {}", saveEx.getMessage());
            }
            broadcastStage(abRun, "ab_failed", ex.getMessage());
        }
    }

    /**
     * §7 W5 — pure pass-rate arithmetic, extracted so {@link #runAsync} stays thin
     * and the math is unit-testable. Counts passes via the shared
     * {@link AbEvalPipeline#isPass(AbScenarioResult.RunResult)} predicate so the
     * whole-agent rate matches the prompt path's {@code judge.isPass()} counting
     * (§7 B1 numeric parity). When {@code cachedBaselineRate} is supplied the
     * baseline side is NOT counted from per-scenario results (it was skipped) — the
     * cached rate is used verbatim.
     */
    static AgentAbDeltas computeDeltas(List<AbScenarioResult> perScenario, Double cachedBaselineRate) {
        // 2-arg backward-compatible form: no prior-winner per-scenario (so the skip
        // path falls back to candidate-measured-only + verbatim cachedBaselineRate).
        return computeDeltas(perScenario, cachedBaselineRate, null);
    }

    /**
     * EVAL-429-ROBUSTNESS — pass-rate arithmetic over the MEASURED set only (infra
     * ERROR/TIMEOUT scenarios excluded from the denominator, D1/D2/D3). Three modes:
     * <ul>
     *   <li><b>skip + prior-winner pairwise</b> ({@code priorWinnerPerScenario != null}):
     *       a scenario is measured iff THIS run's candidate side AND the prior winner's
     *       candidate side (the cached baseline measurement) are both non-infra. Baseline
     *       passes are counted from the prior winner's candidate side over that
     *       intersection (true cross-round pairwise) — overrides the global cached rate.</li>
     *   <li><b>legacy skip</b> ({@code cachedBaselineRate != null}, no prior-winner):
     *       candidate-measured-only; the baseline rate is the verbatim cached rate.</li>
     *   <li><b>fresh</b> (both null): same-round pairwise — measured iff candidate AND
     *       baseline sides are both non-infra.</li>
     * </ul>
     * measured==0 → {@code AgentAbDeltas(null, null, null)} (D3 not-measured sentinel);
     * excluded scenarios are logged (D4).
     */
    static AgentAbDeltas computeDeltas(List<AbScenarioResult> perScenario, Double cachedBaselineRate,
                                       List<AbScenarioResult> priorWinnerPerScenario) {
        if (perScenario == null || perScenario.isEmpty()) {
            return new AgentAbDeltas(null, null, null);
        }
        Map<String, AbScenarioResult.RunResult> priorCandidate =
                indexCandidateSide(priorWinnerPerScenario);
        int measuredCount = 0;
        int candidatePassed = 0;
        int baselinePassed = 0;
        List<String> infraExcluded = new ArrayList<>();
        for (AbScenarioResult r : perScenario) {
            boolean measured;
            boolean countBaseline;
            boolean baselinePass;
            if (priorWinnerPerScenario != null) {
                // skip path, cross-round pairwise vs the prior winner's candidate side.
                AbScenarioResult.RunResult prior = priorCandidate.get(r.scenarioId());
                boolean priorMeasured = prior != null && AbEvalPipeline.isMeasured(prior.status());
                measured = AbEvalPipeline.isMeasured(r.candidate().status()) && priorMeasured;
                countBaseline = true;
                baselinePass = prior != null && AbEvalPipeline.isPass(prior);
            } else if (cachedBaselineRate != null) {
                // legacy skip fallback: candidate-measured-only; baseline = cached rate.
                measured = AbEvalPipeline.isMeasured(r.candidate().status());
                countBaseline = false;
                baselinePass = false;
            } else {
                // fresh: same-round pairwise.
                measured = AbEvalPipeline.pairwiseMeasured(
                        r.candidate().status(), r.baseline().status());
                countBaseline = true;
                baselinePass = AbEvalPipeline.isPass(r.baseline());
            }
            if (measured) {
                measuredCount++;
                if (AbEvalPipeline.isPass(r.candidate())) candidatePassed++;
                if (countBaseline && baselinePass) baselinePassed++;
            } else {
                infraExcluded.add(r.scenarioId());
            }
        }
        if (!infraExcluded.isEmpty()) {
            log.info("[AgentEvolveAb] computeDeltas excluded {} infra/not-measured scenario(s) "
                    + "from denominator: {}", infraExcluded.size(), infraExcluded);
        }
        if (measuredCount == 0) {
            return new AgentAbDeltas(null, null, null);
        }
        double candidateRate = (double) candidatePassed / measuredCount * 100.0;
        Double baselineRate;
        if (priorWinnerPerScenario != null) {
            baselineRate = (double) baselinePassed / measuredCount * 100.0;
        } else if (cachedBaselineRate != null) {
            baselineRate = cachedBaselineRate;
        } else {
            baselineRate = (double) baselinePassed / measuredCount * 100.0;
        }
        // baselineRate is always non-null here (all 3 branches above set it; the
        // measuredCount==0 early-return already handled the not-measured case). The
        // null-check is defensive — keeps the delta null-safe if a future branch leaves
        // baselineRate unset.
        Double deltaRate = baselineRate == null ? null : candidateRate - baselineRate;
        return new AgentAbDeltas(baselineRate, candidateRate, deltaRate);
    }

    /** Index a per-scenario result list by scenarioId → its candidate-side RunResult. */
    private static Map<String, AbScenarioResult.RunResult> indexCandidateSide(
            List<AbScenarioResult> perScenario) {
        if (perScenario == null) {
            return Map.of();
        }
        Map<String, AbScenarioResult.RunResult> map = new HashMap<>();
        for (AbScenarioResult r : perScenario) {
            map.put(r.scenarioId(), r.candidate());
        }
        return map;
    }

    /** Pure result of {@link #computeDeltas}; null fields = not-measured (D3 sentinel). */
    record AgentAbDeltas(Double baselinePassRate, Double candidatePassRate, Double deltaPassRate) {}

    /**
     * §8 R2 — role-aware target/general split of a dataset version keyed on the
     * EVOLVED agent's role. Mirrors {@code BehaviorRuleAbEvalService.runAsync}'s
     * split logic but the owner role is the evolved agent's (not a rule_owner_agent).
     * GENERAL role or no-target-match → regression-only (full dataset is general).
     */
    private RoleSplit resolveRoleSplit(AgentEntity agent, String datasetVersionId) {
        return resolveRoleSplit(agent, datasetVersionId, null);
    }

    /**
     * BC-M2b — split that prefers an explicit target scenario-id set over the
     * role-based split. When {@code explicitTargetScenarioIds} is non-empty:
     * target = those ids that exist in the dataset version, general = every other
     * dataset scenario. The dataset is still scored in full (target ∪ general =
     * the whole dataset version), so the benchmark/general subset is always
     * measured alongside the targeted bad-case subset (the do-nothing guard). When
     * null/empty it delegates to the role-based split.
     */
    private RoleSplit resolveRoleSplit(AgentEntity agent, String datasetVersionId,
                                       List<String> explicitTargetScenarioIds) {
        if (explicitTargetScenarioIds != null && !explicitTargetScenarioIds.isEmpty()) {
            Set<String> wanted = new HashSet<>(explicitTargetScenarioIds);
            List<EvalScenarioEntity> allScenarios =
                    scenarioRepository.findAllByDatasetVersionId(datasetVersionId);
            List<EvalScenarioEntity> targetSubset = new ArrayList<>();
            List<EvalScenarioEntity> regressionSubset = new ArrayList<>();
            for (EvalScenarioEntity e : allScenarios) {
                if (wanted.contains(e.getId())) {
                    targetSubset.add(e);
                } else {
                    regressionSubset.add(e);
                }
            }
            if (targetSubset.isEmpty()) {
                // None of the requested ids are in this dataset version — the targeted
                // bad case isn't part of the dataset, so there is nothing to target.
                // Fall back to general-only (still measures the benchmark) but warn loud.
                log.warn("[AgentEvolveAb] explicit target ids {} not found in datasetVersionId={} "
                        + "(agentId={}) → regression-only over {} scenarios",
                        wanted, datasetVersionId, agent.getId(), regressionSubset.size());
            } else {
                log.info("[AgentEvolveAb] explicit-id split target_n={} regression_n={} "
                        + "agentId={} datasetVersionId={}",
                        targetSubset.size(), regressionSubset.size(), agent.getId(), datasetVersionId);
            }
            Set<String> targetIds = new HashSet<>();
            for (EvalScenarioEntity e : targetSubset) targetIds.add(e.getId());
            Set<String> generalIds = new HashSet<>();
            for (EvalScenarioEntity e : regressionSubset) generalIds.add(e.getId());
            List<EvalScenarioEntity> all = new ArrayList<>(targetSubset.size() + regressionSubset.size());
            all.addAll(targetSubset);
            all.addAll(regressionSubset);
            return new RoleSplit(all, targetIds, generalIds);
        }
        String ownerRole = agentRoleResolver.resolveRole(agent);
        List<EvalScenarioEntity> targetSubset;
        List<EvalScenarioEntity> regressionSubset;
        if (AgentRoleConstants.GENERAL.equals(ownerRole)) {
            targetSubset = List.of();
            regressionSubset = scenarioRepository.findAllByDatasetVersionId(datasetVersionId);
            log.info("[AgentEvolveAb] ownerRole=general agentId={} → regression-only ({} scenarios)",
                    agent.getId(), regressionSubset.size());
        } else {
            targetSubset = scenarioRepository.findByDatasetVersionAndAgentRoles(
                    datasetVersionId, new String[]{ownerRole});
            Set<String> targetIds = new HashSet<>();
            for (EvalScenarioEntity e : targetSubset) targetIds.add(e.getId());
            if (targetIds.isEmpty()) {
                regressionSubset = scenarioRepository.findAllByDatasetVersionId(datasetVersionId);
                log.info("[AgentEvolveAb] no scenarios match ownerRole={} agentId={} → "
                        + "regression-only ({} scenarios)", ownerRole, agent.getId(), regressionSubset.size());
            } else {
                // In-Java filter over the GENERAL subset (matches behavior_rule
                // r1-FIX: dodges Hibernate NOT IN binding footguns; ≤49 scenarios).
                List<EvalScenarioEntity> generalAll = scenarioRepository.findByDatasetVersionAndAgentRoles(
                        datasetVersionId, new String[]{AgentRoleConstants.GENERAL});
                regressionSubset = generalAll.stream()
                        .filter(s -> !targetIds.contains(s.getId()))
                        .toList();
                log.info("[AgentEvolveAb] role-aware split ownerRole={} target_n={} regression_n={} agentId={}",
                        ownerRole, targetSubset.size(), regressionSubset.size(), agent.getId());
            }
        }
        Set<String> targetIds = new HashSet<>();
        for (EvalScenarioEntity e : targetSubset) targetIds.add(e.getId());
        Set<String> generalIds = new HashSet<>();
        for (EvalScenarioEntity e : regressionSubset) generalIds.add(e.getId());
        List<EvalScenarioEntity> all = new ArrayList<>(targetSubset.size() + regressionSubset.size());
        all.addAll(targetSubset);
        all.addAll(regressionSubset);
        return new RoleSplit(all, targetIds, generalIds);
    }

    /** target-first scenario list + the two id-sets for per-subset partitioning. */
    private record RoleSplit(List<EvalScenarioEntity> all, Set<String> targetIds, Set<String> generalIds) {}

    /** Parse a prior winner ab_run's per-scenario JSON (§8 子点① cached baseline source). */
    private List<AbScenarioResult> loadPriorWinnerPerScenario(String priorWinnerAbRunId) {
        if (priorWinnerAbRunId == null) {
            throw new IllegalStateException(
                    "skipBaseline run has no priorWinnerAbRunId — cannot recompute cached per-subset baseline");
        }
        AgentEvolveAbRunEntity prior = abRunRepository.findById(priorWinnerAbRunId)
                .orElseThrow(() -> new IllegalStateException(
                        "prior winner ab_run not found: " + priorWinnerAbRunId));
        String json = prior.getAbScenarioResultsJson();
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(
                    "prior winner ab_run " + priorWinnerAbRunId + " has no ab_scenario_results_json "
                            + "— cannot recompute cached per-subset baseline");
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AbScenarioResult>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "failed to parse prior winner ab_scenario_results_json (abRunId=" + priorWinnerAbRunId + ")", ex);
        }
    }

    /**
     * §8 子点① coverage guard — the prior winner's measured per-scenario set must
     * cover (⊇) the current run's {@code target ∪ general} id-union. A dataset-version
     * match (checked in {@code startAgentAb}) is necessary but not sufficient: an
     * agent rename can flip its resolved role, shifting which scenarios fall in
     * target vs general, so the winner's JSON may not contain a current id even on
     * the same dataset version. Partitioning over a mismatched set would bias the
     * cached baseline silently — fail loud instead.
     */
    static void assertPriorWinnerCoversSubsets(List<AbScenarioResult> priorWinnerPerScenario,
                                               Set<String> targetIds, Set<String> generalIds,
                                               String priorWinnerAbRunId) {
        Set<String> priorIds = new HashSet<>();
        for (AbScenarioResult r : priorWinnerPerScenario) {
            priorIds.add(r.scenarioId());
        }
        List<String> missing = new ArrayList<>();
        for (String id : targetIds) {
            if (!priorIds.contains(id)) missing.add(id);
        }
        for (String id : generalIds) {
            if (!priorIds.contains(id)) missing.add(id);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "skip_baseline cached-rate id-set mismatch (§8 子点①): prior winner (abRunId="
                            + priorWinnerAbRunId + ") per-scenario results do not cover the current "
                            + "target∪general subset — missing " + missing.size() + " scenario id(s) "
                            + missing + " (likely a dataset/role shift between the winner run and now); "
                            + "refusing to compute a partial per-subset baseline");
        }
    }

    /**
     * §8 子点② — pure per-subset vs-best delta computation. {@code currentRun} is
     * this A/B's per-scenario results (candidate side authoritative; baseline side
     * valid only when not skipping). {@code priorWinnerRun} is non-null ONLY on the
     * skip path = the prior winner's per-scenario results, whose CANDIDATE side is
     * the best's measurement (§8 子点①).
     *
     * <ul>
     *   <li>{@code targetDeltaPp = candidate_target − best_target} (null when no target subset)</li>
     *   <li>{@code regressionDeltaPp = candidate_general − best_general}</li>
     * </ul>
     */
    static SubsetDeltas computeSubsetDeltas(List<AbScenarioResult> currentRun,
                                            List<AbScenarioResult> priorWinnerRun,
                                            Set<String> targetIds,
                                            Set<String> generalIds) {
        // EVAL-429-ROBUSTNESS: each subset rate is computed over the pairwise-measured
        // intersection so candidate and best share the SAME scenarios per subset
        // (D2). skip path → cross-round pairwise vs the prior winner's candidate side;
        // fresh path → same-round pairwise (candidate vs baseline side).
        double candTarget = passRateOver(currentRun, targetIds, /*candidateSide*/ true, priorWinnerRun);
        double candGeneral = passRateOver(currentRun, generalIds, true, priorWinnerRun);
        double bestTarget;
        double bestGeneral;
        if (priorWinnerRun != null) {
            // skip path: best = the prior winner's CANDIDATE side measurement, gated on
            // THIS run's candidate side also being measured for that scenario.
            bestTarget = passRateOver(priorWinnerRun, targetIds, true, currentRun);
            bestGeneral = passRateOver(priorWinnerRun, generalIds, true, currentRun);
        } else {
            // fresh path: best = this run's BASELINE side (same-round pairwise).
            bestTarget = passRateOver(currentRun, targetIds, false, null);
            bestGeneral = passRateOver(currentRun, generalIds, false, null);
        }
        boolean hasTarget = !targetIds.isEmpty();
        boolean hasGeneral = !generalIds.isEmpty();
        // Absolute per-subset rates (item 4): null when the subset is empty so the
        // orchestrator can distinguish "0% measured" from "subset absent".
        Double candidateTargetRate = hasTarget ? candTarget : null;
        Double candidateGeneralRate = hasGeneral ? candGeneral : null;
        Double baselineTargetRate = hasTarget ? bestTarget : null;
        Double baselineGeneralRate = hasGeneral ? bestGeneral : null;
        Double targetDeltaPp = hasTarget ? (candTarget - bestTarget) : null;
        Double regressionDeltaPp = hasGeneral ? (candGeneral - bestGeneral) : null;
        return new SubsetDeltas(targetDeltaPp, regressionDeltaPp,
                candidateTargetRate, candidateGeneralRate, baselineTargetRate, baselineGeneralRate);
    }

    /**
     * Pure result of {@link #computeSubsetDeltas}; null = subset absent / no signal.
     * Carries both the vs-best deltas AND the 4 absolute subset rates (item 4) the
     * orchestrator needs for the vs-original general anchor.
     */
    record SubsetDeltas(Double targetDeltaPp, Double regressionDeltaPp,
                        Double candidateTargetRate, Double candidateGeneralRate,
                        Double baselineTargetRate, Double baselineGeneralRate) {}

    /**
     * 3-arg backward-compatible form (no cross-run counterpart) → same-round pairwise.
     */
    static double passRateOver(List<AbScenarioResult> results, Set<String> ids, boolean candidateSide) {
        return passRateOver(results, ids, candidateSide, null);
    }

    /**
     * EVAL-429-ROBUSTNESS — pass-rate (%) of {@code side} over the {@code ids} subset,
     * counting passes via the shared {@link AbEvalPipeline#isPass(AbScenarioResult.RunResult)}
     * predicate, but ONLY over scenarios that are pairwise-measured:
     * <ul>
     *   <li>{@code pairwiseWith == null} → same-round pairwise: the chosen {@code side}
     *       AND the OTHER side of the SAME result must both be non-infra.</li>
     *   <li>{@code pairwiseWith != null} → cross-round pairwise: the chosen {@code side}
     *       AND the counterpart run's candidate side for that scenarioId must both be
     *       non-infra (the cross-round measurement the skip path compares against).</li>
     * </ul>
     * A scenario whose either side is ERROR/TIMEOUT (or CACHED) is excluded from both
     * numerator and denominator. Returns 0.0 when {@code ids} is empty or nothing was
     * measured (callers gate the empty-subset case separately via {@code hasTarget}).
     */
    static double passRateOver(List<AbScenarioResult> results, Set<String> ids, boolean candidateSide,
                               List<AbScenarioResult> pairwiseWith) {
        if (results == null || ids == null || ids.isEmpty()) {
            return 0.0;
        }
        Map<String, AbScenarioResult.RunResult> counterpart =
                pairwiseWith == null ? null : indexCandidateSide(pairwiseWith);
        int passed = 0;
        int total = 0;
        for (AbScenarioResult r : results) {
            if (!ids.contains(r.scenarioId())) {
                continue;
            }
            AbScenarioResult.RunResult own = candidateSide ? r.candidate() : r.baseline();
            boolean otherMeasured;
            if (counterpart == null) {
                AbScenarioResult.RunResult otherSide = candidateSide ? r.baseline() : r.candidate();
                otherMeasured = AbEvalPipeline.isMeasured(otherSide.status());
            } else {
                AbScenarioResult.RunResult cp = counterpart.get(r.scenarioId());
                otherMeasured = cp != null && AbEvalPipeline.isMeasured(cp.status());
            }
            if (!(AbEvalPipeline.isMeasured(own.status()) && otherMeasured)) {
                continue;   // not pairwise-measured → excluded from this subset rate
            }
            total++;
            if (AbEvalPipeline.isPass(own)) {
                passed++;
            }
        }
        return total == 0 ? 0.0 : (double) passed / total * 100.0;
    }

    private String writeBundle(Bundle bundle) {
        try {
            return objectMapper.writeValueAsString(bundle);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize bundle: " + bundle, ex);
        }
    }

    private Bundle readBundle(String json) {
        try {
            return objectMapper.readValue(json, Bundle.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to parse bundle json: " + json, ex);
        }
    }

    /**
     * FE-BE contract (mirrors behavior_rule's broadcast shape):
     * {@code {type:"agent_evolve_ab_run_updated", event, abRunId, agentId, status}}.
     * No-op when {@code triggeredByUserId} is null (Phase 1 manual/script trigger
     * may not resolve an operator) — FE recovers by polling.
     */
    private void broadcastStage(AgentEvolveAbRunEntity abRun, String event, String error) {
        if (broadcaster == null || abRun.getTriggeredByUserId() == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "agent_evolve_ab_run_updated");
            payload.put("event", event);
            payload.put("abRunId", abRun.getId());
            payload.put("agentId", abRun.getAgentId());
            payload.put("status", abRun.getStatus());
            if (error != null) {
                payload.put("error", error);
            }
            broadcaster.userEvent(abRun.getTriggeredByUserId(), payload);
        } catch (Exception ex) {
            log.warn("[AgentEvolveAb] broadcast failed (non-fatal) event={} abRunId={}: {}",
                    event, abRun.getId(), ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
