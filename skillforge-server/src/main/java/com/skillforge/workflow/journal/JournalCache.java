package com.skillforge.workflow.journal;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * AUTOEVOLVING V1 Sprint 2 — journal-replay seam (chunk 1 defines the contract;
 * <b>chunk 2 implements + wires it</b> into the {@code agent()} /
 * {@code humanApprove()} resume short-circuits and the approve REST path).
 *
 * <p>On resume the workflow JS is re-run from the top in a fresh Rhino context.
 * Each {@code agent()} / {@code humanApprove()} call whose deterministic
 * {@code stepIndex} is at or before the resume frontier must return its
 * <em>first-run</em> result instead of re-executing — looked up here BY
 * {@code step_index} (V127 column), never by {@code created_at} (non-deterministic
 * under {@code parallel()}). The cache-hit path must not re-append a step row or
 * re-run the engine (plan §2.5 invariant).
 */
public interface JournalCache {

    /**
     * The first-run {@code finalResponse} string of the completed
     * {@code subagent_dispatch} step at {@code stepIndex}, or empty if there is
     * no such completed step (a true cache miss — a bug during replay).
     */
    Optional<String> getCachedAgentFinalResponse(String runId, int stepIndex);

    /**
     * The recorded approval decision of the completed {@code human_approve} step
     * at {@code stepIndex} (the {@code step_output_json} written by the approve
     * REST call), or empty if the gate has not been decided yet.
     */
    Optional<JsonNode> getApproveDecision(String runId, int stepIndex);
}
