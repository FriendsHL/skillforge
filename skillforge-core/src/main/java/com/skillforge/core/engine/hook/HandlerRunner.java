package com.skillforge.core.engine.hook;

import java.util.Map;

/**
 * Strategy interface for handler execution — one implementation per {@link HookHandler} subtype.
 *
 * <p>Dispatcher routes to the correct runner by matching {@link #handlerType()} against the
 * handler's runtime class. Runners must not mutate the passed-in input map.
 *
 * @param <H> the specific {@link HookHandler} subtype this runner handles
 */
public interface HandlerRunner<H extends HookHandler> {

    /** Subtype this runner handles. Used as the routing key in the dispatcher. */
    Class<H> handlerType();

    /**
     * Run the handler with the given input and context.
     *
     * @param handler the static handler configuration
     * @param input   the per-event runtime input (see {@code docs/design-n3-lifecycle-hooks.md §3.4})
     * @param ctx     hook execution context (sessionId, userId, metadata)
     * @return non-null {@link HookRunResult}; runners must not return null
     */
    HookRunResult run(H handler, Map<String, Object> input, HookExecutionContext ctx);
}
