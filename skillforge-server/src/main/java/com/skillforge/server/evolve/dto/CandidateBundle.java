package com.skillforge.server.evolve.dto;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — the candidate bundle pointer tuple recorded as a
 * sidecar on an {@code evolve_iteration} step ({@code step_output_json.candidateBundle},
 * written by {@code RecordIterationTool}). Each pointer is {@code null} when the
 * winning bundle made no change on that surface.
 *
 * <p>footgun #6 contract — FE mirror ({@code api/evolve.ts}):
 * <pre>
 * interface CandidateBundle {
 *   promptVersionId: string | null
 *   behaviorRuleVersionId: string | null
 *   skillDraftId: string | null
 * }
 * </pre>
 * Field names + nullability must stay byte-for-byte aligned with the FE type.
 */
public record CandidateBundle(
        String promptVersionId,
        String behaviorRuleVersionId,
        String skillDraftId
) {
    /** True when every pointer is null/blank — i.e. nothing to adopt. */
    public boolean isEmpty() {
        return isBlank(promptVersionId) && isBlank(behaviorRuleVersionId) && isBlank(skillDraftId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
