package com.skillforge.server.eval;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ScenarioRunnerTool {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunnerTool.class);

    private final EvalSessionRepository evalSessionRepository;
    private final SandboxSkillRegistryFactory sandboxFactory;
    private final EvalEngineFactory engineFactory;
    private final ChatEventBroadcaster broadcaster;
    private final ExecutorService evalLoopExecutor;

    public ScenarioRunnerTool(EvalSessionRepository evalSessionRepository,
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

    /**
     * EVAL-V2 M2: multi-turn execution. Drives the agent through each user turn
     * sequentially, accumulating prior turns as message history so the agent
     * sees a normal multi-turn conversation. Assistant placeholders in the
     * spec are replaced in-memory by actual responses for the judge transcript;
     * the on-disk EvalScenario is never mutated.
     *
     * <p>Single sandbox + single sandbox-registry built once, reused for all
     * turns (mirroring how a chat session shares its skill registry across
     * messages). Per-turn execution uses a 25s timeout; the case-level budget
     * is 90s total — exceeding it returns a TIMEOUT result.
     *
     * <p>The returned {@link ScenarioRunResult} aggregates loop count, tool
     * calls, and tokens across all turns. {@code agentFinalOutput} is the
     * last turn's response. If any turn errors, the case is reported as ERROR.
     *
     * @param transcriptOut populated in-place with the full conversation
     *                      (user turns verbatim, assistant turns with actual
     *                      content). Pre-existing entries are preserved.
     */
    public ScenarioRunResult runScenarioMultiTurn(String evalRunId, EvalScenario scenario,
                                                   AgentDefinition agentDef, Long userId,
                                                   MultiTurnTranscript transcriptOut) {
        String evalSessionId = "eval_" + UUID.randomUUID().toString();

        EvalSessionEntity evalSession = new EvalSessionEntity();
        evalSession.setSessionId(evalSessionId);
        evalSession.setEvalRunId(evalRunId);
        evalSession.setScenarioId(scenario.getId());
        evalSession.setStatus("running");
        evalSession.setStartedAt(Instant.now());
        evalSessionRepository.save(evalSession);

        long startMs = System.currentTimeMillis();
        long budgetMs = 90_000L;

        try {
            writeFixtureFiles(evalRunId, scenario);

            // Build sandbox registry + engine ONCE for the whole conversation.
            SkillRegistry sandboxRegistry = sandboxFactory.buildSandboxRegistry(evalRunId, scenario.getId());
            AgentLoopEngine engine = engineFactory.buildEvalEngine(sandboxRegistry);
            AgentDefinition evalAgentDef = copyAgentDefWithoutEvalOverrides(agentDef);

            Path sandboxRoot = sandboxFactory.getSandboxRoot(evalRunId, scenario.getId());

            // Aggregated counters across turns.
            ScenarioRunResult agg = new ScenarioRunResult();
            agg.setScenarioId(scenario.getId());
            agg.setStatus("PENDING_JUDGE");

            List<Message> history = new ArrayList<>();
            String lastResponse = null;
            int totalLoops = 0;
            long totalInput = 0;
            long totalOutput = 0;

            // Pre-collect user turn indices so we know when to inject runtime
            // responses into the transcript output (assistant placeholders are
            // populated by the immediate prior user-turn's response, in order).
            List<EvalScenario.ConversationTurn> turns = scenario.getConversationTurns();
            int userTurnIdx = 0;
            int totalUserTurns = (int) turns.stream()
                    .filter(t -> "user".equalsIgnoreCase(t.getRole()))
                    .count();

            for (int i = 0; i < turns.size(); i++) {
                EvalScenario.ConversationTurn turn = turns.get(i);
                String role = turn.getRole();
                String content = turn.getContent();

                if (!"user".equalsIgnoreCase(role)) {
                    // Assistant placeholder: skip — the next user turn's engine.run
                    // call will produce the actual assistant response and the engine's
                    // returned message list naturally carries it forward.
                    //
                    // System / tool turns from the spec are surfaced into the judge
                    // transcript only (not pushed into agent history) — M2 MVP keeps
                    // execution semantics simple; agent's authoritative system prompt
                    // is the AgentDefinition's, not a per-case override.
                    if (transcriptOut != null && !"assistant".equalsIgnoreCase(role)) {
                        transcriptOut.add(role, content);
                    }
                    continue;
                }

                long elapsed = System.currentTimeMillis() - startMs;
                long remaining = budgetMs - elapsed;
                if (remaining <= 5_000L) {
                    log.warn("Multi-turn scenario {} timed out at user turn {}/{}",
                            scenario.getId(), userTurnIdx + 1, totalUserTurns);
                    return ScenarioRunResult.timeout(scenario.getId(), "90s budget exceeded mid-conversation");
                }

                // Per-turn execution mirrors the single-turn path's settings.
                LoopContext ctx = new LoopContext();
                ctx.setMaxLoops(scenario.getMaxLoops());
                ctx.setExecutionMode("auto");
                ctx.setMaxLlmStreamTimeoutMs(20_000L);

                // Substitute /tmp/eval/ → sandbox root (same logic as runSingleScenario).
                String userMsg = content == null ? "" : content.replace("/tmp/eval/", sandboxRoot.toString() + "/");

                if (transcriptOut != null) transcriptOut.add("user", userMsg);

                LoopResult turnResult;
                try {
                    turnResult = engine.run(evalAgentDef, userMsg,
                            new ArrayList<>(history), evalSessionId, userId, ctx);
                } catch (Exception turnEx) {
                    log.error("Multi-turn scenario {} turn {}/{} failed: {}",
                            scenario.getId(), userTurnIdx + 1, totalUserTurns, turnEx.getMessage());
                    ScenarioRunResult err = ScenarioRunResult.error(scenario.getId(), turnEx.getMessage());
                    err.setExecutionTimeMs(System.currentTimeMillis() - startMs);
                    err.setLoopCount(totalLoops);
                    err.setInputTokens(totalInput);
                    err.setOutputTokens(totalOutput);
                    err.setAgentFinalOutput(lastResponse);
                    return err;
                }

                userTurnIdx++;

                String response = turnResult.getFinalResponse();
                lastResponse = response;
                totalLoops += turnResult.getLoopCount();
                totalInput += turnResult.getTotalInputTokens();
                totalOutput += turnResult.getTotalOutputTokens();
                agg.applyToolCallSignals(turnResult.getToolCalls());

                if (transcriptOut != null) transcriptOut.add("assistant", response == null ? "" : response);

                // Carry forward conversation history: the engine returned the full message
                // list including the new user message + assistant + any tool exchanges.
                // For the next turn, history = previous turns' user+assistant + this turn's
                // user+assistant. Use the engine's message list directly to preserve any
                // tool_use/tool_result pairing the engine produced (must stay paired).
                if (turnResult.getMessages() != null) {
                    history = new ArrayList<>(turnResult.getMessages());
                } else {
                    // Fallback: reconstruct minimally so the next turn still has context.
                    history.add(Message.user(userMsg));
                    if (response != null) history.add(Message.assistant(response));
                }
            }

            agg.setLoopCount(totalLoops);
            agg.setInputTokens(totalInput);
            agg.setOutputTokens(totalOutput);
            agg.setAgentFinalOutput(lastResponse);
            agg.setExecutionTimeMs(System.currentTimeMillis() - startMs);

            String sessionStatus = "PENDING_JUDGE".equals(agg.getStatus()) ? "completed" : "failed";
            evalSession.setStatus(sessionStatus);
            evalSession.setCompletedAt(Instant.now());
            evalSessionRepository.save(evalSession);

            return agg;
        } catch (Exception e) {
            log.error("Multi-turn scenario {} failed unexpectedly", scenario.getId(), e);
            evalSession.setStatus("failed");
            evalSession.setCompletedAt(Instant.now());
            evalSessionRepository.save(evalSession);
            ScenarioRunResult err = ScenarioRunResult.error(scenario.getId(), e.getMessage());
            err.setExecutionTimeMs(System.currentTimeMillis() - startMs);
            return err;
        } finally {
            sandboxFactory.cleanupSandbox(evalRunId, scenario.getId());
        }
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

            // Build LoopContext — set scenario's maxLoops BEFORE engine.run() overwrites it.
            // AgentLoopEngine.run() unconditionally applies agentDef.config.max_loops if present,
            // which would corrupt the hitLoopLimit signal and the loop budget.
            // Fix: pass a copy of agentDef with max_loops removed so the scenario's value wins.
            String evalSessionId = "eval_" + UUID.randomUUID().toString();
            LoopContext ctx = new LoopContext();
            ctx.setMaxLoops(scenario.getMaxLoops());
            ctx.setExecutionMode("auto");
            ctx.setMaxLlmStreamTimeoutMs(20_000L);

            // Strip max_loops and execution_mode from a defensive copy so they don't override eval values
            AgentDefinition evalAgentDef = copyAgentDefWithoutEvalOverrides(agentDef);

            // Rewrite task to use sandbox paths
            Path sandboxRoot = sandboxFactory.getSandboxRoot(evalRunId, scenario.getId());
            String task = scenario.getTask().replace("/tmp/eval/", sandboxRoot.toString() + "/");

            // Run engine
            LoopResult loopResult = engine.run(evalAgentDef, task, null, evalSessionId, null, ctx);

            long executionTimeMs = System.currentTimeMillis() - startMs;

            // Build result
            ScenarioRunResult result = new ScenarioRunResult();
            result.setScenarioId(scenario.getId());
            result.setAgentFinalOutput(loopResult.getFinalResponse());
            result.setLoopCount(loopResult.getLoopCount());
            result.setInputTokens(loopResult.getTotalInputTokens());
            result.setOutputTokens(loopResult.getTotalOutputTokens());
            result.setExecutionTimeMs(executionTimeMs);

            result.applyToolCallSignals(loopResult.getToolCalls());

            // Status will be set later by EvalJudgeTool based on oracle scoring
            result.setStatus("PENDING_JUDGE");
            return result;
        } catch (Exception e) {
            log.error("Single scenario run failed for {}: {}", scenario.getId(), e.getMessage());
            ScenarioRunResult result = ScenarioRunResult.error(scenario.getId(), e.getMessage());
            result.setExecutionTimeMs(System.currentTimeMillis() - startMs);
            return result;
        }
    }

    /**
     * Returns a copy of agentDef with eval-sensitive config keys removed so they don't override
     * eval-specific LoopContext values (max_loops, execution_mode) set by the scenario.
     */
    private AgentDefinition copyAgentDefWithoutEvalOverrides(AgentDefinition original) {
        AgentDefinition copy = new AgentDefinition();
        copy.setName(original.getName());
        copy.setDescription(original.getDescription());
        copy.setSystemPrompt(original.getSystemPrompt());
        copy.setModelId(original.getModelId());
        copy.setSkillIds(original.getSkillIds());
        if (original.getConfig() != null) {
            Map<String, Object> configCopy = new HashMap<>(original.getConfig());
            configCopy.remove("max_loops");       // scenario's maxLoops must win
            configCopy.remove("execution_mode");  // eval always runs in "auto" mode
            copy.setConfig(configCopy);
        }
        return copy;
    }
}
