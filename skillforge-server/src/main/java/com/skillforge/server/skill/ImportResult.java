package com.skillforge.server.skill;

/**
 * SKILL-IMPORT — return value of {@link SkillImportService#importSkill}.
 *
 * <p>Serialised as JSON and returned to the agent inside the {@code ImportSkill}
 * tool's tool_result block.
 *
 * @param id              {@code t_skill.id} of the upserted row
 * @param name            registered skill name (slug)
 * @param skillPath       absolute on-disk runtime path the artifact was copied to
 * @param source          {@link SkillSource#wireName()} of the originating marketplace
 * @param conflictResolved {@code true} when an existing row was overwritten;
 *                        {@code false} when a new row was created
 */
public record ImportResult(
        Long id,
        String name,
        String skillPath,
        String source,
        boolean conflictResolved) {
}
