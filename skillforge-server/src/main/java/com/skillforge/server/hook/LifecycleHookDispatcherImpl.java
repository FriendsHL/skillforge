package com.skillforge.server.hook;

import com.skillforge.core.engine.TraceCollector;
import com.skillforge.core.engine.TraceSpan;
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
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>Routes to the correct {@link HandlerRunner} by handler subtype.</li>
 *   <li>Wraps runner execution in {@link CompletableFuture} with per-entry timeout.</li>
 *   <li>Applies {@link FailurePolicy} to decide whether the main flow continues.</li>
 *   <li>Emits {@code LIFECYCLE_HOOK} trace spans.</li>
 * </ul>
 *
 * <p>Never throws out of the five named wrappers — any failure is logged, traced, and folded
 * into the boolean / void return contract.
 */
@Component
public class LifecycleHookDispatcherImpl implements LifecycleHookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LifecycleHookDispatcherImpl.class);

    /** Max runner input output that we include in trace. Keeps traces lean. */
    private static final int TRACE_OUTPUT_MAX = 2048;
    private static final int MAX_HOOK_DEPTH = 1;
    private static final int MIN_TIMEOUT_SEC = 1;
    private static final int MAX_TIMEOUT_SEC = 300;

    // hookDepth is ThreadLocal; only safe on platform threads — revisit if virtual threads adopted
    private static final ThreadLocal<Integer> hookDepth = ThreadLocal.withInitial(() -> 0);

    private final Map<Class<? extends HookHandler>, HandlerRunner<?>> runners;
    private final Executor hookExecutor;
    private final TraceCollector traceCollector;

    public LifecycleHookDispatcherImpl(List<HandlerRunner<?>> runners,
                                       @Qualifier("hookExecutor") Executor hookExecutor,
                                       TraceCollector traceCollector) {
        Map<Class<? extends HookHandler>, HandlerRunner<?>> map = new HashMap<>();
        for (HandlerRunner<?> r : runners) {
            map.put(r.handlerType(), r);
        }
        this.runners = Map.copyOf(map);
        this.hookExecutor = hookExecutor;
        this.traceCollector = traceCollector;
        log.info("LifecycleHookDispatcher initialized with {} runner(s): {}", map.size(), map.keySet());
    }

    @Override
    public boolean dispatch(HookEvent event,
                            Map<String, Object> input,
                            AgentDefinition agentDef,
                            String sessionId,
                            Long userId) {
        if (event == null || agentDef == null) return true;

        // 1. Depth guard — no nested hook -> hook Skill -> hook chain.
        int depth = hookDepth.get();
        if (depth >= MAX_HOOK_DEPTH) {
            log.warn("Skipping lifecycle hook {} — depth limit reached (depth={})", event, depth);
            return true;
        }

        LifecycleHooksConfig cfg = agentDef.getLifecycleHooks();
        if (cfg == null) return true;
        List<HookEntry> entries = cfg.entriesFor(event);
        if (entries.isEmpty()) return true;

        // P0: only first entry is executed. Multi-entry chain is P1.
        HookEntry entry = entries.get(0);
        if (entry == null || entry.getHandler() == null) {
            return true;
        }
        return runEntry(event, entry, input, sessionId, userId);
    }

    private boolean runEntry(HookEvent event,
                             HookEntry entry,
                             Map<String, Object> input,
                             String sessionId,
                             Long userId) {
        HookHandler handler = entry.getHandler();
        FailurePolicy policy = entry.getFailurePolicy() != null ? entry.getFailurePolicy() : FailurePolicy.CONTINUE;
        int timeoutSec = clampTimeout(entry.getTimeoutSeconds());

        @SuppressWarnings("unchecked")
        HandlerRunner<HookHandler> runner = (HandlerRunner<HookHandler>) runners.get(handler.getClass());
        if (runner == null) {
            log.warn("No HandlerRunner registered for handler type {}; treating as runner_not_implemented",
                    handler.getClass().getSimpleName());
            traceHook(event, entry, sessionId, null, "runner_not_implemented", 0, false);
            return policy != FailurePolicy.ABORT;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("_hook_origin", "lifecycle:" + event.wireName());
        HookExecutionContext execCtx = new HookExecutionContext(sessionId, userId, event, Map.copyOf(metadata));

        int previousDepth = hookDepth.get();
        hookDepth.set(previousDepth + 1);
        try {
            if (entry.isAsync()) {
                return runAsync(event, entry, runner, handler, input, execCtx, timeoutSec, previousDepth + 1);
            }
            return runSync(event, entry, runner, handler, input, execCtx, timeoutSec, policy, previousDepth + 1);
        } finally {
            if (previousDepth == 0) {
                hookDepth.remove();
            } else {
                hookDepth.set(previousDepth);
            }
        }
    }

    private boolean runAsync(HookEvent event,
                             HookEntry entry,
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
            log.warn("Lifecycle hook {} rejected by executor (handler={}); pool saturated",
                    event, describe(handler));
            traceHook(event, entry, execCtx.sessionId(), null, "executor_rejected", 0, false);
            // Async hooks never block or ABORT the main flow, even on rejection.
            return true;
        }
        fut.whenComplete((r, ex) -> {
            long dur = System.currentTimeMillis() - t0;
            if (ex != null) {
                String reason = ex.getCause() instanceof TimeoutException
                        ? "timeout" : "async_error:" + ex.getClass().getSimpleName();
                traceHook(event, entry, execCtx.sessionId(), null, reason, dur, false);
            } else if (r != null) {
                traceHook(event, entry, execCtx.sessionId(), r, r.success() ? "ok" : "handler_error", dur,
                        r.success());
            } else {
                traceHook(event, entry, execCtx.sessionId(), null, "null_result", dur, false);
            }
        });
        // Async hooks never block or ABORT the main flow.
        return true;
    }

    private boolean runSync(HookEvent event,
                            HookEntry entry,
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
            traceHook(event, entry, execCtx.sessionId(), null, "executor_rejected", 0, false);
            return policy != FailurePolicy.ABORT;
        }
        try {
            HookRunResult result = fut.get(timeoutSec, TimeUnit.SECONDS);
            long dur = System.currentTimeMillis() - t0;
            if (result == null) {
                traceHook(event, entry, execCtx.sessionId(), null, "null_result", dur, false);
                return policy != FailurePolicy.ABORT;
            }
            traceHook(event, entry, execCtx.sessionId(), result,
                    result.success() ? "ok" : "handler_error", dur, result.success());
            if (!result.success() && policy == FailurePolicy.ABORT) {
                return false;
            }
            return true;
        } catch (TimeoutException e) {
            fut.cancel(true);
            long dur = System.currentTimeMillis() - t0;
            log.warn("Lifecycle hook {} timed out after {}s (handler={})", event, timeoutSec, describe(handler));
            traceHook(event, entry, execCtx.sessionId(), null, "timeout", dur, false);
            return policy != FailurePolicy.ABORT;
        } catch (ExecutionException e) {
            long dur = System.currentTimeMillis() - t0;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Lifecycle hook {} failed: {}", event, cause.toString());
            traceHook(event, entry, execCtx.sessionId(), null,
                    "exception:" + cause.getClass().getSimpleName(), dur, false);
            return policy != FailurePolicy.ABORT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long dur = System.currentTimeMillis() - t0;
            traceHook(event, entry, execCtx.sessionId(), null, "interrupted", dur, false);
            return policy != FailurePolicy.ABORT;
        }
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
                           String sessionId,
                           HookRunResult result,
                           String reason,
                           long durationMs,
                           boolean success) {
        if (traceCollector == null) return;
        try {
            TraceSpan span = new TraceSpan("LIFECYCLE_HOOK", event.wireName());
            span.setSessionId(sessionId);
            span.setStartTimeMs(System.currentTimeMillis() - durationMs);
            span.setDurationMs(durationMs);
            span.setEndTimeMs(System.currentTimeMillis());
            span.setSuccess(success);
            if (!success) span.setError(reason);
            span.setInput(describe(entry.getHandler()));
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

    private static String describe(HookHandler h) {
        if (h == null) return "null";
        if (h instanceof HookHandler.SkillHandler s) return "skill:" + s.getSkillName();
        if (h instanceof HookHandler.ScriptHandler s) return "script:" + s.getScriptLang();
        if (h instanceof HookHandler.MethodHandler m) return "method:" + m.getMethodRef();
        return h.getClass().getSimpleName();
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
        Map<String, Object> input = baseInput(HookEvent.USER_PROMPT_SUBMIT, agentDef, sessionId);
        input.put("user_message", userMessage);
        input.put("message_count", messageCount);
        return dispatch(HookEvent.USER_PROMPT_SUBMIT, input, agentDef, sessionId, userId);
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
