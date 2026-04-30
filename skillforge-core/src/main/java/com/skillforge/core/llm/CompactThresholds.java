package com.skillforge.core.llm;

/**
 * Per-provider thresholds (ratios in [0, 1]) controlling when AgentLoopEngine triggers
 * the three compact paths:
 * <ul>
 *   <li>{@link #softRatio} — B1 engine-soft light compact (default 0.60)</li>
 *   <li>{@link #hardRatio} — B2 engine-hard full compact, gated on B1 having run (default 0.80)</li>
 *   <li>{@link #preemptiveRatio} — preemptive full compact before LLM call (default 0.85)</li>
 * </ul>
 *
 * <p>Lives in {@code core.llm} so {@link LlmProvider} can expose
 * {@link LlmProvider#getCompactThresholds()} without core depending on the server module.
 * {@code LlmProperties.CompactThresholds} (in server) is mapped onto this value object
 * during provider wiring.
 *
 * <p>Immutable. Use {@link #DEFAULTS} when nothing is configured.
 */
public final class CompactThresholds {

    /** Default values match historical hard-coded thresholds in AgentLoopEngine. */
    public static final CompactThresholds DEFAULTS = new CompactThresholds(0.60, 0.80, 0.85);

    private final double softRatio;
    private final double hardRatio;
    private final double preemptiveRatio;

    public CompactThresholds(double softRatio, double hardRatio, double preemptiveRatio) {
        this.softRatio = softRatio;
        this.hardRatio = hardRatio;
        this.preemptiveRatio = preemptiveRatio;
    }

    public double getSoftRatio() {
        return softRatio;
    }

    public double getHardRatio() {
        return hardRatio;
    }

    public double getPreemptiveRatio() {
        return preemptiveRatio;
    }

    @Override
    public String toString() {
        return "CompactThresholds{soft=" + softRatio + ", hard=" + hardRatio
                + ", preemptive=" + preemptiveRatio + "}";
    }
}
