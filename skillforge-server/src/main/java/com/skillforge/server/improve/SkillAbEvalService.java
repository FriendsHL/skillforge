package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalRunEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.EvalJudgeOutput;
import com.skillforge.server.eval.EvalJudgeSkill;
import com.skillforge.server.eval.ScenarioRunResult;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.EvalRunRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class SkillAbEvalService {

    private static final Logger log = LoggerFactory.getLogger(SkillAbEvalService.class);
    private static final double PROMOTION_DELTA_THRESHOLD_PP = 15.0;
    private static final double PROMOTION_MIN_CANDIDATE_RATE_PP = 40.0;

    private final SkillRepository skillRepository;
    private final SkillAbRunRepository skillAbRunRepository;
    private final EvalRunRepository evalRunRepository;
    private final AgentService agentService;
    private final ScenarioLoader scenarioLoader;
    private final SandboxSkillRegistryFactory sandboxFactory;
    private final EvalEngineFactory evalEngineFactory;
    private final EvalJudgeSkill evalJudgeSkill;
    private final SkillPackageLoader skillPackageLoader;
    private final ObjectMapper objectMapper;
    private final ChatEventBroadcaster broadcaster;
    private final ExecutorService coordinatorExecutor;
    private final ExecutorService loopExecutor;
    private final SkillRegistry skillRegistry;

    public SkillAbEvalService(SkillRepository skillRepository,
                              SkillAbRunRepository skillAbRunRepository,
                              EvalRunRepository evalRunRepository,
                              AgentService agentService,
                              ScenarioLoader scenarioLoader,
                              SandboxSkillRegistryFactory sandboxFactory,
                              EvalEngineFactory evalEngineFactory,
                              EvalJudgeSkill evalJudgeSkill,
                              SkillPackageLoader skillPackageLoader,
                              ObjectMapper objectMapper,
                              ChatEventBroadcaster broadcaster,
                              @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor,
                              @Qualifier("abEvalLoopExecutor") ExecutorService loopExecutor,
                              SkillRegistry skillRegistry) {
        this.skillRepository = skillRepository;
        this.skillAbRunRepository = skillAbRunRepository;
        this.evalRunRepository = evalRunRepository;
        this.agentService = agentService;
        this.scenarioLoader = scenarioLoader;
        this.sandboxFactory = sandboxFactory;
        this.evalEngineFactory = evalEngineFactory;
        this.evalJudgeSkill = evalJudgeSkill;
        this.skillPackageLoader = skillPackageLoader;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.coordinatorExecutor = coordinatorExecutor;
        this.loopExecutor = loopExecutor;
        this.skillRegistry = skillRegistry;
    }

    public SkillAbRunEntity createAndTrigger(Long parentSkillId, Long candidateSkillId,
                                              String agentId, String baselineEvalRunId,
                                              Long triggeredByUserId) {
        SkillEntity parent = skillRepository.findById(parentSkillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + parentSkillId));
        SkillEntity candidate = skillRepository.findById(candidateSkillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + candidateSkillId));

        if (candidate.getParentSkillId() == null || !candidate.getParentSkillId().equals(parentSkillId)) {
            throw new RuntimeException("Candidate skill is not a fork of parent");
        }
        if (candidate.isEnabled()) {
            throw new RuntimeException("Candidate skill is already promoted");
        }

        boolean inProgress = skillAbRunRepository
                .findByCandidateSkillIdOrderByStartedAtDesc(candidateSkillId).stream()
                .anyMatch(r -> "RUNNING".equals(r.getStatus()) || "PENDING".equals(r.getStatus()));
        if (inProgress) {
            throw new RuntimeException("An A/B run is already in progress for this candidate");
        }

        SkillAbRunEntity abRun = new SkillAbRunEntity();
        abRun.setId(UUID.randomUUID().toString());
        abRun.setParentSkillId(parentSkillId);
        abRun.setCandidateSkillId(candidateSkillId);
        abRun.setAgentId(agentId);
        abRun.setBaselineEvalRunId(baselineEvalRunId);
        abRun.setStatus("PENDING");
        abRun.setTriggeredByUserId(triggeredByUserId);
        SkillAbRunEntity saved = skillAbRunRepository.save(abRun);

        try {
            coordinatorExecutor.submit(() -> runAbTestAsync(saved.getId()));
        } catch (RejectedExecutionException e) {
            saved.setStatus("FAILED");
            saved.setFailureReason("Coordinator executor rejected task: " + e.getMessage());
            skillAbRunRepository.save(saved);
            throw new RuntimeException("Failed to schedule A/B test: executor is full or shutdown", e);
        }
        log.info("Created skill AB run: id={} parentSkillId={} candidateSkillId={}",
                saved.getId(), parentSkillId, candidateSkillId);
        return saved;
    }

    private void runAbTestAsync(String abRunId) {
        SkillAbRunEntity abRun = skillAbRunRepository.findById(abRunId).orElse(null);
        if (abRun == null) {
            log.warn("SkillAbRun not found for async execution: {}", abRunId);
            return;
        }
        try {
            abRun.setStatus("RUNNING");
            abRun.setStartedAt(Instant.now());
            skillAbRunRepository.save(abRun);

            List<EvalScenario> heldOutScenarios = scenarioLoader.loadAll().stream()
                    .filter(s -> "held_out".equals(s.getSplit()))
                    .toList();
            if (heldOutScenarios.isEmpty()) {
                log.warn("No held_out scenarios found, using all scenarios for skill AB eval");
                heldOutScenarios = scenarioLoader.loadAll();
            }

            double baselineRate = 0.0;
            if (abRun.getBaselineEvalRunId() != null) {
                Optional<EvalRunEntity> baselineRun = evalRunRepository.findById(abRun.getBaselineEvalRunId());
                if (baselineRun.isPresent()) {
                    baselineRate = computeHeldOutBaselineRate(baselineRun.get(), heldOutScenarios);
                }
            }
            abRun.setBaselinePassRate(baselineRate);

            SkillEntity candidateSkill = skillRepository.findById(abRun.getCandidateSkillId())
                    .orElseThrow(() -> new RuntimeException("Candidate skill vanished: " + abRun.getCandidateSkillId()));
            SkillDefinition candidateSkillDef = buildSkillDefinition(candidateSkill);

            AgentEntity agentEntity = agentService.getAgent(Long.parseLong(abRun.getAgentId()));
            AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);

            List<AbScenarioResult> scenarioResults = new ArrayList<>();
            int passed = 0;
            for (EvalScenario scenario : heldOutScenarios) {
                log.info("Skill AB eval scenario: {} ({})", scenario.getId(), scenario.getName());
                String candidateStatus;
                double candidateScore;
                try {
                    ScenarioRunResult runResult = runSingleScenario(abRunId, scenario, agentDef, candidateSkillDef);
                    EvalJudgeOutput judgeOutput = evalJudgeSkill.judge(scenario, runResult);
                    candidateStatus = runResult.getStatus();
                    candidateScore = judgeOutput.getCompositeScore();
                    if (judgeOutput.isPass()) {
                        passed++;
                    }
                } catch (Exception e) {
                    log.error("Skill AB eval scenario {} failed: {}", scenario.getId(), e.getMessage());
                    candidateStatus = "ERROR";
                    candidateScore = 0.0;
                }

                scenarioResults.add(new AbScenarioResult(
                        scenario.getId(), scenario.getName(),
                        new AbScenarioResult.RunResult("UNKNOWN", 0.0),
                        new AbScenarioResult.RunResult(candidateStatus, candidateScore)));

                if (broadcaster != null && abRun.getTriggeredByUserId() != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", "ab_skill_scenario_finished");
                    event.put("abRunId", abRun.getId());
                    event.put("scenarioId", scenario.getId());
                    event.put("candidateStatus", candidateStatus);
                    event.put("candidateScore", candidateScore);
                    broadcaster.userEvent(abRun.getTriggeredByUserId(), event);
                }
            }

            double candidatePassRate = heldOutScenarios.isEmpty() ? 0
                    : (double) passed / heldOutScenarios.size() * 100;
            double delta = candidatePassRate - baselineRate;

            try {
                abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(scenarioResults));
            } catch (Exception e) {
                log.warn("Failed to serialize skill AB scenario results", e);
            }

            abRun.setCandidatePassRate(candidatePassRate);
            abRun.setDeltaPassRate(delta);
            abRun.setStatus("COMPLETED");
            abRun.setCompletedAt(Instant.now());

            boolean shouldPromote = delta >= PROMOTION_DELTA_THRESHOLD_PP
                    && candidatePassRate >= PROMOTION_MIN_CANDIDATE_RATE_PP;
            if (shouldPromote) {
                promoteCandidate(candidateSkill);
                abRun.setPromoted(true);
            } else {
                abRun.setSkipReason(String.format(
                        "delta=%.2f candidateRate=%.2f (thresholds: delta>=%.1f, rate>=%.1f)",
                        delta, candidatePassRate, PROMOTION_DELTA_THRESHOLD_PP, PROMOTION_MIN_CANDIDATE_RATE_PP));
            }

            skillAbRunRepository.save(abRun);

            if (broadcaster != null && abRun.getTriggeredByUserId() != null) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "ab_skill_run_completed");
                event.put("abRunId", abRun.getId());
                event.put("status", abRun.getStatus());
                event.put("promoted", abRun.isPromoted());
                event.put("baselinePassRate", baselineRate);
                event.put("candidatePassRate", candidatePassRate);
                event.put("deltaPassRate", delta);
                event.put("skipReason", abRun.getSkipReason());
                broadcaster.userEvent(abRun.getTriggeredByUserId(), event);
            }

            log.info("Skill AB eval completed: abRunId={}, candidateRate={}, baselineRate={}, delta={}, promoted={}",
                    abRun.getId(), candidatePassRate, baselineRate, delta, abRun.isPromoted());
        } catch (Exception e) {
            log.error("Skill AB eval failed: abRunId={}", abRunId, e);
            try {
                abRun.setStatus("FAILED");
                abRun.setFailureReason(e.getMessage());
                abRun.setCompletedAt(Instant.now());
                skillAbRunRepository.save(abRun);
                if (broadcaster != null && abRun.getTriggeredByUserId() != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", "ab_skill_run_completed");
                    event.put("abRunId", abRun.getId());
                    event.put("status", "FAILED");
                    event.put("failureReason", e.getMessage());
                    broadcaster.userEvent(abRun.getTriggeredByUserId(), event);
                }
            } catch (Exception saveErr) {
                log.error("Failed to persist FAILED status for abRunId={}", abRunId, saveErr);
            }
        }
    }

    @Transactional
    public void promoteCandidate(SkillEntity candidateSkill) {
        SkillEntity parentSkill = skillRepository.findById(candidateSkill.getParentSkillId())
                .orElse(null);
        candidateSkill.setEnabled(true);
        skillRepository.save(candidateSkill);
        if (parentSkill != null) {
            parentSkill.setEnabled(false);
            skillRepository.save(parentSkill);
        }
        try {
            SkillDefinition promotedDef = buildSkillDefinition(candidateSkill);
            skillRegistry.registerSkillDefinition(promotedDef);
            log.info("Promoted skill id={} (semver={}), registered in SkillRegistry",
                    candidateSkill.getId(), candidateSkill.getSemver());
        } catch (Exception e) {
            log.warn("Failed to re-register promoted skill in SkillRegistry: {}", e.getMessage());
        }
    }

    private SkillDefinition buildSkillDefinition(SkillEntity skill) {
        if (skill.getSkillPath() != null) {
            try {
                return skillPackageLoader.loadFromDirectory(Path.of(skill.getSkillPath()));
            } catch (IOException e) {
                log.warn("Failed to load skill package from {}, falling back to metadata: {}",
                        skill.getSkillPath(), e.getMessage());
            }
        }
        SkillDefinition def = new SkillDefinition();
        def.setId(String.valueOf(skill.getId()));
        def.setName(skill.getName());
        def.setDescription(skill.getDescription());
        StringBuilder prompt = new StringBuilder();
        prompt.append("# ").append(skill.getName()).append("\n\n");
        if (skill.getDescription() != null) {
            prompt.append(skill.getDescription()).append("\n\n");
        }
        if (skill.getTriggers() != null) {
            prompt.append("**Use when:** ").append(skill.getTriggers()).append("\n\n");
        }
        if (skill.getRequiredTools() != null) {
            prompt.append("**Required tools:** ").append(skill.getRequiredTools()).append("\n");
        }
        def.setPromptContent(prompt.toString());
        if (skill.getTriggers() != null) {
            def.setTriggers(Arrays.asList(skill.getTriggers().split(",")));
        }
        if (skill.getRequiredTools() != null) {
            def.setRequiredTools(Arrays.asList(skill.getRequiredTools().split(",")));
        }
        return def;
    }

    private ScenarioRunResult runSingleScenario(String abRunId, EvalScenario scenario,
                                                 AgentDefinition agentDef,
                                                 SkillDefinition candidateSkillDef) {
        try {
            SkillRegistry sandboxRegistry = sandboxFactory.buildSandboxRegistryWithSkills(
                    abRunId, scenario.getId(), List.of(candidateSkillDef));
            AgentLoopEngine engine = evalEngineFactory.buildEvalEngine(sandboxRegistry);

            String evalSessionId = UUID.randomUUID().toString();
            LoopContext ctx = new LoopContext();
            ctx.setMaxLoops(scenario.getMaxLoops());
            ctx.setExecutionMode("auto");
            ctx.setMaxLlmStreamTimeoutMs(20_000L);

            AgentDefinition evalDef = copyWithoutEvalOverrides(agentDef);

            Path sandboxRoot = sandboxFactory.getSandboxRoot(abRunId, scenario.getId());
            String task = scenario.getTask().replace("/tmp/eval/", sandboxRoot.toString() + "/");

            if (scenario.getSetup() != null && scenario.getSetup().getFiles() != null) {
                java.nio.file.Files.createDirectories(sandboxRoot);
                for (Map.Entry<String, String> entry : scenario.getSetup().getFiles().entrySet()) {
                    Path filePath = sandboxRoot.resolve(entry.getKey());
                    if (filePath.getParent() != null) {
                        java.nio.file.Files.createDirectories(filePath.getParent());
                    }
                    java.nio.file.Files.writeString(filePath, entry.getValue());
                }
            }

            Future<LoopResult> future = loopExecutor.submit(
                    () -> engine.run(evalDef, task, null, evalSessionId, null, ctx));

            long startMs = System.currentTimeMillis();
            LoopResult loopResult;
            try {
                loopResult = future.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return ScenarioRunResult.timeout(scenario.getId(), "30s skill AB eval timeout");
            }

            long executionTimeMs = System.currentTimeMillis() - startMs;
            ScenarioRunResult result = new ScenarioRunResult();
            result.setScenarioId(scenario.getId());
            result.setAgentFinalOutput(loopResult.getFinalResponse());
            result.setLoopCount(loopResult.getLoopCount());
            result.setInputTokens(loopResult.getTotalInputTokens());
            result.setOutputTokens(loopResult.getTotalOutputTokens());
            result.setExecutionTimeMs(executionTimeMs);
            result.setStatus("PENDING_JUDGE");

            if (loopResult.getToolCalls() != null) {
                loopResult.getToolCalls().stream()
                        .filter(tc -> !tc.isSuccess())
                        .findFirst()
                        .ifPresent(tc -> result.setSkillExecutionFailed(true));
            }
            return result;
        } catch (Exception e) {
            log.error("Skill AB eval single scenario failed: {}", scenario.getId(), e);
            return ScenarioRunResult.error(scenario.getId(), e.getMessage());
        } finally {
            sandboxFactory.cleanupSandbox(abRunId, scenario.getId());
        }
    }

    private AgentDefinition copyWithoutEvalOverrides(AgentDefinition original) {
        AgentDefinition copy = new AgentDefinition();
        copy.setName(original.getName());
        copy.setDescription(original.getDescription());
        copy.setSystemPrompt(original.getSystemPrompt());
        copy.setModelId(original.getModelId());
        copy.setSkillIds(original.getSkillIds());
        copy.setSoulPrompt(original.getSoulPrompt());
        copy.setToolsPrompt(original.getToolsPrompt());
        if (original.getConfig() != null) {
            Map<String, Object> configCopy = new HashMap<>(original.getConfig());
            configCopy.remove("max_loops");
            configCopy.remove("execution_mode");
            copy.setConfig(configCopy);
        }
        return copy;
    }

    private double computeHeldOutBaselineRate(EvalRunEntity baselineRun, List<EvalScenario> heldOutScenarios) {
        if (baselineRun.getScenarioResultsJson() == null) {
            return baselineRun.getOverallPassRate();
        }
        try {
            List<Map<String, Object>> results = objectMapper.readValue(
                    baselineRun.getScenarioResultsJson(),
                    new TypeReference<List<Map<String, Object>>>() {});

            List<String> heldOutIds = heldOutScenarios.stream()
                    .map(EvalScenario::getId).toList();

            long heldOutPassed = results.stream()
                    .filter(r -> heldOutIds.contains(r.get("scenarioId")))
                    .filter(r -> Boolean.TRUE.equals(r.get("pass")))
                    .count();
            long heldOutTotal = results.stream()
                    .filter(r -> heldOutIds.contains(r.get("scenarioId")))
                    .count();
            return heldOutTotal == 0 ? 0 : (double) heldOutPassed / heldOutTotal * 100;
        } catch (Exception e) {
            log.warn("Failed to parse baseline scenario results, using overall pass rate", e);
            return baselineRun.getOverallPassRate();
        }
    }

    public List<SkillAbRunEntity> getAbRunsForSkill(Long skillId) {
        List<SkillAbRunEntity> asParent = skillAbRunRepository.findByParentSkillIdOrderByStartedAtDesc(skillId);
        List<SkillAbRunEntity> asCandidate = skillAbRunRepository.findByCandidateSkillIdOrderByStartedAtDesc(skillId);
        List<SkillAbRunEntity> merged = new ArrayList<>(asParent);
        for (SkillAbRunEntity r : asCandidate) {
            if (merged.stream().noneMatch(m -> m.getId().equals(r.getId()))) {
                merged.add(r);
            }
        }
        merged.sort(Comparator.comparing(
                SkillAbRunEntity::getStartedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return merged;
    }

    public Optional<SkillAbRunEntity> getAbRun(String abRunId) {
        return skillAbRunRepository.findById(abRunId);
    }
}
