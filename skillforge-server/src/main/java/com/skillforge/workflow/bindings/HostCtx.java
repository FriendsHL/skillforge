package com.skillforge.workflow.bindings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.workflow.WorkflowContext;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * The {@code ctx} host object (AUTOEVOLVING V1 Sprint 2, PRD FR-1.2 /
 * dsl-syntax). Exposes per-run metadata + a JSON helper to workflow scripts:
 *
 * <ul>
 *   <li>{@code ctx.runId()} → the {@code t_flywheel_run} id (String);</li>
 *   <li>{@code ctx.json(value)} → serialize a JS value to a compact JSON String
 *       (e.g. to embed structured data inside an {@code agent()} prompt). Uses
 *       the Spring-managed {@link ObjectMapper} (JavaTimeModule configured —
 *       java.md footgun #1) via {@link WorkflowContext#getObjectMapper()}.</li>
 * </ul>
 *
 * <p>{@code ctx} is a plain object whose {@code runId}/{@code json} properties
 * are host functions — not a {@code BaseFunction} itself — so it is built via
 * {@link #build} rather than registered like the {@code agent()}/{@code parallel()}
 * function bindings.
 */
public final class HostCtx {

    private HostCtx() {
    }

    /** Builds the {@code ctx} object for the given scope. */
    public static Scriptable build(Context cx, Scriptable scope, WorkflowContext ctx) {
        Scriptable obj = cx.newObject(scope);
        ScriptableObject.putProperty(obj, "runId", new RunIdFn(ctx));
        ScriptableObject.putProperty(obj, "json", new JsonFn(ctx));
        return obj;
    }

    private static final class RunIdFn extends BaseFunction {
        private final transient WorkflowContext ctx;

        RunIdFn(WorkflowContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String getFunctionName() {
            return "runId";
        }

        @Override
        public int getArity() {
            return 0;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            return ctx.getRunId();
        }
    }

    private static final class JsonFn extends BaseFunction {
        private final transient WorkflowContext ctx;

        JsonFn(WorkflowContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String getFunctionName() {
            return "json";
        }

        @Override
        public int getArity() {
            return 1;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            ObjectMapper om = ctx.getObjectMapper();
            if (om == null) {
                throw Context.reportRuntimeError(
                        "ctx.json() is not available in this run context (no ObjectMapper bound)");
            }
            Object value = (args.length == 0) ? null : JsConversions.jsToJava(args[0]);
            try {
                return om.writeValueAsString(value);
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                throw Context.reportRuntimeError("ctx.json() serialization failed: " + e.getMessage());
            }
        }
    }
}
