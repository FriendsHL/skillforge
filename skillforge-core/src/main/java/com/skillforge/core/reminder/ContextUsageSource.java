package com.skillforge.core.reminder;

import com.skillforge.core.compact.RequestTokenEstimator;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.context.ContextLifecycle;
import com.skillforge.core.context.PromptCompactPolicy;
import com.skillforge.core.context.PromptPlacement;
import com.skillforge.core.llm.CompactThresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REMINDER-MVP: emits "context X% used" so the LLM proactively wraps up before the engine has
 * to compact. The most actionable signal under the 5K budget — placed first in D7 order.
 *
 * <p>Default cadence: every turn ({@code interval-turns=1}); only emits at or above
 * {@code pct-threshold} (default 70).  Hint text mentions the next compact threshold (soft 60 /
 * hard 80 / preemptive 85) so the model knows what's about to happen.
 *
 * <p>Token accounting (W3 — spec-aligned with PRD §1.4 + tech-design §1.4): uses
 * {@link RequestTokenEstimator#estimate} — the same envelope estimator the engine itself
 * runs to gate compaction (system prompt + messages + tool schemas + LLM output reservation).
 * This matches the dashboard's ContextBreakdownService, so a 70% reminder fires at the same
 * point compaction prep would. Inputs (systemPrompt / tools / requestMaxTokens / jsonMapper /
 * thresholds) come through {@link ReminderContext}, populated by AgentLoopEngine — sources
 * stay framework-free.
 *
 * <p>Per-turn cost: one estimator call per emit (W3 fix collapses former duplicate
 * shouldEmit + emit estimates into a single call by deferring the threshold check to emit).
 * shouldEmit is now O(1) (debounce + flags only); emit returns null when the ratio is below
 * threshold so the builder skips this source for the turn.
 */
public class ContextUsageSource implements ReminderSource {

    private static final Logger log = LoggerFactory.getLogger(ContextUsageSource.class);

    public static final String NAME = "context-usage";

    private final boolean enabled;
    private final int intervalTurns;
    private final int pctThreshold;

    public ContextUsageSource(boolean enabled,
                              int intervalTurns,
                              int pctThreshold) {
        this.enabled = enabled;
        this.intervalTurns = Math.max(1, intervalTurns);
        this.pctThreshold = Math.max(0, Math.min(100, pctThreshold));
    }

    @Override
    public String getName() { return NAME; }

    /**
     * Cheap check — only debounce + enabled + non-zero context window. The actual ratio
     * estimate happens once in {@link #emit} (W3 fix: avoids double-estimating per turn).
     */
    @Override
    public boolean shouldEmit(ReminderContext ctx) {
        if (!enabled || ctx == null) return false;
        if (ctx.getMaxTokens() <= 0) return false;
        return debounceElapsed(ctx);
    }

    @Override
    public ReminderEntry emit(ReminderContext ctx) {
        if (ctx == null || ctx.getMaxTokens() <= 0) return null;
        int used;
        try {
            used = RequestTokenEstimator.estimate(
                    ctx.getSystemPrompt(),
                    ctx.getMessages(),
                    ctx.getTools(),
                    ctx.getRequestMaxTokens(),
                    ctx.getJsonMapper());
        } catch (Exception e) {
            log.debug("ContextUsageSource estimate failed (skip): {}", e.toString());
            return null;
        }
        int max = ctx.getMaxTokens();
        double pct = used * 100.0 / max;
        if (pct < pctThreshold) {
            // Below threshold — skip this turn without writing debounce state, so a next-turn
            // bump still has the same fast-emit path.
            return null;
        }
        // Mark debounce only when we actually render. Q2: state lives on ReminderBuilder
        // (per-session map), not LoopContext.
        ReminderBuilder builder = ctx.getReminderBuilder();
        if (builder != null && ctx.getSessionId() != null) {
            builder.setLastEmitted(ctx.getSessionId(), NAME, ctx.getCurrentTurnIndex());
        }
        CompactThresholds thresholds = ctx.getCompactThresholds() != null
                ? ctx.getCompactThresholds() : CompactThresholds.DEFAULTS;
        String hint = nextThresholdHint(pct, thresholds);
        String text = String.format("Context: %.0f%% used (%d/%d tokens)%s",
                pct, used, max, hint.isEmpty() ? "" : ", " + hint);
        return new ReminderEntry(
                NAME,
                ReminderSourceType.CONTEXT_USAGE,
                pct >= thresholds.getPreemptiveRatio() * 100
                        ? ReminderSeverity.CRITICAL : ReminderSeverity.WARNING,
                ReminderReasonCode.CONTEXT_THRESHOLD_REACHED,
                text,
                TokenEstimator.estimateString(text),
                intervalTurns,
                PromptPlacement.BEFORE_NEXT_MODEL_CALL,
                ContextLifecycle.UNTIL_STATE_CHANGE,
                PromptCompactPolicy.DROP_ON_COMPACT,
                null);
    }

    private boolean debounceElapsed(ReminderContext ctx) {
        ReminderBuilder builder = ctx.getReminderBuilder();
        if (builder == null || ctx.getSessionId() == null) return true;
        Integer last = builder.getLastEmitted(ctx.getSessionId(), NAME);
        if (last == null) return true;
        return ctx.getCurrentTurnIndex() - last >= intervalTurns;
    }

    /**
     * Return a short hint about the next compact threshold the user is about to cross.
     * Always returns a non-empty hint (every band is covered): below soft / between soft+hard
     * / between hard+preemptive / above preemptive.
     */
    static String nextThresholdHint(double pct, CompactThresholds thresholds) {
        // pct is 0-100; thresholds are 0.0-1.0 ratios.
        double soft = thresholds.getSoftRatio() * 100;
        double hard = thresholds.getHardRatio() * 100;
        double preemptive = thresholds.getPreemptiveRatio() * 100;
        if (pct < soft) {
            return String.format("soft compact at %.0f%% (%.0f%% away)", soft, soft - pct);
        }
        if (pct < hard) {
            return String.format("soft compact at %.0f%% already crossed; hard at %.0f%% (%.0f%% away)",
                    soft, hard, hard - pct);
        }
        if (pct < preemptive) {
            return String.format("hard compact at %.0f%% already crossed; preemptive at %.0f%% (%.0f%% away)",
                    hard, preemptive, preemptive - pct);
        }
        return String.format("preemptive compact at %.0f%% already crossed — wrap up immediately",
                preemptive);
    }
}
