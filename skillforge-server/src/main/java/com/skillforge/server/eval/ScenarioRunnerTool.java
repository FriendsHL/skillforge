package com.skillforge.server.eval;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.EvalSessionEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.repository.EvalSessionRepository;
import com.skillforge.server.repository.SessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

/**
 * EVAL-V2 M3a (b2) major changes vs legacy:
 * <ul>
 *   <li>Creates a <strong>real</strong> {@link SessionEntity} with
 *       {@code origin='eval'} (instead of synthetic "eval_<UUID>") so:
 *       <ul>
 *         <li>{@code t_eval_task_item.session_id} → {@code t_session.id} FK is
 *             a real link, not a dangling reference.</li>
 *         <li>OBS trace UI can drill from a failed eval item to the chat
 *             session + root_trace_id (D7 reuses the OBS trace pipeline).</li>
 *         <li>Origin filtering (5-place) keeps eval sessions out of production
 *             dashboards / lists.</li>
 *       </ul>
 *   </li>
 *   <li>After {@code engine.run(...)} completes, runs a server-side post-write
 *       UPDATE on {@code t_llm_trace.origin = 'eval'} for the eval session_id.
 *       This is the simplest way to propagate origin to traces without
 *       touching {@code AgentLoopEngine} / {@code ChatService} business logic
 *       or the observability module's SQL (which would break standalone
 *       observability ITs that don't have {@code t_session}).</li>
 *   <li>Returns {@code sessionId} / {@code rootTraceId} / {@code toolCallCount}
 *       on {@link ScenarioRunResult} so {@code EvalOrchestrator} can persist
 *       them to {@code t_eval_task_item}.</li>
 * </ul>
 */
