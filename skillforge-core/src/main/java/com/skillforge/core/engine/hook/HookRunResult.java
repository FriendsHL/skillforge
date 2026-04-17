package com.skillforge.core.engine.hook;

/**
 * Handler execution result, intentionally decoupled from {@code SkillResult}:
 * not every handler runs a Skill (P1/P2 runners invoke scripts / builtin methods).
 */
public record HookRunResult(boolean success, String output, String errorMessage, long durationMs) {

    public static HookRunResult ok(String output, long durationMs) {
        return new HookRunResult(true, output, null, durationMs);
    }

    public static HookRunResult failure(String errorMessage, long durationMs) {
        return new HookRunResult(false, null, errorMessage, durationMs);
    }
}
