package com.skillforge.server.evolve.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b (G3) — the deterministic reconciliation of a
 * {@link PredictionDto} against the A/B run's per-scenario flips.
 *
 * <ul>
 *   <li>{@code hits} — flipToPass ids that actually flipped baseline→candidate to PASS</li>
 *   <li>{@code misses} — flipToPass ids that did NOT flip to PASS</li>
 *   <li>{@code riskHits} — riskToFail ids that actually regressed PASS→FAIL</li>
 *   <li>{@code surprises} — scenarios that flipped (either direction) but were not predicted</li>
 *   <li>{@code confidence} — {@code hits/(hits+misses)}; null when flipToPass is empty / not scoreable</li>
 * </ul>
 *
 * <p>FE-BE contract (java.md footgun #6): field names/types map one-for-one to the
 * FE {@code reconciliation} interface. {@code confidence} is nullable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReconciliationDto(
        List<String> hits,
        List<String> misses,
        List<String> riskHits,
        List<String> surprises,
        Double confidence) {
}
