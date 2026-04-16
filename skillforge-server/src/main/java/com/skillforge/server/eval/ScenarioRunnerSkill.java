package com.skillforge.server.eval;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.engine.ToolCallRecord;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.EvalSessionEntity;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.repository.EvalSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ScenarioRunnerSkill {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunnerSkill.class);

    private final EvalSessionRepository evalSessionRepository;
    private final SandboxSkillRegistryFactory sandboxFactory;
    private final EvalEngineFactory engineFactory;
    private final ChatEventBroadcaster broadcaster;
    private final ExecutorService evalLoopExecutor;

    public ScenarioRunnerSkill(EvalSessionRepository evalSessionRepository,
                               SandboxSkillRegistryFactory sandboxFactory,
                               EvalEngineFactory engineFactory,
                               ChatEventBroadcaster broadcaster,
                               @Qualifier("evalLoopExecutor") ExecutorService evalLoopExecutor) {
        this.evalSessionRepository = evalSessionRepository;
        this.sandboxFactory = sandboxFactory;
        this.engineFactory = engineFactory;
        this.broadcaster = broadcaster;
        this.evalLoopExecutor = evalLoopExecutor;
    }

    public ScenarioRunResult runScenario(String evalRunId, EvalScenario scenario,
                                          AgentDefinition agentDef, Long userId) {
        String evalSessionId = "eval_" + UUID.randomUUID().toString();

        // 1. Create EvalSessionEntity
        EvalSessionEntity evalSession = new EvalSessionEntity();
        evalSession.setSessionId(evalSessionId);
        evalSession.setEvalRunId(evalRunId);
        evalSession.setScenarioId(scenario.getId());
        evalSession.setStatus("running");
        evalSession.setStartedAt(Instant.now());
        evalSessionRepository.save(evalSession);

        try {
            // 2. Write fixture files to sandbox
            writeFixtureFiles(evalRunId, scenario);

            // 3. Run with retry
            ScenarioRunResult result = runWithRetry(scenario, agentDef, evalRunId);

            // 4. Update EvalSessionEntity
            String sessionStatus = switch (result.getStatus()) {
                case "PASS", "FAIL" -> "completed";
                case "TIMEOUT" -> "timeout";
                default -> "failed";
            };
            evalSession.setStatus(sessionStatus);
            evalSession.setCompletedAt(Instant.now());
            evalSessionRepository.save(evalSession);

            return result;
        } catch (Exception e) {
            log.error("Scenario {} failed unexpectedly", scenario.getId(), e);
            evalSession.setStatus("failed");
            evalSession.setCompletedAt(Instant.now());
            evalSessionRepository.save(evalSession);
            return ScenarioRunResult.error(scenario.getId(), e.getMessage());
        } finally {
            // Cleanup sandbox
            sandboxFactory.cleanupSandbox(evalRunId, scenario.getId());
        }
    }

    private void writeFixtureFiles(String evalRunId, EvalScenario scenario) throws IOException {
        if (scenario.getSetup() == null || scenario.getSetup().getFiles() == null) return;

        Path sandboxRoot = sandboxFactory.getSandboxRoot(evalRunId, scenario.getId());
        for (Map.Entry<String, String> entry : scenario.getSetup().getFiles().entrySet()) {
            Path filePath = sandboxRoot.resolve(entry.getKey());
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(filePath, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    private ScenarioRunResult runWithRetry(EvalScenario scenario, AgentDefinition agentDef,
                                            String evalRunId) {
        long budgetMs = 90_000L;
        long startMs = System.currentTimeMillis();

        for (int attempt = 1; attempt <= 3; attempt++) {
            long remainingMs = budgetMs - (System.currentTimeMillis() - startMs);
            if (remainingMs <= 5_000L) {
                return ScenarioRunResult.timeout(scenario.getId(), "Budget exhausted");
            }
            long attemptTimeout = Math.min(25_000L, remainingMs);

            Future<ScenarioRunResult> future = evalLoopExecutor.submit(
                    () -> runSingleScenario(scenario, agentDef, evalRunId));
            try {
                ScenarioRunResult result = future.get(attemptTimeout, TimeUnit.MILLISECONDS);
                // PASS/FAIL/VETO: return immediately, no retry
                if (!"TIMEOUT".equals(result.getStatus()) && !"ERROR".equals(result.getStatus())) {
                    return result;
                }
                if (attempt == 3) return result;
                log.info("Scenario {} attempt {} resulted in {}, retrying", scenario.getId(), attempt, result.getStatus());
            } catch (TimeoutException e) {
                future.cancel(true);
                if (attempt == 3) {
                    return ScenarioRunResult.timeout(scenario.getId(), "90s budget exceeded");
                }
                log.info("Scenario {} attempt {} timed out, retrying", scenario.getId(), attempt);
            } catch (ExecutionException e) {
                if (attempt == 3) {
                    return ScenarioRunResult.error(scenario.getId(), e.getCause().getMessage());
                }
                log.info("Scenario {} attempt {} failed: {}, retrying", scenario.getId(), attempt, e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ScenarioRunResult.error(scenario.getId(), "Interrupted");
            }
        }
        return ScenarioRunResult.error(scenario.getId(), "Unreachable");
    }

    private ScenarioRunResult runSingleScenario(EvalScenario scenario, AgentDefinition agentDef,
                                                 String evalRunId) {
        long startMs = System.currentTimeMillis();
        try {
            // Build sandbox registry and engine
            SkillRegistry sandboxRegistry = sandboxFactory.buildSandboxRegistry(evalRunId, scenario.getId());
            AgentLoopEngine engine = engineFactory.buildEvalEngine(sandboxRegistry);

            // Build LoopContext
            String evalSessionId = "eval_" + UUID.randomUUID().toString();
            LoopContext ctx = new LoopContext();
            ctx.setMaxLoops(scenario.getMaxLoops());
            ctx.setExecutionMode("auto");
            ctx.setMaxLlmStreamTimeoutMs(20_000L);

            // Rewrite task to use sandbox paths
            Path sandboxRoot = sandboxFactory.getSandboxRoot(evalRunId, scenario.getId());
            String task = scenario.getTask().replace("/tmp/eval/", sandboxRoot.toString() + "/");

            // Run engine
            LoopResult loopResult = engine.run(agentDef, task, null, evalSessionId, null, ctx);

            long executionTimeMs = System.currentTimeMillis() - startMs;

            // Build result
            ScenarioRunResult result = new ScenarioRunResult();
            result.setScenarioId(scenario.getId());
            result.setAgentFinalOutput(loopResult.getFinalResponse());
            result.setLoopCount(loopResult.getLoopCount());
            result.setInputTokens(loopResult.getTotalInputTokens());
            result.setOutputTokens(loopResult.getTotalOutputTokens());
            result.setExecutionTimeMs(executionTimeMs);

            // Detect skill execution failures from tool call records
            if (loopResult.getToolCalls() != null) {
                for (ToolCallRecord record : loopResult.getToolCalls()) {
                    if (!record.isSuccess()) {
                        result.setSkillExecutionFailed(true);
                        break;
                    }
                }
            }

            // Status will be set later by EvalJudgeSkill based on oracle scoring
            result.setStatus("PENDING_JUDGE");
            return result;
        } catch (Exception e) {
            log.error("Single scenario run failed for {}: {}", scenario.getId(), e.getMessage());
            ScenarioRunResult result = ScenarioRunResult.error(scenario.getId(), e.getMessage());
            result.setExecutionTimeMs(System.currentTimeMillis() - startMs);
            return result;
        }
    }
}
