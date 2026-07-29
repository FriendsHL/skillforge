package com.skillforge.core.reminder;

import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.context.ContextLifecycle;
import com.skillforge.core.context.PromptCompactPolicy;
import com.skillforge.core.context.PromptPlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * REMINDER-MVP: emits a "memory landscape" reminder so the LLM remembers it has long-term
 * memories that may be relevant — and how stale they are.
 *
 * <p>Default cadence: every 5 turns ({@code interval-turns=5}); only emits when there is at
 * least one stale memory ({@code lastRecalledAt} older than {@code stale-days-threshold}, or
 * never recalled).
 *
 * <p>DB cost (W2 fix): one aggregate query per emit via {@link MemoryAgeStatsProvider#getStats}
 * — was 3 separate round-trips with a TOCTOU race window. {@link #shouldEmit} now does pure
 * Java debounce checks (no DB I/O); {@link #emit} runs the single combined query and returns
 * {@code null} if {@code staleCount == 0} (the builder skips this source for the turn — same
 * pattern as {@link FileActivitySource}).
 *
 * <p>Source name {@code "memory-age"} is the per-session debounce key on
 * {@link ReminderBuilder} (Q2 cache-friendly migration) and the yaml prefix
 * ({@code skillforge.reminder.memory-age.*}).
 */
public class MemoryAgeSource implements ReminderSource {

    private static final Logger log = LoggerFactory.getLogger(MemoryAgeSource.class);

    public static final String NAME = "memory-age";

    private final MemoryAgeStatsProvider stats;
    private final boolean enabled;
    private final int intervalTurns;
    private final int staleDaysThreshold;

    public MemoryAgeSource(MemoryAgeStatsProvider stats,
                           boolean enabled,
                           int intervalTurns,
                           int staleDaysThreshold) {
        this.stats = stats;
        this.enabled = enabled;
        this.intervalTurns = Math.max(1, intervalTurns);
        this.staleDaysThreshold = Math.max(1, staleDaysThreshold);
    }

    @Override
    public String getName() { return NAME; }

    /**
     * Cheap O(1) check: enabled + non-null deps + debounce gap. The actual stale lookup is
     * deferred to {@link #emit} (W2 fix: avoids DB roundtrip on every shouldEmit call).
     */
    @Override
    public boolean shouldEmit(ReminderContext ctx) {
        if (!enabled || stats == null || ctx == null || ctx.getUserId() == null) return false;
        return debounceElapsed(ctx);
    }

    @Override
    public ReminderEntry emit(ReminderContext ctx) {
        if (ctx == null || ctx.getUserId() == null) return null;
        MemoryAgeStatsProvider.Stats s;
        try {
            s = stats.getStats(ctx.getUserId(), staleDaysThreshold);
        } catch (Exception e) {
            log.debug("MemoryAgeSource getStats failed (skip): {}", e.toString());
            return null;
        }
        if (s == null) return null;
        // No stale memories → nothing actionable to emit. Don't write debounce state so the
        // next turn can re-check cheaply. Mirror of FileActivitySource's "all too young" path.
        if (s.staleCount() <= 0) return null;
        // Mark debounce only when we actually render. Q2: state lives on ReminderBuilder.
        ReminderBuilder builder = ctx.getReminderBuilder();
        if (builder != null && ctx.getSessionId() != null) {
            builder.setLastEmitted(ctx.getSessionId(), NAME, ctx.getCurrentTurnIndex());
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append("Memory: ").append(s.activeCount()).append(" active entries, ")
                .append(s.staleCount()).append(" stale (>")
                .append(staleDaysThreshold).append(" days)");
        s.lastRecalledAt().ifPresent(t -> sb.append(", last recalled ").append(formatAge(t)));
        String text = sb.toString();
        return new ReminderEntry(
                NAME,
                ReminderSourceType.MEMORY_AGE,
                ReminderSeverity.INFO,
                ReminderReasonCode.STALE_MEMORY_AVAILABLE,
                text,
                TokenEstimator.estimateString(text),
                intervalTurns,
                PromptPlacement.BEFORE_NEXT_MODEL_CALL,
                ContextLifecycle.UNTIL_STATE_CHANGE,
                PromptCompactPolicy.RELOAD_BY_ID,
                null);
    }

    private boolean debounceElapsed(ReminderContext ctx) {
        ReminderBuilder builder = ctx.getReminderBuilder();
        if (builder == null || ctx.getSessionId() == null) return true;
        Integer last = builder.getLastEmitted(ctx.getSessionId(), NAME);
        if (last == null) return true;
        return ctx.getCurrentTurnIndex() - last >= intervalTurns;
    }

    /** Render an Instant as e.g. "2 min ago" / "3h ago" / "5d ago". Best-effort coarse format. */
    static String formatAge(Instant t) {
        if (t == null) return "?";
        Duration d = Duration.between(t, Instant.now());
        if (d.isNegative()) return "just now";
        long sec = d.getSeconds();
        if (sec < 60) return sec + "s ago";
        long min = sec / 60;
        if (min < 60) return min + " min ago";
        long hours = min / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }
}
