package com.skillforge.core.engine.hook;

/**
 * Handler execution result, intentionally decoupled from {@code SkillResult}:
 * not every handler runs a Tool (P1/P2 runners invoke scripts / builtin methods).
 *
 * <p>{@link #chainDecision} is filled in by the dispatcher after computing
 * {@code (success, entry.failurePolicy)} — runners do not populate it and should use the
 * {@link #ok(String, long)} / {@link #failure(String, long)} factories, which default to
 * {@link ChainDecision#CONTINUE}.
 */
public record HookRunResult(boolean success,
                            String output,
                            String errorMessage,
                            long durationMs,
                            ChainDecision chainDecision) {

    public HookRunResult(boolean success, String output, String errorMessage, long durationMs) {
        this(success, output, errorMessage, durationMs, ChainDecision.CONTINUE);
    }

    public static HookRunResult ok(String output, long durationMs) {
        return new HookRunResult(true, output, null, durationMs, ChainDecision.CONTINUE);
    }

    public static HookRunResult failure(String errorMessage, long durationMs) {
        return new HookRunResult(false, null, errorMessage, durationMs, ChainDecision.CONTINUE);
    }

    /** Returns a copy of this result with the chainDecision replaced. */
    public HookRunResult withChainDecision(ChainDecision decision) {
        return new HookRunResult(success, output, errorMessage, durationMs, decision);
    }
}
