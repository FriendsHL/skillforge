package com.skillforge.server.evolve.dto;

import com.skillforge.server.entity.EvalScenarioEntity;

import java.time.Instant;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b — read projection of a harvested
 * (session-derived) bad-case scenario for the dashboard list.
 *
 * <p>FE-BE contract (java.md footgun #6): the field names/types below map
 * one-for-one to the FE {@code HarvestedScenario} interface. Keep them in sync —
 * adding/removing a field requires the FE type to change in lockstep.
 *
 * @param id          scenario id
 * @param name        human-readable scenario name
 * @param description scenario description
 * @param status      lifecycle status ({@code draft} | {@code active} | {@code archived})
 * @param sourceRef   origin reference (e.g. {@code session:<id>}); nullable
 * @param createdAt   creation instant (ISO-8601 when serialized)
 * @param reviewedAt  human-review/activation instant; null while still a draft
 */
public record HarvestedScenarioDto(
        String id,
        String name,
        String description,
        String status,
        String sourceRef,
        Instant createdAt,
        Instant reviewedAt) {

    public static HarvestedScenarioDto from(EvalScenarioEntity e) {
        return new HarvestedScenarioDto(
                e.getId(),
                e.getName(),
                e.getDescription(),
                e.getStatus(),
                e.getSourceRef(),
                e.getCreatedAt(),
                e.getReviewedAt());
    }
}
