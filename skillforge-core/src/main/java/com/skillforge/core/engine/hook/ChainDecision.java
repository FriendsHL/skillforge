package com.skillforge.core.engine.hook;

/**
 * Decision the dispatcher makes after running a single {@link HookEntry} — determines
 * whether the chain of entries for the same event keeps running.
 *
 * <ul>
 *   <li>{@link #CONTINUE} — fallthrough to the next entry (normal case, and the case when
 *       a failure is paired with {@link FailurePolicy#CONTINUE}).</li>
 *   <li>{@link #ABORT} — interrupt the main flow. Dispatcher returns {@code false} and the
 *       adapter layer sets {@code LoopContext.abortedByHook}. Only meaningful for synchronous
 *       pre-flow events; async entries never produce ABORT.</li>
 *   <li>{@link #SKIP_CHAIN} — stop running the remaining entries for this event, but let the
 *       main flow continue. Dispatcher returns {@code true}.</li>
 * </ul>
 *
 * <p>Computed by the dispatcher from {@code (success, entry.failurePolicy)} — runners do not
 * decide the chain outcome, they only report how their own execution went.
 */
public enum ChainDecision {
    CONTINUE,
    ABORT,
    SKIP_CHAIN
}
