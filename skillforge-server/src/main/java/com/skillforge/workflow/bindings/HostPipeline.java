package com.skillforge.workflow.bindings;

import com.skillforge.workflow.WorkflowContext;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Wrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code pipeline(items, stage1, stage2, ...)} host binding — <b>V1 serial
 * implementation</b> (plan §2.1).
 *
 * <p>Unlike {@code parallel()}, a pipeline stage is a JS callback
 * ({@code r => agent(...)}) whose evaluation reads the Rhino scope, so it cannot
 * be offloaded to a worker thread (Rhino is single-threaded). The V1 decision is
 * therefore a <em>serial</em> pipeline: each item flows through every stage in
 * order, one item fully before the next, with each {@code agent()} call blocking
 * inline (sequential context, not parallel-collect). The semantics are correct
 * (every item independently flows through all stages; a stage that throws drops
 * that item to {@code null} and skips its remaining stages) — only the
 * concurrency of the fully-pipelined version (dsl-syntax §14, V2) is deferred.
 *
 * <p>Per the "no silent cap" rule (plan §2.1) the binding emits a {@code log()}
 * line announcing the serial execution so callers do not mistake it for the
 * concurrent V2 behaviour.
 *
 * <p>Each stage callback receives {@code (prevResult, originalItem, index)}. For
 * the first stage {@code prevResult} is the original item itself.
 */
public final class HostPipeline extends BaseFunction {

    private final transient WorkflowContext ctx;

    public HostPipeline(WorkflowContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String getFunctionName() {
        return "pipeline";
    }

    @Override
    public int getArity() {
        return 2;
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (args.length == 0 || !(args[0] instanceof NativeArray items)) {
            throw Context.reportRuntimeError(
                    "pipeline(items, ...stages) requires an array of items as the first argument");
        }

        // Collect stage callbacks (args[1..]). Empty stage list is allowed:
        // each item passes through unchanged.
        List<Function> stages = new ArrayList<>(Math.max(0, args.length - 1));
        for (int s = 1; s < args.length; s++) {
            if (!(args[s] instanceof Function stage)) {
                throw Context.reportRuntimeError(
                        "pipeline() stage " + (s - 1) + " is not a function");
            }
            stages.add(stage);
        }

        int n = (int) items.getLength();

        // No silent cap (plan §2.1): make the V1 serial execution explicit so the
        // caller does not assume the concurrent V2 pipeline semantics.
        ctx.recordLog("[workflow] pipeline: V1 串行执行 (" + n + " items × "
                + stages.size() + " stages, no concurrency — dsl-syntax §14)");

        Object[] results = new Object[n];
        for (int i = 0; i < n; i++) {
            Object originalItem = items.get(i, items);
            Object current = originalItem;
            Object indexArg = Double.valueOf(i); // JS number
            boolean dropped = false;
            for (Function stage : stages) {
                try {
                    Object out = stage.call(cx, scope, scope,
                            new Object[]{current, originalItem, indexArg});
                    current = unwrap(out);
                } catch (RuntimeException ex) {
                    // Stage threw for this item → drop to null, skip remaining
                    // stages. Other items are unaffected (per-item isolation).
                    current = null;
                    dropped = true;
                    break;
                }
            }
            // current may be an already-JS value (a NativeObject returned by a
            // prior agent()) or a schema-parsed Map/List — JsConversions.toJs
            // handles both; raw Context.javaToJS would trip the ClassShutter on
            // a Map (Task B/E footgun).
            results[i] = (dropped || current == null) ? null : JsConversions.toJs(cx, scope, current);
        }
        return cx.newArray(scope, results);
    }

    private static Object unwrap(Object o) {
        return (o instanceof Wrapper w) ? w.unwrap() : o;
    }
}