@Component
public class ScenarioRunnerTool {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunnerTool.class);

    private final EvalSessionRepository evalSessionRepository;
    private final SessionRepository sessionRepository;
    private final SandboxSkillRegistryFactory sandboxFactory;
    private final EvalEngineFactory engineFactory;
    private final ChatEventBroadcaster broadcaster;
    private final ExecutorService evalLoopExecutor;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * EVAL-V2 M3a (b2): explicit TransactionTemplate so we can open a
     * REQUIRES_NEW transaction without relying on Spring AOP / @Transactional.
     * Self-invocation from {@link #runScenario(String, EvalScenario,
     * AgentDefinition, Long, Long)} would otherwise bypass the proxy and the
     * @Transactional annotation would silently no-op (same trap PgLlmTraceStore
     * solves with TransactionTemplate). REQUIRES_NEW because we want each
     * eval-session write committed before {@code engine.run(...)} starts on a
     * different thread, and the trace-origin rewrite committed promptly after
     * scenario completion.
     */
    private final TransactionTemplate requiresNewTx;

    public ScenarioRunnerTool(EvalSessionRepository evalSessionRepository,
                               SessionRepository sessionRepository,
                               SandboxSkillRegistryFactory sandboxFactory,
                               EvalEngineFactory engineFactory,
                               ChatEventBroadcaster broadcaster,
                               @Qualifier("evalLoopExecutor") ExecutorService evalLoopExecutor,
                               PlatformTransactionManager transactionManager) {
        this.evalSessionRepository = evalSessionRepository;
        this.sessionRepository = sessionRepository;
        this.sandboxFactory = sandboxFactory;
        this.engineFactory = engineFactory;
        this.broadcaster = broadcaster;
        this.evalLoopExecutor = evalLoopExecutor;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
                                                   AgentDefinition agentDef, Long agentId, Long userId,
                                                   MultiTurnTranscript transcriptOut) {
        String evalSessionId = createEvalSession(evalRunId, scenario, agentId, userId);

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
            agg.setSessionId(evalSessionId);

            List<Message> history = new ArrayList<>();
            String lastResponse = null;
            int totalLoops = 0;
            long totalInput = 0;
            long totalOutput = 0;
            String capturedRootTraceId = null;

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
                    ScenarioRunResult timeoutResult = ScenarioRunResult.timeout(
                            scenario.getId(), "90s budget exceeded mid-conversation");
                    timeoutResult.setSessionId(evalSessionId);
                    return timeoutResult;
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
                    err.setSessionId(evalSessionId);
                    return err;
                }

                userTurnIdx++;

                String response = turnResult.getFinalResponse();
                lastResponse = response;
                totalLoops += turnResult.getLoopCount();
                totalInput += turnResult.getTotalInputTokens();
                totalOutput += turnResult.getTotalOutputTokens();
                agg.applyToolCallSignals(turnResult.getToolCalls());

                // Capture rootTraceId on first turn that produces a non-null
                // value (LoopContext is mutated by AgentLoopEngine to write
                // back the trace ids). Subsequent turns share the session
                // root since the session lookup happens per-turn.
                if (capturedRootTraceId == null && ctx.getRootTraceId() != null) {
                    capturedRootTraceId = ctx.getRootTraceId();
                }

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

            // EVAL-V2 M3a (b2): rootTraceId fallback — if no turn populated ctx,
            // re-read the live SessionEntity which AgentLoopEngine stamps with
            // active_root_trace_id during chat.
            if (capturedRootTraceId == null) {
                capturedRootTraceId = readSessionRootTraceId(evalSessionId);
            }
            agg.setRootTraceId(capturedRootTraceId);

            String sessionStatus = "PENDING_JUDGE".equals(agg.getStatus()) ? "completed" : "failed";
            updateEvalSessionStatus(evalSessionId, sessionStatus);

            return agg;
        } catch (Exception e) {
            log.error("Multi-turn scenario {} failed unexpectedly", scenario.getId(), e);
            updateEvalSessionStatus(evalSessionId, "failed");
            ScenarioRunResult err = ScenarioRunResult.error(scenario.getId(), e.getMessage());
            err.setExecutionTimeMs(System.currentTimeMillis() - startMs);
            err.setSessionId(evalSessionId);
            return err;
        } finally {
            try {
                rewriteTraceOriginToEval(evalSessionId);
            } catch (Exception ignored) {
                // rewriteTraceOriginToEval already logs internally; never mask the scenario result
            }
            sandboxFactory.cleanupSandbox(evalRunId, scenario.getId());
        }
    }

    public ScenarioRunResult runScenario(String evalRunId, EvalScenario scenario,
                                          AgentDefinition agentDef, Long agentId, Long userId) {
        String evalSessionId = createEvalSession(evalRunId, scenario, agentId, userId);

        try {
            // 2. Write fixture files to sandbox
            writeFixtureFiles(evalRunId, scenario);

            // 3. Run with retry — pass the session id through so single-turn
            //    runs use the same real session as the EvalSessionEntity row.
            ScenarioRunResult result = runWithRetry(scenario, agentDef, evalRunId, evalSessionId, userId);
            result.setSessionId(evalSessionId);

            // 4. Update EvalSessionEntity legacy row
            String sessionStatus = switch (result.getStatus()) {
                case "PASS", "FAIL" -> "completed";
                case "TIMEOUT" -> "timeout";
                default -> "failed";
            };
            updateEvalSessionStatus(evalSessionId, sessionStatus);

            // EVAL-V2 M3a (b2): rootTraceId fallback via session lookup.
            if (result.getRootTraceId() == null) {
                result.setRootTraceId(readSessionRootTraceId(evalSessionId));
            }

            return result;
        } catch (Exception e) {
            log.error("Scenario {} failed unexpectedly", scenario.getId(), e);
            updateEvalSessionStatus(evalSessionId, "failed");
            ScenarioRunResult err = ScenarioRunResult.error(scenario.getId(), e.getMessage());
            err.setSessionId(evalSessionId);
            return err;
        } finally {
            try {
                rewriteTraceOriginToEval(evalSessionId);
            } catch (Exception ignored) {
                // rewriteTraceOriginToEval already logs internally; never mask the scenario result
            }
            // Cleanup sandbox
            sandboxFactory.cleanupSandbox(evalRunId, scenario.getId());
        }
    }

    /**
     * EVAL-V2 M3a (b2): create a real {@code t_session} row with
     * {@code origin='eval'} so OBS trace UI can drill from
     * {@code t_eval_task_item.session_id → t_session.id}.
     *
     * <p>Mirrors the legacy {@link EvalSessionEntity} row (kept for backward
     * compat — some old callers still query {@code evalSessionRepository}).
     *
     * <p>Runs in a separate transaction (REQUIRES_NEW) because the orchestrator
     * is on the {@code evalOrchestratorExecutor} pool and we want the session
     * row visible to the engine before {@code engine.run(...)} starts (which
     * itself runs on a different thread / transaction).
     */
    private String createEvalSession(String evalRunId, EvalScenario scenario, Long agentId, Long userId) {
        String evalSessionId = UUID.randomUUID().toString();

        requiresNewTx.executeWithoutResult(s -> {
            // 1. Real SessionEntity (origin='eval').
            SessionEntity session = new SessionEntity();
            session.setId(evalSessionId);
            // userId is required (NOT NULL); fall back to 0 for the rare case where
            // an eval is triggered by background machinery without a user context.
            session.setUserId(userId != null ? userId : 0L);
            // agentId is required (NOT NULL); 0 is a benign sentinel for cases
            // where the caller couldn't resolve it (shouldn't happen in normal flow).
            session.setAgentId(agentId != null ? agentId : 0L);
            session.setOrigin(SessionEntity.ORIGIN_EVAL);
            session.setRuntimeStatus("running");
            session.setTitle("Eval: " + scenario.getId());
            session.setExecutionMode("auto");
            session.setSourceScenarioId(scenario.getId());
            sessionRepository.save(session);

            // 2. Legacy EvalSessionEntity row (kept until M3a-b3+1 sprint cleanup).
            EvalSessionEntity evalSession = new EvalSessionEntity();
            evalSession.setSessionId(evalSessionId);
            evalSession.setEvalRunId(evalRunId);
            evalSession.setScenarioId(scenario.getId());
            evalSession.setStatus("running");
            evalSession.setStartedAt(Instant.now());
            evalSessionRepository.save(evalSession);
        });

        return evalSessionId;
    }

    private void updateEvalSessionStatus(String evalSessionId, String status) {
        requiresNewTx.executeWithoutResult(s -> {
            evalSessionRepository.findById(evalSessionId).ifPresent(es -> {
                es.setStatus(status);
                es.setCompletedAt(Instant.now());
                evalSessionRepository.save(es);
            });
            sessionRepository.findById(evalSessionId).ifPresent(se -> {
                // mirror runtime status; mapping handled at OBS UI layer.
                String runtime = switch (status) {
                    case "completed" -> "idle";
                    case "timeout"   -> "error";
                    default          -> status;
                };
                se.setRuntimeStatus(runtime);
                se.setCompletedAt(Instant.now());
                sessionRepository.save(se);
            });
        });
    }

    /**
     * EVAL-V2 M3a (b2): post-write UPDATE on {@code t_llm_trace.origin} for all
     * traces this eval session produced. Idempotent — guard against re-running
     * with {@code AND origin = 'production'} so we don't churn rows that are
     * already marked.
     *
     * <p>Why post-write and not a request-time parameter:
     * <ul>
     *   <li>{@code AgentLoopEngine} / {@code ChatService} business logic is
     *       out of scope for b2 (per Plan).</li>
     *   <li>Modifying {@code PgLlmTraceStore} INSERT to do a sub-SELECT on
     *       {@code t_session} would break standalone observability ITs that
     *       don't have {@code t_session} table.</li>
     *   <li>Eval scenarios are synchronous from the orchestrator's POV (we
     *       await {@code engine.run(...)} return), so by the time we run the
     *       UPDATE all this scenario's traces have been written.</li>
     * </ul>
     */
    private void rewriteTraceOriginToEval(String evalSessionId) {
        if (evalSessionId == null || evalSessionId.isBlank()) return;
        try {
            requiresNewTx.executeWithoutResult(s -> {
                int updated = entityManager.createNativeQuery(
                        "UPDATE t_llm_trace SET origin = :evalOrigin "
                      + "WHERE session_id = :sessionId AND origin = :prodOrigin")
                        .setParameter("evalOrigin", SessionEntity.ORIGIN_EVAL)
                        .setParameter("sessionId", evalSessionId)
                        .setParameter("prodOrigin", SessionEntity.ORIGIN_PRODUCTION)
                        .executeUpdate();
                if (updated > 0) {
                    log.debug("Rewrote {} t_llm_trace rows to origin=eval for session={}",
                            updated, evalSessionId);
                }
            });
        } catch (Exception e) {
            // Don't fail the scenario on origin rewrite issues — at worst the
            // OBS dashboard double-counts this eval as production traffic until
            // we re-trigger; not worth aborting the eval.
            log.warn("Failed to rewrite trace.origin for session={}: {}", evalSessionId, e.getMessage());
        }
    }

    private String readSessionRootTraceId(String sessionId) {
        try {
            return requiresNewTx.execute(s ->
                    sessionRepository.findById(sessionId)
                            .map(SessionEntity::getActiveRootTraceId)
                            .orElse(null));
        } catch (Exception e) {
            return null;
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
                                            String evalRunId, String evalSessionId, Long userId) {
        long budgetMs = 90_000L;
        long startMs = System.currentTimeMillis();

        for (int attempt = 1; attempt <= 3; attempt++) {
            long remainingMs = budgetMs - (System.currentTimeMillis() - startMs);
            if (remainingMs <= 5_000L) {
                return ScenarioRunResult.timeout(scenario.getId(), "Budget exhausted");
            }
            long attemptTimeout = Math.min(25_000L, remainingMs);

            Future<ScenarioRunResult> future = evalLoopExecutor.submit(
                    () -> runSingleScenario(scenario, agentDef, evalRunId, evalSessionId, userId));
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
                                                 String evalRunId, String evalSessionId, Long userId) {
        long startMs = System.currentTimeMillis();
        try {
            // Build sandbox registry and engine
            SkillRegistry sandboxRegistry = sandboxFactory.buildSandboxRegistry(evalRunId, scenario.getId());
            AgentLoopEngine engine = engineFactory.buildEvalEngine(sandboxRegistry);

            // Build LoopContext — set scenario's maxLoops BEFORE engine.run() overwrites it.
            // AgentLoopEngine.run() unconditionally applies agentDef.config.max_loops if present,
            // which would corrupt the hitLoopLimit signal and the loop budget.
            // Fix: pass a copy of agentDef with max_loops removed so the scenario's value wins.
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
            LoopResult loopResult = engine.run(evalAgentDef, task, null, evalSessionId, userId, ctx);

            long executionTimeMs = System.currentTimeMillis() - startMs;

            // Build result
            ScenarioRunResult result = new ScenarioRunResult();
            result.setScenarioId(scenario.getId());
            result.setAgentFinalOutput(loopResult.getFinalResponse());
            result.setLoopCount(loopResult.getLoopCount());
            result.setInputTokens(loopResult.getTotalInputTokens());
            result.setOutputTokens(loopResult.getTotalOutputTokens());
            result.setExecutionTimeMs(executionTimeMs);
            result.setSessionId(evalSessionId);
            // ctx.rootTraceId is set by the engine when the trace pipeline is wired.
            result.setRootTraceId(ctx.getRootTraceId());

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
