package com.skillforge.core.context.runtime;

/**
 * Stable reference to the latest successful load of one Skill.
 *
 * <p>The prompt body is intentionally not persisted here. Recovery must resolve
 * the Skill from the current authorized registry and compare {@code versionHash}
 * before reattaching authoritative content.
 */
public record SkillInvocationRef(
        String skillId,
        String versionHash,
        long invocationSequence,
        int estimatedTokens) {

    public SkillInvocationRef {
        skillId = skillId == null ? "" : skillId;
        versionHash = versionHash == null ? "" : versionHash;
        estimatedTokens = Math.max(0, estimatedTokens);
    }
}
