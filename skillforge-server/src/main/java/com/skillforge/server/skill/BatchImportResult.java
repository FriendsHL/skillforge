package com.skillforge.server.skill;

import java.util.List;

/**
 * SKILL-IMPORT-BATCH — return value of
 * {@link SkillImportService#batchImportFromMarketplace(SkillSource, Long)}.
 *
 * <p>Serialised as JSON and returned to the dashboard / curl caller. Each list
 * is independent — failure of one subdir does not abort the rest, so callers
 * can render partial success.
 *
 * <p>JSON shape (PRD F2):
 * <pre>
 * {
 *   "imported": [{"name": "...", "skillPath": "..."}],
 *   "updated":  [{"name": "...", "skillPath": "..."}],
 *   "skipped":  [{"name": "...", "reason": "..."}],
 *   "failed":   [{"name": "...", "error": "..."}]
 * }
 * </pre>
 *
 * @param imported newly created {@code t_skill} rows (no prior row for the
 *                 same {@code (ownerId, name, source)} tuple)
 * @param updated  pre-existing rows whose payload was overwritten (i.e. the
 *                 underlying {@link ImportResult#conflictResolved()} was {@code true})
 * @param skipped  subdirs that were intentionally not imported — missing
 *                 {@code SKILL.md}, malformed package, or cross-source name
 *                 collision (raised as {@link IllegalStateException} by
 *                 {@code importSkill})
 * @param failed   subdirs that crashed with an unexpected exception (IO error,
 *                 DB error, etc.). Other subdirs continue.
 */
public record BatchImportResult(
        List<ImportedItem> imported,
        List<ImportedItem> updated,
        List<SkippedItem> skipped,
        List<FailedItem> failed) {

    /** A row that was inserted or updated successfully. */
    public record ImportedItem(String name, String skillPath) {
    }

    /** A subdir intentionally not imported, with a human-readable reason. */
    public record SkippedItem(String name, String reason) {
    }

    /** A subdir that crashed with an unexpected exception. */
    public record FailedItem(String name, String error) {
    }
}
