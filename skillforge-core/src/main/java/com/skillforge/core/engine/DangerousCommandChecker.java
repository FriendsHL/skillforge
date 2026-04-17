package com.skillforge.core.engine;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Best-effort static scanner for dangerous shell patterns. Used by:
 * <ul>
 *   <li>{@link SafetySkillHook} — blocks Bash skill input when the LLM asks for destructive commands.</li>
 *   <li>{@code ScriptHandlerRunner} — blocks user-supplied hook scripts before spawning a process.</li>
 * </ul>
 *
 * <p><b>Limitations.</b> Regex on raw script text cannot defeat {@code eval "$(echo cm0gLXJmIC8K | base64 -d)"}
 * or arbitrarily quoted constructs. This is a guard against accidental footguns, not a sandbox.
 * For production, disable {@code lifecycle.hooks.script.allowed-langs} entirely.
 */
public final class DangerousCommandChecker {

    /**
     * Patterns that require explicit user confirmation before running. Caller treats a hit the same
     * as a dangerous hit for hook scripts (no ask_user plumbing in the hook runner).
     */
    public static final List<Pattern> CONFIRMATION_REQUIRED_PATTERNS = List.of(
            Pattern.compile("\\bclawhub\\s+install\\b"),
            Pattern.compile("\\bskill-hub/cli\\s+install\\b"),
            Pattern.compile("\\bskillhub\\s+install\\b")
    );

    /** Destructive / privileged / RCE patterns. Kept in sync with SafetySkillHook. */
    public static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // Destructive delete commands
            Pattern.compile("rm\\s+-rf\\s+/(?:\\s|$)"),
            Pattern.compile("rm\\s+-rf\\s+~(?:\\s|$|/)"),
            Pattern.compile("rm\\s+-rf\\s+\\.(?:\\s|$)"),
            // Privilege escalation
            Pattern.compile("\\bsudo\\b"),
            // Disk formatting
            Pattern.compile("\\bmkfs\\b"),
            // Disk operations
            Pattern.compile("\\bdd\\s+if="),
            // Fork bomb
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
            // Write to disk device
            Pattern.compile(">\\s*/dev/sda"),
            // Global permission change
            Pattern.compile("chmod\\s+-R\\s+777\\s+/(?:\\s|$)"),
            // System shutdown/reboot
            Pattern.compile("\\bshutdown\\b"),
            Pattern.compile("\\breboot\\b"),
            Pattern.compile("\\bhalt\\b"),
            Pattern.compile("\\bpoweroff\\b"),
            // Remote code execution — absolute-path variants (/bin/bash etc.) are also blocked.
            // Note: eval/base64/python3 -c pipelines can still bypass this best-effort guard.
            Pattern.compile("curl\\s+.*\\|\\s*(/\\S+/)?sh(?:\\s|$)"),
            Pattern.compile("curl\\s+.*\\|\\s*(/\\S+/)?bash(?:\\s|$)"),
            Pattern.compile("wget\\s+.*\\|\\s*(/\\S+/)?sh(?:\\s|$)"),
            Pattern.compile("wget\\s+.*\\|\\s*(/\\S+/)?bash(?:\\s|$)")
    );

    private DangerousCommandChecker() {
        // utility class
    }

    /**
     * Scan a script body / command string for dangerous patterns.
     *
     * @return pattern string matched, or {@code null} if nothing matched.
     */
    public static String firstDangerousMatch(String commandOrScript) {
        if (commandOrScript == null || commandOrScript.isEmpty()) return null;
        for (Pattern p : CONFIRMATION_REQUIRED_PATTERNS) {
            if (p.matcher(commandOrScript).find()) return p.pattern();
        }
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(commandOrScript).find()) return p.pattern();
        }
        return null;
    }

    /** Convenience: true when any dangerous pattern hits. */
    public static boolean isDangerous(String commandOrScript) {
        return firstDangerousMatch(commandOrScript) != null;
    }
}
