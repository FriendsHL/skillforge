package com.skillforge.workflow;

import com.skillforge.workflow.bindings.HostBindings;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
// WorkflowToolInvoker is in the same package (com.skillforge.workflow).
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.util.concurrent.ExecutorService;

/**
 * Sprint-1 spike workflow runner: glue between the await preprocessor, the L1
 * sandbox, the host bindings, and Rhino {@code evaluateString}. Task G replaces
 * this with the full {@code WorkflowRunnerService} (lock → startRun → anchor
 * session → evaluate → markCompleted/markError + meta extraction).
 *
 * <p>Body wrapping: the DSL allows a top-level {@code return} (workflow scripts
 * return a result object). A bare {@code return} is illegal at script top level
 * in Rhino, so the body is wrapped in an immediately-invoked function. Task F/G
 * add {@code export const meta} stripping + pure-literal validation; the spike
 * accepts an already-meta-free body.
 */
public final class WorkflowEvaluator {

    private final L1SandboxFactory sandboxFactory;

    public WorkflowEvaluator(L1SandboxFactory sandboxFactory) {
        this.sandboxFactory = sandboxFactory;
    }

    /**
     * Evaluates a (meta-free) workflow body and returns the raw Rhino result.
     * Caller-supplied {@code ctx} carries the run id, args, and budget. The
     * {@code tool()} binding is unavailable on this overload (back-compat for the
     * Sprint-1 spike tests that predate {@code tool()}).
     */
    public Object evaluate(String body, WorkflowContext ctx,
                           WorkflowAgentInvoker invoker, ExecutorService subAgentExecutor) {
        return evaluate(body, ctx, invoker, null, subAgentExecutor);
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 overload — additionally binds the deterministic
     * {@code tool()} host function via {@code toolInvoker}. A {@code null}
     * {@code toolInvoker} leaves {@code tool()} present but unusable (it throws),
     * matching the behaviour of a workflow that never calls it.
     */
    public Object evaluate(String body, WorkflowContext ctx,
                           WorkflowAgentInvoker invoker, WorkflowToolInvoker toolInvoker,
                           ExecutorService subAgentExecutor) {
        String stripped = AwaitPreprocessor.stripAwait(body);
        String wrapped = "(function(){\n" + stripped + "\n})();";

        Context cx = sandboxFactory.enter(ctx.getBudget());
        try {
            Scriptable scope = sandboxFactory.createSafeScope(cx);
            HostBindings.register(cx, scope, ctx, invoker, toolInvoker, subAgentExecutor);
            return cx.evaluateString(scope, wrapped, ctx.getRunId(), 1, null);
        } finally {
            Context.exit();
        }
    }

    /**
     * Evaluates raw script source directly (no IIFE wrap) — used by the sandbox
     * security tests to assert dangerous expressions throw. The body must be a
     * complete expression/statement that is legal at top level.
     */
    public Object evaluateRaw(String source, WorkflowContext ctx,
                              WorkflowAgentInvoker invoker, ExecutorService subAgentExecutor) {
        String stripped = AwaitPreprocessor.stripAwait(source);
        Context cx = sandboxFactory.enter(ctx.getBudget());
        try {
            Scriptable scope = sandboxFactory.createSafeScope(cx);
            HostBindings.register(cx, scope, ctx, invoker, subAgentExecutor);
            return cx.evaluateString(scope, stripped, ctx.getRunId(), 1, null);
        } finally {
            Context.exit();
        }
    }
}
