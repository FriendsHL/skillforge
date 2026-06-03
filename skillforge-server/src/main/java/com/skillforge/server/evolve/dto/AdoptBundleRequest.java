package com.skillforge.server.evolve.dto;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — request body for
 * {@code POST /api/evolve/runs/{evolveRunId}/adopt}. Each pointer is optional;
 * a {@code null}/absent pointer means "do not adopt this surface". At least one
 * must be non-null (the controller rejects an all-null body with 400).
 *
 * <p>footgun #6 contract — FE mirror ({@code api/evolve.ts}):
 * <pre>
 * interface AdoptBundleRequest {
 *   promptVersionId?: string | null
 *   behaviorRuleVersionId?: string | null
 *   skillDraftId?: string | null
 * }
 * </pre>
 */
public record AdoptBundleRequest(
        String promptVersionId,
        String behaviorRuleVersionId,
        String skillDraftId
) {}
