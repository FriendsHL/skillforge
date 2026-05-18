package com.skillforge.server.skill;

import com.skillforge.server.security.skill.SkillScanFinding;

import java.util.List;

/**
 * SKILL-IMPORT — return value of {@link SkillImportService#importSkill}.
 *
 * <p>Serialised as JSON and returned to the agent inside the {@code ImportSkill}
 * tool's tool_result block.
 *
 * <p>SKILL-CREATOR-WITH-EVAL Phase 1.2 (2026-05-18): added the {@code evaluating}
 * + {@code draftId} fields and the {@link #evaluating(String)} factory so the
 * import endpoint can signal "skill was NOT registered live — evaluation gate
 * is running first, see draft id for status". Existing {@link #ImportResult(Long,
 * String, String, String, boolean)} short ctor and the 6-arg canonical ctor stay
 * source-compatible.
 *
 * @param id              {@code t_skill.id} of the upserted row (null when {@code evaluating})
 * @param name            registered skill name (slug; null when {@code evaluating})
 * @param skillPath       absolute on-disk runtime path the artifact was copied to (null when {@code evaluating})
 * @param source          {@link SkillSource#wireName()} of the originating marketplace
 * @param conflictResolved {@code true} when an existing row was overwritten;
 *                        {@code false} when a new row was created
 * @param scanWarnings     low/medium scan findings that did not block import
 * @param evaluating       {@code true} when the import deferred registration to
 *                         the SKILL-CREATOR-WITH-EVAL evaluation gate; FE should
 *                         poll {@code draftId} for the verdict
 * @param draftId          {@code t_skill_draft.id} created for the evaluation
 *                         batch (only set when {@code evaluating=true})
 */
public record ImportResult(
        Long id,
        String name,
        String skillPath,
        String source,
        boolean conflictResolved,
        List<SkillScanFinding> scanWarnings,
        boolean evaluating,
        String draftId) {

    public ImportResult(Long id, String name, String skillPath, String source, boolean conflictResolved) {
        this(id, name, skillPath, source, conflictResolved, List.of(), false, null);
    }

    /** Legacy 6-arg canonical ctor kept for source compat with pre-Phase 1.2 callers. */
    public ImportResult(Long id, String name, String skillPath, String source,
                        boolean conflictResolved, List<SkillScanFinding> scanWarnings) {
        this(id, name, skillPath, source, conflictResolved, scanWarnings, false, null);
    }

    public ImportResult {
        scanWarnings = scanWarnings == null ? List.of() : List.copyOf(scanWarnings);
    }

    /**
     * SKILL-CREATOR-WITH-EVAL Phase 1.2 (2026-05-18): factory used by
     * {@code SkillImportService.importSkill} when an evals/evals.json was
     * present in the package and the evaluation gate took over instead of
     * direct registration. {@code conflictResolved=false} because no row
     * was written to t_skill (the candidate skill stays transient in
     * t_skill rows until the eval verdict drives promote / cleanup).
     *
     * @param draftId t_skill_draft.id created by the dispatch path
     */
    public static ImportResult evaluating(String draftId) {
        return new ImportResult(null, null, null, null, false, List.of(), true, draftId);
    }
}
