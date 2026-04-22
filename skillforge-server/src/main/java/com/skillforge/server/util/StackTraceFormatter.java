package com.skillforge.server.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Formats a {@link Throwable} (and its Caused-by chain) into a compact, bounded
 * text representation suitable for persisting into DB columns like
 * {@code t_session.runtime_error} and for broadcasting on WebSocket status
 * payloads.
 *
 * <p>Each cause level emits at most {@code maxFramesPerCause} stack frames; the
 * remainder is summarized as {@code ... N more frames}. Cycle-safe via an
 * identity-based seen set.
 *
 * <p><b>Security note:</b> exception messages reachable from this formatter may
 * contain sensitive data — internal IPs, request bodies, auth tokens, or other
 * user-controlled content — when upstream exceptions are constructed from
 * untrusted input. Current callers expose the output to trusted dev/operator
 * surfaces (DB column, authenticated WS stream, browser console). If the
 * output is ever surfaced to untrusted end-users, add a redaction layer first.
 */
public final class StackTraceFormatter {

    private StackTraceFormatter() {
    }

    /**
     * Format the given throwable plus its Caused-by chain.
     *
     * @param t                  the throwable to format; {@code null} returns an empty string
     * @param maxFramesPerCause  maximum frames to emit per cause level; values &lt; 0 are clamped to 0
     * @return a newline-separated string with no trailing newline
     */
    public static String format(Throwable t, int maxFramesPerCause) {
        if (t == null) {
            return "";
        }
        int maxFrames = Math.max(0, maxFramesPerCause);
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        Throwable current = t;
        boolean first = true;
        while (current != null && seen.add(current)) {
            if (!first) {
                sb.append('\n');
            }
            appendHeader(sb, current, first);
            appendFrames(sb, current.getStackTrace(), maxFrames);
            first = false;
            current = current.getCause();
        }
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb, Throwable t, boolean isRoot) {
        if (!isRoot) {
            sb.append("Caused by: ");
        }
        sb.append(t.getClass().getName());
        String msg = t.getMessage();
        if (msg != null) {
            sb.append(": ").append(msg);
        }
    }

    private static void appendFrames(StringBuilder sb, StackTraceElement[] frames, int maxFrames) {
        int total = frames == null ? 0 : frames.length;
        int shown = Math.min(total, maxFrames);
        for (int i = 0; i < shown; i++) {
            sb.append('\n').append('\t').append("at ").append(frames[i].toString());
        }
        int omitted = total - shown;
        if (omitted > 0) {
            sb.append('\n').append('\t').append("... ").append(omitted).append(" more frames");
        }
    }
}
