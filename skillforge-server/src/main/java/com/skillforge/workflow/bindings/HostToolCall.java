package com.skillforge.workflow.bindings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.workflow.WorkflowContext;
import com.skillforge.workflow.WorkflowToolInvoker;
import com.skillforge.workflow.journal.JournalCache;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;

import java.util.HashMap;
import java.util.Map;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — the {@code tool(name, input?)} host binding: invoke a
 * whitelisted Java tool SYNCHRONOUSLY on the workflow thread (design §4.1). The
 * deterministic counterpart to {@code agent()} — no LLM, no sub-session, no
 * tool-selection drift. Used for the evolve-loop's mechanical nodes (TriggerAbEval
 * / GetAbResult / RecordIteration / GetCandidateDiff / ...).
 *
 * <p>Mirrors {@link HostAgent}'s sequential path:
 * <ul>
 *   <li>allocate a deterministic {@code stepIndex} ({@code ctx.nextStepIndex()},
 *       the SAME counter {@code agent()} / {@code humanApprove()} use);</li>
 *   <li>journal-replay short-circuit: on resume, a {@code tool()} before the
 *       frontier returns its cached first-run result — a {@code tool()} has side
 *       effects (it triggers A/B, records iterations), so re-running it on resume
 *       would double-fire (plan §4.1 "resume 短路");</li>
 *   <li>otherwise invoke synchronously and return the parsed result to JS.</li>
 * </ul>
 *
 * <p><b>Not supported inside {@code parallel()}/{@code pipeline()}</b> (Phase 1):
 * a {@code tool()} blocks the workflow thread, which is exactly where
 * {@code parallel()} evaluates its thunks — calling it there would serialize the
 * fan-out. The evolve-loop never does this; reject it explicitly rather than
 * silently mis-behave.
 */
public final class HostToolCall extends BaseFunction {

    private final transient WorkflowContext ctx;
    private final transient WorkflowToolInvoker invoker;

    public HostToolCall(WorkflowContext ctx, WorkflowToolInvoker invoker) {
        this.ctx = ctx;
        this.invoker = invoker;
    }

    @Override
    public String getFunctionName() {
        return "tool";
    }

    @Override
    public int getArity() {
        return 2;
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (args.length == 0 || args[0] == null) {
            throw Context.reportRuntimeError("tool(name, input?) requires a tool name string");
        }
        if (invoker == null) {
            throw Context.reportRuntimeError(
                    "tool() is not available in this run context (no WorkflowToolInvoker bound)");
        }
        if (ctx.isInParallelCollect()) {
            throw Context.reportRuntimeError(
                    "tool() is not supported inside parallel()/pipeline() (Phase 1)");
        }
        String toolName = Context.toString(args[0]);
        Map<String, Object> input = extractInput(args);

        // Deterministic, on-the-workflow-thread bookkeeping (shared counter).
        int stepIndex = ctx.nextStepIndex();
        ctx.recordInvokeThread(Thread.currentThread().getName());

        // ── Journal-replay short-circuit (plan §4.1) ──
        // tool() has side effects, so on resume every tool() strictly BEFORE the
        // frontier returns its first-run result from the journal — no re-execution,
        // no new step row, no double-fire of the A/B / record.
        if (ctx.isResuming() && stepIndex < ctx.getResumeFrontierIndex()) {
            Object cached = cachedToolResult(stepIndex);
            return JsConversions.toJs(cx, scope, cached);
        }

        Object out = invoker.invoke(toolName, input, stepIndex);
        return JsConversions.toJs(cx, scope, out);
    }

    /**
     * Re-derives the JS return value of a cache-hit {@code tool()} from its
     * first-run {@code result} node (stored in the journal). A miss is a genuine
     * replay bug (the step should exist from the prior run).
     */
    private Object cachedToolResult(int stepIndex) {
        JournalCache cache = ctx.getJournalCache();
        if (cache == null) {
            throw new IllegalStateException(
                    "journal-replay requires a JournalCache, but none is bound (runId="
                            + ctx.getRunId() + ")");
        }
        JsonNode result = cache.getCachedToolResult(ctx.getRunId(), stepIndex)
                .orElseThrow(() -> new IllegalStateException(
                        "journal-replay cache miss for tool() stepIndex=" + stepIndex
                                + " (runId=" + ctx.getRunId() + ")"));
        ObjectMapper om = ctx.getObjectMapper();
        if (om == null) {
            throw new IllegalStateException("journal-replay tool result requires an ObjectMapper");
        }
        return om.convertValue(result, Object.class);
    }

    private Map<String, Object> extractInput(Object[] args) {
        Map<String, Object> input = new HashMap<>();
        if (args.length > 1 && args[1] instanceof NativeObject no) {
            for (Object id : no.getIds()) {
                String key = String.valueOf(id);
                input.put(key, JsConversions.jsToJava(no.get(key, no)));
            }
        }
        return input;
    }
}
