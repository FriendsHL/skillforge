package com.skillforge.server.skill;

/**
 * P1-D — Logical "source" of a runtime skill artifact, used by
 * {@link SkillStorageService} to allocate stable, predictable on-disk
 * destination paths and by {@code t_skill.source} for catalog UI/governance.
 *
 * <p>Naming maps to the directory layer under {@code <runtimeRoot>}:
 * <ul>
 *     <li>{@link #UPLOAD} → {@code upload/{ownerId}/{uuid}}</li>
 *     <li>{@link #SKILL_CREATOR} → {@code skill-creator/{ownerId}/{uuid}}</li>
 *     <li>{@link #DRAFT_APPROVE} → {@code draft-approve/{ownerId}/{uuid}}</li>
 *     <li>{@link #EVOLUTION_FORK} → {@code evolution-fork/{ownerId}/{parentSkillId}/{uuid}}</li>
 *     <li>{@link #SKILLHUB} → {@code skillhub/{slug}/{version}}</li>
 *     <li>{@link #CLAWHUB} → {@code clawhub/{slug}/{version}}</li>
 *     <li>{@link #GITHUB} → {@code github/{repoSlug}/{ref}}</li>
 *     <li>{@link #FILESYSTEM} → {@code filesystem/{ownerId}/{uuid}}</li>
 * </ul>
 */
public enum SkillSource {
    UPLOAD,
    SKILL_CREATOR,
    DRAFT_APPROVE,
    EVOLUTION_FORK,
    SKILLHUB,
    CLAWHUB,
    GITHUB,
    FILESYSTEM;

    /**
     * Lower-kebab-case name for persistence in {@code t_skill.source}.
     * Stable wire format independent of enum constant rename.
     */
    public String wireName() {
        return name().toLowerCase().replace('_', '-');
    }
}
