package com.skillforge.workflow.bindings;

import com.skillforge.workflow.WorkflowAgentInvoker;
import com.skillforge.workflow.WorkflowContext;
import com.skillforge.workflow.WorkflowToolInvoker;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Registers the workflow host primitives + {@code args} onto a sandbox scope.
 * Registers {@code agent} / {@code tool} / {@code parallel} / {@code pipeline} /
 * {@code phase} / {@code log} / {@code humanApprove} / {@code ctx} + {@code args}
 * (Sprint 2 added {@code humanApprove} + {@code ctx}; AUTOEVOLVE-CLOSE-LOOP P1
 * added the deterministic {@code tool} binding).
 */
public final class HostBindings {

    private HostBindings() {
    }

    public static void register(Context cx, Scriptable scope, WorkflowContext ctx,
                                WorkflowAgentInvoker invoker, ExecutorService subAgentExecutor) {
        register(cx, scope, ctx, invoker, null, subAgentExecutor);
    }

    public static void register(Context cx, Scriptable scope, WorkflowContext ctx,
                                WorkflowAgentInvoker invoker, WorkflowToolInvoker toolInvoker,
                                ExecutorService subAgentExecutor) {
        define(scope, "agent", new HostAgent(ctx, invoker, subAgentExecutor));
        define(scope, "tool", new HostToolCall(ctx, toolInvoker));
        define(scope, "parallel", new HostParallel(ctx));
        define(scope, "pipeline", new HostPipeline(ctx));
        define(scope, "phase", new HostPhase(ctx));
        define(scope, "log", new HostLog(ctx));
        define(scope, "humanApprove", new HostHumanApprove(ctx));
        ScriptableObject.putProperty(scope, "ctx", HostCtx.build(cx, scope, ctx));
        ScriptableObject.putProperty(scope, "args", nativeizeArgs(cx, scope, ctx.getArgs()));
    }

    /**
     * Builds a native JS object for {@code args} via {@link JsConversions#toJs}
     * (Task B promoted the spike's local {@code deepNativeize} into the shared
     * util). We must NOT hand a raw Java {@code Map} to {@code Context.javaToJS} —
     * the sandbox {@code ClassShutter} rejects wrapping it (the same mechanism
     * that blocks {@code new java.io.File}; be-dev2 traceability footgun).
     */
    private static Scriptable nativeizeArgs(Context cx, Scriptable scope, Map<String, Object> args) {
        return (Scriptable) JsConversions.toJs(cx, scope, args);
    }

    private static void define(Scriptable scope, String name, Scriptable fn) {
        ScriptableObject.putProperty(scope, name, fn);
    }
}
