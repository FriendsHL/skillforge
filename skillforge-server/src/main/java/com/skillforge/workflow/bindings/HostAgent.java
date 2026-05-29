package com.skillforge.workflow.bindings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.workflow.WorkflowAgentInvoker;
import com.skillforge.workflow.WorkflowContext;
import com.skillforge.workflow.journal.JournalCache;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * The {@code agent(prompt, opts?)} host binding (plan §5.1). Context-sensitive
 * behaviour (plan §2.1):
 *
 * <ul>
 *   <li><b>Sequential context</b> (top-level / not inside {@code parallel}):
 *       invoke the sub-agent synchronously on the workflow thread and return the
 *       result to JS.</li>
 *   <li><b>parallel-collect context</b>: do NOT block — submit the (pure-Java)
 *       {@code invoke} to the sub-agent executor and return a
 *       {@link PendingAgentCall} placeholder. {@code HostParallel} barrier-joins
 *       these after evaluating all thunks.</li>
 * </ul>
 *
 * <p>Budget increment and step-index allocation happen <em>at invoke time</em> on
 * the workflow thread (single-threaded → deterministic order, plan §3.2) BEFORE
 * any offload.
 */
public final class HostAgent extends BaseFunction {

    private final transient WorkflowContext ctx;
    private final transient WorkflowAgentInvoker invoker;
    private final transient ExecutorService subAgentExecutor;

    public HostAgent(WorkflowContext ctx, WorkflowAgentInvoker invoker, ExecutorService subAgentExecutor) {
        this.ctx = ctx;
        this.invoker = invoker;
        this.subAgentExecutor = subAgentExecutor;
    }

    @Override
    public String getFunctionName() {
        return "agent";
    }

    @Override
    public int getArity() {
        return 2;
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (args.length == 0 || args[0] == null) {
            throw Context.reportRuntimeError("agent(prompt) requires a prompt string");
        }
        String prompt = Context.toString(args[0]);
        Map<String, Object> opts = extractOpts(args);

        // Deterministic, on-the-workflow-thread bookkeeping (plan §3.2).
        ctx.getBudget().incrementAgentCalls();
        int stepIndex = ctx.nextStepIndex();
        ctx.recordInvokeThread(Thread.currentThread().getName());

        // ── Journal-replay short-circuit (Task F, plan §2.5) ──
        // On resume, every agent() call strictly BEFORE the frontier returns its
        // first-run result from the journal — no engine.run, no new step row, no
        // token spend. The control flow re-runs identically (deterministic step
        // indices) so the cache always hits. parallel-collect returns an
        // already-completed future so HostParallel barrier-joins instantly.
        if (ctx.isResuming() && stepIndex < ctx.getResumeFrontierIndex()) {
            Object cached = cachedAgentResult(stepIndex, opts);
            if (ctx.isInParallelCollect()) {
                return new PendingAgentCall(stepIndex, CompletableFuture.completedFuture(cached));
            }
            return JsConversions.toJs(cx, scope, cached);
        }

        if (ctx.isInParallelCollect()) {
            // Offload the blocking engine.run to a worker thread. Pure Java — never
            // touches Rhino — so concurrent execution is safe (plan §2.1).
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(
                    () -> invoker.invoke(prompt, opts, stepIndex), subAgentExecutor);
            return new PendingAgentCall(stepIndex, future);
        }

        // Sequential path: block on the workflow thread and return the value.
        // Result may now be a Map/List (schema-parsed object, Task E) — go via
        // JsConversions.toJs, never raw Context.javaToJS (ClassShutter footgun).
        Object out = invoker.invoke(prompt, opts, stepIndex);
        return JsConversions.toJs(cx, scope, out);
    }

    /**
     * Re-derives the JS return value of a cache-hit {@code agent()} from its
     * first-run {@code finalResponse} (stored in the journal). Mirrors
     * {@code DefaultWorkflowAgentInvoker.invoke}'s return contract exactly: a
     * {@code schema} opt → parse the JSON into a Java {@code Map}/{@code List}
     * (byte-identical shape to the first run); no schema → the raw String. A miss
     * is a genuine replay bug (the step should exist from the prior run).
     */
    private Object cachedAgentResult(int stepIndex, Map<String, Object> opts) {
        JournalCache cache = ctx.getJournalCache();
        if (cache == null) {
            throw new IllegalStateException(
                    "journal-replay requires a JournalCache, but none is bound (runId="
                            + ctx.getRunId() + ")");
        }
        String finalResponse = cache.getCachedAgentFinalResponse(ctx.getRunId(), stepIndex)
                .orElseThrow(() -> new IllegalStateException(
                        "journal-replay cache miss for agent() stepIndex=" + stepIndex
                                + " (runId=" + ctx.getRunId() + ")"));
        boolean schemaPresent = opts != null && opts.get("schema") != null;
        if (!schemaPresent) {
            return finalResponse;
        }
        ObjectMapper om = ctx.getObjectMapper();
        if (om == null) {
            throw new IllegalStateException("journal-replay schema parse requires an ObjectMapper");
        }
        try {
            JsonNode parsed = om.readTree(finalResponse);
            return om.convertValue(parsed, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "journal-replay parse failed for agent() stepIndex=" + stepIndex
                            + " (runId=" + ctx.getRunId() + "): " + e.getMessage(), e);
        }
    }

    private Map<String, Object> extractOpts(Object[] args) {
        Map<String, Object> opts = new HashMap<>();
        if (args.length > 1 && args[1] instanceof NativeObject no) {
            for (Object id : no.getIds()) {
                String key = String.valueOf(id);
                // Deep-convert each opt to plain Java (Task B). Matters for the
                // nested `schema` NativeObject (Task E feeds it to the JSON
                // Schema validator) — Context.jsToJava(Object.class) would leave
                // it as a Rhino Scriptable with JS-typed leaves.
                opts.put(key, JsConversions.jsToJava(no.get(key, no)));
            }
        }
        return opts;
    }
}
