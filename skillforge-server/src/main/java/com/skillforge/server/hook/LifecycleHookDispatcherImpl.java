package com.skillforge.server.hook;

import com.skillforge.core.engine.TraceCollector;
import com.skillforge.core.engine.TraceSpan;
import com.skillforge.core.engine.hook.ChainDecision;
import com.skillforge.core.engine.hook.FailurePolicy;
import com.skillforge.core.engine.hook.HandlerRunner;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Core lifecycle hook dispatcher. Responsibilities:
 *
 * <ul>
 *   <li>Short-circuits on null / empty hook configuration.</li>
 *   <li>Enforces a 1-level ThreadLocal recursion guard to prevent runaway hook chains.</li>
 *   <li>Iterates every {@link HookEntry} in order, honoring
 *       {@link ChainDecision#CONTINUE} / {@link ChainDecision#ABORT} / {@link ChainDecision#SKIP_CHAIN}.</li>
 *   <li>Rejects forbidden skills before delegating to any runner.</li>
 *   <li>Routes to the correct {@link HandlerRunner} by handler subtype.</li>
 *   <li>Wraps runner execution in {@link CompletableFuture} with per-entry timeout.</li>
 *   <li>Emits one {@code LIFECYCLE_HOOK} trace span per entry with
 *       {@code entryIndex / handlerType / handlerName / chainDecision / stdoutSize}.</li>
 * </ul>
 *
 * <p>Never throws out of the five named wrappers — any failure is logged, traced, and folded
 * into the boolean / void return contract.
 */
@Component
public class LifecycleHookDispatcherImpl implements LifecycleHookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LifecycleHookDispatcherImpl.class);

    /** Max runner output stored on a trace span. Real stdout can still be larger; see runner. */
    private static final int TRACE_OUTPUT_MAX = 4_096;
    private static final int MAX_HOOK_DEPTH = 1;
    private static final int MIN_TIMEOUT_SEC = 1;
    private static final int MAX_TIMEOUT_SEC = 300;

    // hookDepth is ThreadLocal; only safe on platform threads — revisit if virtual threads adopted
    private static final ThreadLocal<Integer> hookDepth = ThreadLocal.withInitial(() -> 0);

    private final Map<Class<? extends HookHandler>, HandlerRunner<?>> runners;
    private final Executor hookExecutor;
    private final TraceCollector traceCollector;
    private final Set<String> forbiddenSkills;

    public LifecycleHookDispatcherImpl(List<HandlerRunner<?>> runners,
                                       @Qualifier("hookExecutor") Executor hookExecutor,
                                       TraceCollector traceCollector,
                                       @Value("${lifecycle.hooks.forbidden-skills:SubAgent,TeamCreate,TeamSend,TeamKill}")
                                       List<String> forbiddenSkills) {
        Map<Class<? extends HookHandler>, HandlerRunner<?>> map = new HashMap<>();
        for (HandlerRunner<?> r : runners) {
            map.put(r.handlerType(), r);
        }
        this.runners = Map.copyOf(map);
        this.hookExecutor = hookExecutor;
        this.traceCollector = traceCollector;
        this.forbiddenSkills = forbiddenSkills != null ? Set.copyOf(forbiddenSkills) : Set.of();
        log.info("LifecycleHookDispatcher initialized: {} runner(s) {}, forbiddenSkills={}",
                map.size(), map.keySet(), this.forbiddenSkills);
    }

    /** Test-only constructor — default forbidden list is empty so tests stay deterministic. */
    public LifecycleHookDispatcherImpl(List<HandlerRunner<?>> runners,
                                       Executor hookExecutor,
                                       TraceCollector traceCollector) {
        this(runners, hookExecutor, traceCollector, List.of());
    }

    @Override
    public boolean dispatch(HookEvent event,
                            Map<String, Object> input,
                            AgentDefinition agentDef,
                            String sessionId,
                            Long userId) {
        return dispatchCollecting(event, input, agentDef, sessionId, userId).keepGoing();
    }

    @Override
    public DispatchOutcome dispatchCollecting(HookEvent event,
                                              Map<String, Object> input,
                                              AgentDefinition agentDef,
                                              String sessionId,
                                              Long userId) {
        if (event == null || agentDef == null) return new DispatchOutcome(true, List.of());

        // 1. Depth guard — no nested hook -> hook Skill -> hook chain.
        int depth = hookDepth.get();
        if (depth >= MAX_HOOK_DEPTH) {
            log.warn("Skipping lifecycle hook {} — depth limit reached (depth={})", event, depth);
            return new DispatchOutcome(true, List.of());
        }

        LifecycleHooksConfig cfg = agentDef.getLifecycleHooks();
        if (cfg == null) return new DispatchOutcome(true, List.of());
        List<HookEntry> entries = cfg.entriesFor(event);
        if (entries.isEmpty()) return new DispatchOutcome(true, List.of());

        List<HookRunResult> syncResults = new ArrayList<>();
        // P1: iterate every entry, honoring ChainDecision.
        for (int i = 0; i < entries.size(); i++) {
            HookEntry entry = entries.get(i);
            if (entry == null || entry.getHandler() == null) {
                continue;
            }
            HookRunResult result = runEntry(event, entry, i, input, sessionId, userId);
            if (result == null) {
                // Runner routing bug — defensive default
                continue;
            }
            if (!entry.isAsync()) {
                syncResults.add(result);
            }
            ChainDecision decision = result.chainDecision();
            if (decision == ChainDecision.ABORT) {
                return new DispatchOutcome(false, syncResults);
            }
            if (decision == ChainDecision.SKIP_CHAIN) {
                break;
            }
            // CONTINUE → fall through
        }
        return new DispatchOutcome(true, syncResults);
    }

    /**
     * Run a single entry and compute its {@link ChainDecision}. Never throws.
     */
    private HookRunResult runEntry(HookEvent event,
                                   HookEntry entry,
                                   int entryIndex,
                                   Map<String, Object> input,
                                   String sessionId,
                                   Long userId) {
        HookHandler handler = entry.getHandler();
        FailurePolicy policy = entry.getFailurePolicy() != null ? entry.getFailurePolicy() : FailurePolicy.CONTINUE;
        int timeoutSec = clampTimeout(entry.getTimeoutSeconds());

        // Forbidden-skill gate — enforced at dispatcher layer (not SkillHandlerRunner) so any
        // future runner sharing the SkillHandler type inherits the rule.
        if (handler instanceof HookHandler.SkillHandler sh
                && sh.getSkillName() != null
                && forbiddenSkills.contains(sh.getSkillName())) {
            HookRunResult failed = HookRunResult.failure("forbidden_skill:" + sh.getSkillName(), 0);
            ChainDecision decision = chainDecisionFor(false, policy, entry.isAsync());
            failed = failed.withChainDecision(decision);
            traceHook(event, entry, entryIndex, sessionId, failed, "forbidden_skill", 0);
            return failed;
        }

        // Async+SKIP_CHAIN recovery: async entries never produce a meaningful chain decision
        // beyond CONTINUE. If an older persisted config slipped past validation with
        // async=true && SKIP_CHAIN, downgrade at runtime so we don't wait for a never-running
        // decision.
        if (entry.isAsync() && policy == FailurePolicy.SKIP_CHAIN) {
            log.warn("[policy_overridden_async] async entry with SKIP_CHAIN policy (event={}) — downgrading to CONTINUE at runtime",
                    event);
            policy = FailurePolicy.CONTINUE;
        }

        @SuppressWarnings("unchecked")
        HandlerRunner<HookHandler> runner = (HandlerRunner<HookHandler>) runners.get(handler.getClass());
        if (runner == null) {
            log.warn("No HandlerRunner registered for handler type {}; runner_not_implemented",
                    handler.getClass().getSimpleName());
            ChainDecision decision = chainDecisionFor(false, policy, entry.isAsync());
            HookRunResult failed = new HookRunResult(false, null, "runner_not_implemented", 0, decision);
            traceHook(event, entry, entryIndex, sessionId, failed, "runner_not_implemented", 0);
            return failed;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("_hook_origin", "lifecycle:" + event.wireName());
        metadata.put("_hook_timeout_sec", timeoutSec);
        HookExecutionContext execCtx = new HookExecutionContext(sessionId, userId, event, Map.copyOf(metadata));

        int previousDepth = hookDepth.get();
        hookDepth.set(previousDepth + 1);
        try {
            if (entry.isAsync()) {
                runAsync(event, entry, entryIndex, runner, handler, input, execCtx, timeoutSec, previousDepth + 1);
                return new HookRunResult(true, null, null, 0, ChainDecision.CONTINUE);
            }
            return runSync(event, entry, entryIndex, runner, handler, input, execCtx, timeoutSec, policy,
                    previousDepth + 1);
        } finally {
            if (previousDepth == 0) {
                hookDepth.remove();
            } else {
                hookDepth.set(previousDepth);
            }
        }
    }

    private void runAsync(HookEvent event,
                          HookEntry entry,
                          int entryIndex,
                          HandlerRunner<HookHandler> runner,
                          HookHandler handler,
                          Map<String, Object> input,
                          HookExecutionContext execCtx,
                          int timeoutSec,
                          int propagatedDepth) {
        long t0 = System.currentTimeMillis();
        CompletableFuture<HookRunResult> fut;
        try {
            fut = CompletableFuture.supplyAsync(
                            () -> runRunnerWithDepth(runner, handler, input, execCtx, propagatedDepth),
                            hookExecutor)
                    .orTimeout(timeoutSec, TimeUnit.SECONDS);
        } catch (RejectedExecutionException rex) {
            log.warn("Async lifecycle hook {} rejected by executor (handler={}); pool saturated",
                    event, describe(handler));
            HookRunResult rejected = HookRunResult.failure("executor_rejected", 0);
            traceHook(event, entry, entryIndex, execCtx.sessionId(), rejected, "executor_rejected", 0);
            return;
        }
        fut.whenComplete((r, ex) -> {
            long dur = System.currentTimeMillis() - t0;
            if (ex != null) {
                String reason = ex.getCause() instanceof TimeoutException
                        ? "timeout" : "async_error:" + ex.getClass().getSimpleName();
                HookRunResult failed = HookRunResult.failure(reason, dur);
                traceHook(event, entry, entryIndex, execCtx.sessionId(), failed, reason, dur);
            } else if (r != null) {
                String reason = r.success() ? "ok" : "handler_error";
                traceHook(event, entry, entryIndex, execCtx.sessionId(), r, reason, dur);
            } else {
                HookRunResult failed = HookRunResult.failure("null_result", dur);
                traceHook(event, entry, entryIndex, execCtx.sessionId(), failed, "null_result", dur);
            }
        });
    }

    private HookRunResult runSync(HookEvent event,
                                  HookEntry entry,
                                  int entryIndex,
                                  HandlerRunner<HookHandler> runner,
                                  HookHandler handler,
                                  Map<String, Object> input,
                                  HookExecutionContext execCtx,
                                  int timeoutSec,
                                  FailurePolicy policy,
                                  int propagatedDepth) {
        long t0 = System.currentTimeMillis();
        CompletableFuture<HookRunResult> fut;
        try {
            fut = CompletableFuture.supplyAsync(
                    () -> runRunnerWithDepth(runner, handler, input, execCtx, propagatedDepth),
                    hookExecutor);
        } catch (RejectedExecutionException rex) {
            log.warn("Lifecycle hook {} rejected by executor (handler={}); pool saturated",
                    event, describe(handler));
            ChainDecision decision = chainDecisionFor(false, policy, false);
            HookRunResult rejected = new HookRunResult(false, null, "executor_rejected", 0, decision);
            traceHook(event, entry, entryIndex, execCtx.sessionId(), rejected, "executor_rejected", 0);
            return rejected;
        }
        try {
            HookRunResult result = fut.get(timeoutSec, TimeUnit.SECONDS);
            long dur = System.currentTimeMillis() - t0;
            if (result == null) {
                ChainDecision decision = chainDecisionFor(false, policy, false);
                HookRunResult nullResult = new HookRunResult(false, null, "null_result", dur, decision);
                traceHook(event, entry, entryIndex, execCtx.sessionId(), nullResult, "null_result", dur);
                return nullResult;
            }
            ChainDecision decision = chainDecisionFor(result.success(), policy, false);
            HookRunResult decorated = result.withChainDecision(decision);
            String reason = result.success() ? "ok" : "handler_error";
            traceHook(event, entry, entryIndex, execCtx.sessionId(), decorated, reason, dur);
            return decorated;
        } catch (TimeoutException e) {
            fut.cancel(true);
            long dur = System.currentTimeMillis() - t0;
            log.warn("Lifecycle hook {} timed out after {}s (handler={})", event, timeoutSec, describe(handler));
            ChainDecision decision = chainDecisionFor(false, policy, false);
            HookRunResult timedOut = new HookRunResult(false, null, "timeout", dur, decision);
            traceHook(event, entry, entryIndex, execCtx.sessionId(), timedOut, "timeout", dur);
            return timedOut;
        } catch (ExecutionException e) {
            long dur = System.currentTimeMillis() - t0;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Lifecycle hook {} failed: {}", event, cause.toString());
            ChainDecision decision = chainDecisionFor(false, policy, false);
            String reason = "exception:" + cause.getClass().getSimpleName();
            HookRunResult failed = new HookRunResult(false, null, reason, dur, decision);
            traceHook(event, entry, entryIndex, execCtx.sessionId(), failed, reason, dur);
            return failed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long dur = System.currentTimeMillis() - t0;
            ChainDecision decision = chainDecisionFor(false, policy, false);
            HookRunResult interrupted = new HookRunResult(false, null, "interrupted", dur, decision);
            traceHook(event, entry, entryIndex, execCtx.sessionId(), interrupted, "interrupted", dur);
            return interrupted;
        }
    }

    /**
     * Compute the {@link ChainDecision} from (success, failurePolicy).
     * Async entries always yield CONTINUE — they can't rewrite the chain by design.
     */
    private static ChainDecision chainDecisionFor(boolean success, FailurePolicy policy, boolean async) {
        if (async) return ChainDecision.CONTINUE;
        if (success) return ChainDecision.CONTINUE;
        if (policy == FailurePolicy.ABORT) return ChainDecision.ABORT;
        if (policy == FailurePolicy.SKIP_CHAIN) return ChainDecision.SKIP_CHAIN;
        return ChainDecision.CONTINUE;
    }

    /**
     * Run the runner on an arbitrary executor thread while propagating the current hook depth
     * to that thread's ThreadLocal. If the runner recurses into {@link #dispatch}, the depth
     * guard on the new thread will see the propagated value and short-circuit the inner call.
     */
    private HookRunResult runRunnerWithDepth(HandlerRunner<HookHandler> runner,
                                             HookHandler handler,
                                             Map<String, Object> input,
                                             HookExecutionContext execCtx,
                                             int depth) {
        Integer previous = hookDepth.get();
        hookDepth.set(depth);
        try {
            return runner.run(handler, input, execCtx);
        } finally {
            if (previous == null || previous == 0) {
                hookDepth.remove();
            } else {
                hookDepth.set(previous);
            }
        }
    }

    private int clampTimeout(int rawTimeoutSec) {
        if (rawTimeoutSec < MIN_TIMEOUT_SEC) return MIN_TIMEOUT_SEC;
        if (rawTimeoutSec > MAX_TIMEOUT_SEC) return MAX_TIMEOUT_SEC;
        return rawTimeoutSec;
    }

    private void traceHook(HookEvent event,
                           HookEntry entry,
                           int entryIndex,
                           String sessionId,
                           HookRunResult result,
                           String reason,
                           long durationMs) {
        if (traceCollector == null) return;
        try {
            TraceSpan span = new TraceSpan("LIFECYCLE_HOOK", event.wireName());
            span.setSessionId(sessionId);
            span.setStartTimeMs(System.currentTimeMillis() - durationMs);
            span.setDurationMs(durationMs);
            span.setEndTimeMs(System.currentTimeMillis());
            boolean success = result != null && result.success();
            span.setSuccess(success);
            if (!success) span.setError(reason);
            // Per-entry annotation — never embed scriptBody (handled in describe()).
            String input = describe(entry.getHandler())
                    + "|idx=" + entryIndex
                    + "|type=" + handlerTypeKey(entry.getHandler())
                    + "|chainDecision=" + (result != null && result.chainDecision() != null
                            ? result.chainDecision().name() : "CONTINUE")
                    + "|stdoutSize=" + stdoutSize(result);
            span.setInput(input);
            if (result != null && result.output() != null) {
                String out = result.output();
                span.setOutput(out.length() > TRACE_OUTPUT_MAX ? out.substring(0, TRACE_OUTPUT_MAX) + "..." : out);
            } else if (reason != null) {
                span.setOutput(reason);
            }
            traceCollector.record(span);
        } catch (Exception e) {
            log.debug("Failed to record LIFECYCLE_HOOK trace span (non-fatal): {}", e.toString());
        }
    }

    private static int stdoutSize(HookRunResult result) {
        if (result == null || result.output() == null) return 0;
        return result.output().length();
    }

    private static String handlerTypeKey(HookHandler h) {
        if (h instanceof HookHandler.SkillHandler) return "skill";
        if (h instanceof HookHandler.ScriptHandler) return "script";
        if (h instanceof HookHandler.MethodHandler) return "method";
        return "unknown";
    }

    private static String describe(HookHandler h) {
        if (h == null) return "null";
        if (h instanceof HookHandler.SkillHandler s) return "skill:" + s.getSkillName();
        if (h instanceof HookHandler.ScriptHandler s) {
            String body = s.getScriptBody();
            String preview = body == null ? "" : body.substring(0, Math.min(body.length(), 40))
                    .replace('\n', ' ');
            // scriptBody truncated to 40 chars for observability; full body intentionally never logged.
            return "script:" + safeLang(s.getScriptLang()) + ":" + preview;
        }
        if (h instanceof HookHandler.MethodHandler m) return "method:" + m.getMethodRef();
        return h.getClass().getSimpleName();
    }

    private static String safeLang(String lang) {
        return lang == null ? "?" : lang.toLowerCase(Locale.ROOT);
    }

    // ----- Named wrappers -----

    @Override
    public boolean fireSessionStart(AgentDefinition agentDef, String sessionId, Long userId) {
        Map<String, Object> input = baseInput(HookEvent.SESSION_START, agentDef, sessionId);
        input.put("agent_name", agentDef.getName());
        input.put("user_id", userId);
        return dispatch(HookEvent.SESSION_START, input, agentDef, sessionId, userId);
    }

    @Override
    public boolean fireUserPromptSubmit(AgentDefinition agentDef,
                                        String sessionId,
                                        Long userId,
                                        String userMessage,
                                        int messageCount) {
        return fireUserPromptSubmitCollecting(agentDef, sessionId, userId, userMessage, messageCount).keepGoing();
    }

    @Override
    public DispatchOutcome fireUserPromptSubmitCollecting(AgentDefinition agentDef,
                                                          String sessionId,
                                                          Long userId,
                                                          String userMessage,
                                                          int messageCount) {
        Map<String, Object> input = baseInput(HookEvent.USER_PROMPT_SUBMIT, agentDef, sessionId);
        input.put("user_message", userMessage);
        input.put("message_count", messageCount);
        return dispatchCollecting(HookEvent.USER_PROMPT_SUBMIT, input, agentDef, sessionId, userId);
    }

    @Override
    public void firePostToolUse(AgentDefinition agentDef,
                                String sessionId,
                                Long userId,
                                String skillName,
                                Map<String, Object> skillInput,
                                SkillResult result,
                                long durationMs) {
        Map<String, Object> input = baseInput(HookEvent.POST_TOOL_USE, agentDef, sessionId);
        input.put("skill_name", skillName);
        input.put("skill_input", skillInput != null ? skillInput : Map.of());
        String output = result == null ? null : (result.isSuccess() ? result.getOutput() : result.getError());
        if (output != null && output.length() > 4096) output = output.substring(0, 4096) + "...";
        input.put("skill_output", output);
        input.put("success", result != null && result.isSuccess());
        input.put("duration_ms", durationMs);
        dispatch(HookEvent.POST_TOOL_USE, input, agentDef, sessionId, userId);
    }

    @Override
    public void fireStop(AgentDefinition agentDef,
                         String sessionId,
                         Long userId,
                         int loopCount,
                         long inputTokens,
                         long outputTokens,
                         String finalResponse) {
        Map<String, Object> input = baseInput(HookEvent.STOP, agentDef, sessionId);
        input.put("loop_count", loopCount);
        input.put("total_input_tokens", inputTokens);
        input.put("total_output_tokens", outputTokens);
        String trimmed = finalResponse;
        if (trimmed != null && trimmed.length() > 2048) trimmed = trimmed.substring(0, 2048) + "...";
        input.put("final_response", trimmed);
        dispatch(HookEvent.STOP, input, agentDef, sessionId, userId);
    }

    @Override
    public void fireSessionEnd(AgentDefinition agentDef,
                               String sessionId,
                               Long userId,
                               int messageCount,
                               String reason) {
        Map<String, Object> input = baseInput(HookEvent.SESSION_END, agentDef, sessionId);
        input.put("user_id", userId);
        input.put("message_count", messageCount);
        input.put("reason", reason);
        dispatch(HookEvent.SESSION_END, input, agentDef, sessionId, userId);
    }

    private static Map<String, Object> baseInput(HookEvent event, AgentDefinition agentDef, String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hook_event", event.wireName());
        m.put("session_id", sessionId);
        if (agentDef.getId() != null) {
            try {
                m.put("agent_id", Long.parseLong(agentDef.getId()));
            } catch (NumberFormatException e) {
                m.put("agent_id", agentDef.getId());
            }
        }
        return m;
    }
}
