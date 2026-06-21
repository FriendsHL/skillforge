package com.skillforge.server.dto;

import com.skillforge.server.entity.SkillEntity;

/**
 * SKILL-CURATOR human-in-loop — one archival candidate previewed by the dashboard
 * "技能整理" modal ({@code GET /api/admin/skill-consolidation/candidates}). Purely a
 * read-only projection of a {@link SkillEntity} the curator <em>would</em> archive;
 * computing it never mutates the row.
 *
 * @param id          skill row id
 * @param name        skill name
 * @param usageCount  how many times the skill was invoked (candidates are low-usage)
 * @param createdAt   ISO-8601 string of {@code created_at} (null when unset); the
 *                    entity column is a {@link java.time.LocalDateTime}, rendered via
 *                    {@code toString()} so the FE gets a plain string field
 * @param description skill description (may be null)
 */
public record SkillCuratorCandidateDto(
        Long id,
        String name,
        long usageCount,
        String createdAt,
        String description
) {
    public static SkillCuratorCandidateDto from(SkillEntity skill) {
        return new SkillCuratorCandidateDto(
                skill.getId(),
                skill.getName(),
                skill.getUsageCount(),
                skill.getCreatedAt() != null ? skill.getCreatedAt().toString() : null,
                skill.getDescription());
    }
}
