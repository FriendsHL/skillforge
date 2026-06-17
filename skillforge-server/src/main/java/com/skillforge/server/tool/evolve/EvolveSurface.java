package com.skillforge.server.tool.evolve;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module B — the optimisation surfaces the A/B-eval
 * tools route over. The wire value (sent by the orchestrator agent in the
 * {@code surface} field) matches the surface vocabulary already used by
 * {@code OptimizationEventEntity.SURFACE_*} and the auto-trigger listener's
 * switch ({@code OptimizationEventAutoTriggerListener}).
 *
 * <p>This is shared validation/parsing only — each tool fans out to the
 * EXISTING surface-specific service; nothing here re-implements A/B compute.
 *
 * <p><b>{@link #AGENT} is an evolve-routing-only meta-surface</b>
 * (AUTOEVOLVE-AGENT-LEVEL-BUNDLE §7 B2). It routes a <em>whole-agent</em> A/B
 * (a bundle of per-surface version pointers) and is <b>NOT</b> an
 * {@code OptimizationEventEntity.SURFACE_*} — there is no {@code SURFACE_AGENT}
 * and an opt-event can never carry {@code surface_type=agent}. Do not wire it
 * into the opt-event / attribution surface vocabulary.
 *
 * <p><b>Java enum constant ≠ per-tool advertised schema enum</b> (§7 B2): the
 * enum carries AGENT so all four exhaustive {@code switch}es compile, but each
 * tool decides whether to ADVERTISE {@code agent} in its JSON schema. Use
 * {@link #agentAbWireValues()} for the two tools that drive a whole-agent A/B
 * (TriggerAbEval / GetAbResult) and {@link #v1NonAgentWireValues()} for the
 * tools that don't take the agent surface (GenerateCandidate composes a bundle
 * from per-surface generation; PromoteCandidate / RecordIteration are V1-rejected
 * per §7 B2) so an LLM can't pick {@code surface=agent} where it would only be
 * rejected.
 */
public enum EvolveSurface {

    PROMPT("prompt"),
    SKILL("skill"),
    BEHAVIOR_RULE("behavior_rule"),
    AGENT("agent");

    private final String wire;

    EvolveSurface(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * Parse the agent-supplied {@code surface} value. Returns {@code null} for a
     * null/blank/unknown value so the calling tool can surface a clean
     * validation error (rather than throwing here and forcing a try/catch).
     */
    public static EvolveSurface fromWire(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (EvolveSurface s : values()) {
            if (s.wire.equals(trimmed)) {
                return s;
            }
        }
        return null;
    }

    /** Comma-separated list of accepted wire values, for error messages / schema. */
    public static String acceptedValues() {
        return Arrays.stream(values())
                .map(EvolveSurface::wire)
                .collect(Collectors.joining(", "));
    }

    /**
     * Comma-separated list of the V1 non-agent wire values, for the
     * unknown-surface error message of the tools that don't accept {@code agent}
     * (GenerateCandidate / PromoteCandidate / RecordIteration). Keeps the error
     * text consistent with what those tools actually accept (§7 B2 — never tell
     * the LLM {@code agent} is valid on a tool that only rejects it).
     */
    public static String v1NonAgentAcceptedValues() {
        return String.join(", ", v1NonAgentWireValues());
    }

    /**
     * Wire values advertised by the two tools that drive a whole-agent A/B —
     * TriggerAbEval / GetAbResult. Includes {@code agent}.
     */
    public static List<String> agentAbWireValues() {
        return List.of(PROMPT.wire(), SKILL.wire(), BEHAVIOR_RULE.wire(), AGENT.wire());
    }

    /**
     * Wire values advertised by the tools that do NOT take the agent surface in
     * V1 — GenerateCandidate (composes a bundle from per-surface generation),
     * PromoteCandidate / RecordIteration (§7 B2 V1-rejected). Excludes
     * {@code agent} so an LLM can't pick a surface the tool will only reject.
     */
    public static List<String> v1NonAgentWireValues() {
        return List.of(PROMPT.wire(), SKILL.wire(), BEHAVIOR_RULE.wire());
    }
}
