package com.skillforge.server.skill.curate;

import com.skillforge.server.config.SkillConsolidatorProperties;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SKILL-CURATOR V1 — nightly curator that archives non-system, old, rarely-used
 * skills so they stop polluting the agent's tool-selection surface. Mirrors the
 * Memory consolidation pattern ({@code MemoryConsolidator}).
 *
 * <p><b>Safety: v1 defaults to dry-run</b> ({@code skillforge.skill.consolidation.dry-run=true}):
 * the curator only logs what it <em>would</em> archive and mutates nothing. An operator
 * must explicitly flip {@code dry-run=false} before any skill is actually disabled.
 *
 * <p>Candidate selection (all enforced by {@link SkillRepository#findArchivalCandidates}):
 * <ul>
 *   <li>non-system ({@code isSystem=false}) — system skills are exempt;</li>
 *   <li>currently enabled and not already archived;</li>
 *   <li>{@code usageCount < minUsage} (default 1 ⇒ never used);</li>
 *   <li>older than {@code cooldownDays} since {@code createdAt}.</li>
 * </ul>
 *
 * <p>No {@code updatedAt} guard (bug A): {@code updatedAt} is bumped by system saves, not
 * just user edits, so it is the wrong "user intent" signal. Restore-protection (don't
 * re-archive a manually-restored skill) is implemented via the {@code curator_exempt}
 * column (V164): the dashboard restore path sets it true and
 * {@link SkillRepository#findArchivalCandidates} excludes exempt rows.
 *
 * <p><b>Human-in-loop entry points (dashboard-controllable):</b>
 * <ul>
 *   <li>{@link #findCandidates()} — preview: compute + return candidates, NO mutation.</li>
 *   <li>{@link #applyArchival()} — manual apply: archive for real regardless of the
 *       {@code dry-run} prop (the operator explicitly clicked "归档这些").</li>
 *   <li>{@link #consolidate()} — cron path: honors {@code props.isDryRun()} (still
 *       dry-run by default in v1).</li>
 * </ul>
 *
 * <p>Each candidate is processed in its own try/catch (INV-2): a single failure logs
 * a WARN and the batch continues with the next skill.
 */
@Component
public class SkillConsolidator {

    private static final Logger log = LoggerFactory.getLogger(SkillConsolidator.class);

    /** Reason recorded in {@link SkillEntity#getArchiveReason()} when the curator archives a skill. */
    static final String REASON_LOW_USAGE = "low_usage_curator";

    private final SkillRepository skillRepository;
    private final SkillConsolidatorProperties props;

    public SkillConsolidator(SkillRepository skillRepository,
                             SkillConsolidatorProperties props) {
        this.skillRepository = skillRepository;
        this.props = props;
    }

    /**
     * Result of one consolidation pass.
     *
     * @param candidatesFound how many skills matched the archival query
     * @param archived        how many were actually archived (always 0 in dry-run)
     * @param dryRun          whether this run was dry-run (no mutation)
     */
    public record ConsolidationResult(int candidatesFound, int archived, boolean dryRun) {}

    /**
     * Run one curator pass. No-op (logged) when disabled. Computes the two age
     * thresholds from a single {@code now} so {@code createdBefore} (LocalDateTime,
     * legacy column) and {@code updatedBefore} (Instant, new column) describe the
     * same wall-clock instant despite the type mismatch (java.md footgun #2).
     */
    public ConsolidationResult consolidate() {
        if (!props.isEnabled()) {
            log.info("[SkillCurator] disabled via skillforge.skill.consolidation.enabled=false");
            return new ConsolidationResult(0, 0, props.isDryRun());
        }

        boolean dryRun = props.isDryRun();
        List<SkillEntity> candidates = findCandidates();
        int candidatesFound = candidates.size();

        int archived = 0;
        for (SkillEntity skill : candidates) {
            if (dryRun) {
                log.info("[SkillCurator] DRY-RUN would archive skill id={} name={} "
                                + "usageCount={} createdAt={}",
                        skill.getId(), skill.getName(), skill.getUsageCount(), skill.getCreatedAt());
            } else if (archiveOne(skill)) {
                archived++;
            }
        }

        log.info("[SkillCurator] done dryRun={} candidatesFound={} archived={} "
                        + "(minUsage={} cooldownDays={})",
                dryRun, candidatesFound, archived,
                props.getMinUsage(), props.getCooldownDays());
        return new ConsolidationResult(candidatesFound, archived, dryRun);
    }

    /**
     * Preview the rows the curator <em>would</em> archive, with NO mutation. Used by
     * the dashboard "技能整理" candidate table ({@code GET /candidates}) and reused
     * internally by {@link #consolidate()} / {@link #applyArchival()}.
     *
     * <p>Computes {@code createdBefore} as {@code now - cooldownDays}. The
     * {@code createdBefore} type is {@link LocalDateTime} to match the legacy
     * {@code t_skill.created_at} column (java.md footgun #2). Candidate selection
     * (non-system / enabled / not-archived / not-exempt / low-usage / old enough)
     * is fully enforced by {@link SkillRepository#findArchivalCandidates}.
     */
    public List<SkillEntity> findCandidates() {
        LocalDateTime createdBefore = LocalDateTime.now().minusDays(props.getCooldownDays());
        return skillRepository.findArchivalCandidates(props.getMinUsage(), createdBefore);
    }

    /**
     * Human-in-loop manual apply: archive the current candidates for REAL, regardless
     * of the {@code dry-run} prop (the operator explicitly clicked "归档这些" in the
     * dashboard). The cron path stays gated on {@code props.isDryRun()} via
     * {@link #consolidate()} — only this method bypasses dry-run.
     *
     * <p>Per-skill try/catch (INV-2): one failure logs a WARN and the batch continues.
     *
     * @return result with {@code dryRun=false} (this is always a real run)
     */
    public ConsolidationResult applyArchival() {
        List<SkillEntity> candidates = findCandidates();
        int candidatesFound = candidates.size();
        int archived = 0;
        for (SkillEntity skill : candidates) {
            if (archiveOne(skill)) {
                archived++;
            }
        }
        log.info("[SkillCurator] manual apply done candidatesFound={} archived={} "
                        + "(minUsage={} cooldownDays={})",
                candidatesFound, archived, props.getMinUsage(), props.getCooldownDays());
        return new ConsolidationResult(candidatesFound, archived, false);
    }

    /**
     * Archive one skill for real (disable + stamp {@code archivedAt} / reason + save).
     * Wrapped in try/catch so a single failure never aborts the batch (INV-2).
     *
     * @return true when the skill was archived; false when the save threw (logged).
     */
    private boolean archiveOne(SkillEntity skill) {
        try {
            skill.setEnabled(false);
            skill.setArchivedAt(Instant.now());
            skill.setArchiveReason(REASON_LOW_USAGE);
            skillRepository.save(skill);
            log.info("[SkillCurator] archived skill id={} name={} usageCount={} createdAt={} reason={}",
                    skill.getId(), skill.getName(), skill.getUsageCount(),
                    skill.getCreatedAt(), REASON_LOW_USAGE);
            return true;
        } catch (Exception e) {
            // INV-2: one skill's failure must not abort the batch.
            log.warn("[SkillCurator] failed to archive skill id={} name={}: {}",
                    skill.getId(), skill.getName(), e.getMessage(), e);
            return false;
        }
    }
}
