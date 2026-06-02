package com.skillforge.server.improve.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.repository.AgentEvolveAbRunRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.EvalDatasetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final AgentEvolveAbRunRepository abRunRepository;
    private final AgentRepository agentRepository;
    private final BundleApplicator bundleApplicator;
    private final AbEvalPipeline abEvalPipeline;
    private final EvalDatasetService evalDatasetService;
    private final ChatEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final ExecutorService coordinatorExecutor;

    public AgentEvolveAbEvalService(
            AgentEvolveAbRunRepository abRunRepository,
            AgentRepository agentRepository,
            BundleApplicator bundleApplicator,
            AbEvalPipeline abEvalPipeline,
            EvalDatasetService evalDatasetService,
            ChatEventBroadcaster broadcaster,
            ObjectMapper objectMapper,
            @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor) {
        this.abRunRepository = abRunRepository;
        this.agentRepository = agentRepository;
        this.bundleApplicator = bundleApplicator;
        this.abEvalPipeline = abEvalPipeline;
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
        if (candidateBundle == null) {
            throw new IllegalArgumentException("candidateBundle is required");
        }
        if (baselineBundle == null) {
            throw new IllegalArgumentException("baselineBundle is required");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (datasetVersionId == null || datasetVersionId.isBlank()) {
            throw new IllegalArgumentException("datasetVersionId is required");
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
        abRun.setDatasetVersionId(datasetVersionId);
        abRun.setSkipBaseline(skipBaseline);
        abRun.setCachedBaselineRate(cachedBaselineRate);
        abRun.setPriorWinnerAbRunId(priorWinnerAbRunId);
        abRun.setStatus(AgentEvolveAbRunEntity.STATUS_PENDING);
        abRunRepository.save(abRun);

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
            coordinatorExecutor.execute(() -> runAsync(abRunId));
        }
        return abRunId;
    }

    /**
     * Async runner — applies the bundles, runs the whole-agent A/B, aggregates a
     * single pass-rate, persists results. Package-private for direct unit-test
     * invocation.
     */
    void runAsync(String abRunId) {
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

            AgentDefinition candidateDef = bundleApplicator.apply(agent, candidateBundle);
            // When the baseline side is skipped its def is never executed; pass the
            // candidate def as a harmless non-null placeholder (runWithDefs ignores
            // the baseline def's run but still copies eval-overrides off it).
            AgentDefinition baselineDef = skipBaseline
                    ? candidateDef
                    : bundleApplicator.apply(agent, baselineBundle);

            List<EvalScenarioEntity> scenarios =
                    evalDatasetService.getScenariosForVersion(abRun.getDatasetVersionId());
            if (scenarios == null || scenarios.isEmpty()) {
                throw new IllegalStateException(
                        "No scenarios resolved for datasetVersionId=" + abRun.getDatasetVersionId());
            }

            // explain=true → byte-identical to the prompt path's judge call (§7 B1).
            List<AbScenarioResult> perScenario = abEvalPipeline.runWithDefs(
                    abRun.getId(), scenarios, baselineDef, candidateDef, cachedBaselineRate, true);

            AgentAbDeltas deltas = computeDeltas(perScenario, cachedBaselineRate);
            abRun.setBaselinePassRate(deltas.baselinePassRate());
            abRun.setCandidatePassRate(deltas.candidatePassRate());
            abRun.setDeltaPassRate(deltas.deltaPassRate());
            // Phase 1: target/regression split is Phase 2 (§7 W8) → leave null.

            try {
                abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(perScenario));
            } catch (JsonProcessingException ex) {
                log.warn("[AgentEvolveAb] failed to serialize perScenario results: {}", ex.getMessage());
            }
            abRun.setStatus(AgentEvolveAbRunEntity.STATUS_COMPLETED);
            abRun.setCompletedAt(Instant.now());
            abRunRepository.save(abRun);
            broadcastStage(abRun, "ab_completed", null);

            log.info("[AgentEvolveAb] COMPLETED abRunId={} baseline={} candidate={} delta={} skipBaseline={}",
                    abRun.getId(), deltas.baselinePassRate(), deltas.candidatePassRate(),
                    deltas.deltaPassRate(), skipBaseline);
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
        int total = perScenario == null ? 0 : perScenario.size();
        int candidatePassed = 0;
        int baselinePassed = 0;
        if (perScenario != null) {
            for (AbScenarioResult r : perScenario) {
                if (AbEvalPipeline.isPass(r.candidate())) {
                    candidatePassed++;
                }
                if (AbEvalPipeline.isPass(r.baseline())) {
                    baselinePassed++;
                }
            }
        }
        double candidateRate = total == 0 ? 0.0 : (double) candidatePassed / total * 100.0;
        double baselineRate = cachedBaselineRate != null
                ? cachedBaselineRate
                : (total == 0 ? 0.0 : (double) baselinePassed / total * 100.0);
        return new AgentAbDeltas(baselineRate, candidateRate, candidateRate - baselineRate);
    }

    /** Pure result of {@link #computeDeltas}. */
    record AgentAbDeltas(double baselinePassRate, double candidatePassRate, double deltaPassRate) {}

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
