package com.skillforge.core.engine.confirm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code (toolName, installTarget)} from an install-pattern-matching Bash command.
 *
 * <p>Paired with {@link com.skillforge.core.engine.DangerousCommandChecker#CONFIRMATION_REQUIRED_PATTERNS}
 * — caller only invokes {@link #parse(String)} after a {@code CONFIRMATION_REQUIRED_PATTERNS}
 * hit, so {@code toolName == "unknown"} is a defensive fallback and not an expected output.
 *
 * <p>{@code installTarget == "*"} is the conservative sentinel for unparseable / multi-install
 * commands. {@link SessionConfirmCache} refuses to read or write cache entries with target
 * {@code "*"}, forcing the user to re-confirm on every invocation.
 *
 * <p>Security hardening (r3 + r4):
 * <ul>
 *   <li>{@code clawhub install a && clawhub install b} — count of install patterns > 1
 *       → target {@code "*"} (prevents "user approved a, attacker concatenated b").</li>
 *   <li>{@code clawhub install --force pkg} — flag token as target would let
 *       "{@code clawhub install --force MALICIOUS_PKG}" reuse the cache; target starting
 *       with {@code "-"} is normalized to {@code "*"}.</li>
 * </ul>
 */
public final class InstallTargetParser {

    public record Parsed(String toolName, String installTarget) {}

    /** target token: npm-style package name (letters, digits, dot, underscore, hyphen, slash, @). */
    private static final String TARGET_TOKEN = "(@?[A-Za-z0-9._/\\-]+)";

    private static final Pattern CLAWHUB =
            Pattern.compile("\\bclawhub\\s+install\\s+" + TARGET_TOKEN);
    private static final Pattern SKILLHUB =
            Pattern.compile("\\bskillhub\\s+install\\s+" + TARGET_TOKEN);
    private static final Pattern SKILLHUB2 =
            Pattern.compile("\\bskill-hub/cli\\s+install\\s+" + TARGET_TOKEN);

    private InstallTargetParser() {}

    /**
     * Parse (toolName, installTarget) from an install command.
     *
     * @param command raw Bash command string (may be null)
     * @return parsed result; {@code target == "*"} or {@code toolName ∈ {"unknown","multiple"}}
     *         signals the caller to go "always re-prompt, never cache".
     */
    public static Parsed parse(String command) {
        if (command == null) {
            return new Parsed("unknown", "*");
        }
        int installCount = countMatches(CLAWHUB, command)
                + countMatches(SKILLHUB, command)
                + countMatches(SKILLHUB2, command);
        if (installCount != 1) {
            // No single well-formed install or multiple installs — fail-closed to "*".
            return new Parsed("multiple", "*");
        }
        Matcher m;
        if ((m = CLAWHUB.matcher(command)).find()) {
            return normalize("clawhub", m.group(1));
        }
        if ((m = SKILLHUB2.matcher(command)).find()) {
            return normalize("skill-hub", m.group(1));
        }
        if ((m = SKILLHUB.matcher(command)).find()) {
            return normalize("skillhub", m.group(1));
        }
        return new Parsed("unknown", "*");
    }

    /**
     * Reject flag-prefix tokens as install targets to prevent cache-key pollution.
     * e.g. {@code clawhub install --force foo} — regex captures {@code "--force"}; if we
     * accepted that as target, a later {@code clawhub install --force MALICIOUS_PKG}
     * would reuse the approved entry.
     */
    private static Parsed normalize(String toolName, String rawTarget) {
        if (rawTarget == null || rawTarget.isEmpty()
                || rawTarget.startsWith("-")
                || "*".equals(rawTarget)) {
            return new Parsed(toolName, "*");
        }
        return new Parsed(toolName, rawTarget);
    }

    private static int countMatches(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }
}
