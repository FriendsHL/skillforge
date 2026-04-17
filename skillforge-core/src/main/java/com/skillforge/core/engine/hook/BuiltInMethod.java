package com.skillforge.core.engine.hook;

import java.util.Map;

/**
 * A platform-builtin method that can be referenced by {@link HookHandler.MethodHandler#getMethodRef()}.
 *
 * <p>Implementations are Spring {@code @Component} beans collected by the
 * {@code BuiltInMethodRegistry} in the server module. The registry provides whitelist-only
 * lookup by {@link #ref()} — no reflection, no arbitrary class loading.
 *
 * <p>Each method is a self-contained unit of work (log to file, HTTP POST, notify Feishu, etc.)
 * that receives merged args and the hook execution context.
 */
public interface BuiltInMethod {

    /** Unique reference key, e.g. {@code "builtin.log.file"}. Used as the lookup key. */
    String ref();

    /** Human-readable display name for the frontend methods list, e.g. {@code "Log to File"}. */
    String displayName();

    /** Short description of what this method does. */
    String description();

    /**
     * Argument schema for frontend display. Keys are arg names, values are type hints
     * (e.g. {@code "String"}, {@code "Map"}, {@code "Object"}).
     */
    Map<String, String> argsSchema();

    /**
     * Execute the method with the given merged args and hook context.
     *
     * @param args merged handler.args + runtime input (runtime wins on collision)
     * @param ctx  hook execution context (sessionId, userId, event, metadata)
     * @return non-null {@link HookRunResult}
     */
    HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx);
}
