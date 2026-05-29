package com.skillforge.workflow.bindings;

import com.skillforge.workflow.WorkflowContext;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Wrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code parallel(thunks)} host binding — offload concurrency model
 * (plan §2.1):
 *
 * <ol>
 *   <li>Set {@code inParallelCollect=true} and evaluate each thunk
 *       <em>sequentially on the workflow thread</em>. Each thunk's tail is an
 *       {@code agent()} call which (in collect mode) submits {@code engine.run}
 *       to the sub-agent executor and returns a {@link PendingAgentCall}.</li>
 *   <li>Clear the flag, then barrier-join all futures. The N {@code engine.run}
 *       calls run concurrently on worker threads (they never touch Rhino).</li>
 *   <li>Build the result array in <em>call order</em>; a thunk/future that threw
 *       maps to {@code null} (plan §2.4 #3c/#3d).</li>
 * </ol>
 *
 * <p>The {@code javaToJS} result conversion happens on the workflow thread (Rhino
 * thread) after the barrier — never cross-thread.
 */
public final class HostParallel extends BaseFunction {

    private static final Logger log = LoggerFactory.getLogger(HostParallel.class);

    private final transient WorkflowContext ctx;

    public HostParallel(WorkflowContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String getFunctionName() {
        return "parallel";
    }

    @Override
    public int getArity() {
        return 1;
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (args.length == 0 || !(args[0] instanceof NativeArray thunks)) {
            throw Context.reportRuntimeError("parallel(thunks) requires an array of functions");
        }
        int n = (int) thunks.getLength();

        // Phase 1: evaluate thunks sequentially on the workflow thread (collect mode).
        List<PendingAgentCall> pendings = new ArrayList<>(n);
        boolean prev = ctx.isInParallelCollect();
        ctx.setInParallelCollect(true);
        try {
            for (int i = 0; i < n; i++) {
                Object thunkObj = thunks.get(i, thunks);
                if (!(thunkObj instanceof Function thunk)) {
                    throw Context.reportRuntimeError(
                            "parallel() element " + i + " is not a function");
                }
                Object r = unwrap(thunk.call(cx, scope, scope, EMPTY));
                if (r instanceof PendingAgentCall pac) {
                    pendings.add(pac);
                } else {
                    // V1 constraint (plan §2.2): thunk tail must be an agent() call.
                    // Anything else is unsupported in the offload model.
                    throw Context.reportRuntimeError(
                            "parallel() thunk " + i + " did not tail-call agent()");
                }
            }
        } finally {
            ctx.setInParallelCollect(prev);
        }

        // Phase 2: barrier-join. Failures map to null (per-position), other
        // branches unaffected.
        Object[] results = new Object[pendings.size()];
        for (int i = 0; i < pendings.size(); i++) {
            try {
                Object value = pendings.get(i).getFuture().join();
                // Result may be a schema-parsed Map/List (Task E) — JsConversions
                // .toJs, never raw Context.javaToJS (ClassShutter footgun).
                results[i] = JsConversions.toJs(cx, scope, value);
            } catch (RuntimeException ex) {
                log.warn("[parallel] branch {} failed (runId={}): {}",
                        i, ctx.getRunId(), ex.getMessage());
                results[i] = null;
            }
        }
        return cx.newArray(scope, results);
    }

    private static final Object[] EMPTY = new Object[0];

    private static Object unwrap(Object o) {
        return (o instanceof Wrapper w) ? w.unwrap() : o;
    }
}
