package com.skillforge.server.skill;

/**
 * P1-D — Aggregated counts produced by {@link SkillCatalogReconciler}.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code created}: rows newly inserted because the artifact existed on disk
 *       but no t_skill row matched (path or compat tuple).</li>
 *   <li>{@code updated}: rows whose {@code content_hash} changed and metadata
 *       (description / triggers / required_tools) was refreshed.</li>
 *   <li>{@code missing}: non-system rows whose {@code skill_path} no longer
 *       exists on disk; marked {@code artifact_status='missing'}.</li>
 *   <li>{@code invalid}: non-system rows whose package failed to load (parse
 *       error, missing SKILL.md, etc.); marked {@code artifact_status='invalid'}.</li>
 *   <li>{@code shadowed}: rows hidden by a system or runtime winner; marked
 *       {@code artifact_status='shadowed'} with {@code shadowed_by} populated.</li>
 *   <li>{@code disabledDuplicates}: non-winner runtime rows from a same-name
 *       conflict whose {@code enabled} was flipped to false.</li>
 * </ul>
 */
public record RescanReport(int created, int updated, int missing, int invalid,
                           int shadowed, int disabledDuplicates) {

    public static RescanReport empty() {
        return new RescanReport(0, 0, 0, 0, 0, 0);
    }

    public RescanReport plus(RescanReport other) {
        return new RescanReport(
                created + other.created,
                updated + other.updated,
                missing + other.missing,
                invalid + other.invalid,
                shadowed + other.shadowed,
                disabledDuplicates + other.disabledDuplicates);
    }

    public RescanReport addCreated(int n) {
        return new RescanReport(created + n, updated, missing, invalid, shadowed, disabledDuplicates);
    }

    public RescanReport addUpdated(int n) {
        return new RescanReport(created, updated + n, missing, invalid, shadowed, disabledDuplicates);
    }

    public RescanReport addMissing(int n) {
        return new RescanReport(created, updated, missing + n, invalid, shadowed, disabledDuplicates);
    }

    public RescanReport addInvalid(int n) {
        return new RescanReport(created, updated, missing, invalid + n, shadowed, disabledDuplicates);
    }

    public RescanReport addShadowed(int n) {
        return new RescanReport(created, updated, missing, invalid, shadowed + n, disabledDuplicates);
    }

    public RescanReport addDisabledDuplicates(int n) {
        return new RescanReport(created, updated, missing, invalid, shadowed, disabledDuplicates + n);
    }
}
