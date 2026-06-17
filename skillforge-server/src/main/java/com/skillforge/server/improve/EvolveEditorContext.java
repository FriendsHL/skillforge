package com.skillforge.server.improve;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL reflection (config + history aware evolve-editor).
 *
 * <p>Carries the orchestrator-fed reflection inputs into the prompt-candidate
 * generation step so the candidate-producing LLM call ("evolve-editor") can make
 * a smarter next change: it sees what was changed last round
 * ({@code priorChangeSummary}) and last round's per-case eval report
 * ({@code priorEvalReportJson}: which scenarios improved / regressed + reasons +
 * overall delta).
 *
 * <p><b>Presence vs content.</b> Both fields are nullable — the FIRST evolve
 * round has no prior round, so both are null. But the OBJECT's mere PRESENCE
 * (non-null {@code EvolveEditorContext}) is what signals "evolve-editor mode" to
 * {@link PromptImproverService}: it switches the generation to use the seeded
 * {@code evolve-editor} agent's system prompt + appends the target-agent config
 * + reflection blocks. When the context is {@code null}, generation is
 * BYTE-IDENTICAL to the legacy (non-evolve attribution) behavior — this is the
 * gate that keeps {@code AttributionApprovalService}'s shared call path
 * unchanged.
 *
 * @param priorChangeSummary what was changed last round (the orchestrator's
 *                           {@code changeDesc}); {@code null} on the first round
 * @param priorEvalReportJson last round's eval report (per-case improved/regressed
 *                            + reasons + overall delta), compact JSON or prose;
 *                            {@code null} on the first round
 */
public record EvolveEditorContext(String priorChangeSummary, String priorEvalReportJson) {
}
