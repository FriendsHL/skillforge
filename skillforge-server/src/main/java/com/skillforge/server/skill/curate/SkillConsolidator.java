package com.skillforge.server.skill.curate;

import com.skillforge.server.config.SkillConsolidatorProperties;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 *   <li>older than {@code cooldownDays} since {@code createdAt};</li>
 *   <li>not updated within {@code recentUpdateGraceDays} (respects manual edits/restores).</li>
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
        long minUsage = props.getMinUsage();

        Instant now = Instant.now();
        LocalDateTime createdBefore = LocalDateTime.now().minusDays(props.getCooldownDays());
        Instant updatedBefore = now.minus(Duration.ofDays(props.getRecentUpdateGraceDays()));

        List<SkillEntity> candidates =
                skillRepository.findArchivalCandidates(minUsage, createdBefore, updatedBefore);
        int candidatesFound = candidates.size();

        int archived = 0;
        for (SkillEntity skill : candidates) {
            try {
                if (dryRun) {
                    log.info("[SkillCurator] DRY-RUN would archive skill id={} name={} "
                                    + "usageCount={} createdAt={}",
                            skill.getId(), skill.getName(), skill.getUsageCount(), skill.getCreatedAt());
                } else {
                    skill.setEnabled(false);
                    skill.setArchivedAt(Instant.now());
                    skill.setArchiveReason(REASON_LOW_USAGE);
                    skillRepository.save(skill);
                    archived++;
                    log.info("[SkillCurator] archived skill id={} name={} usageCount={} createdAt={} reason={}",
                            skill.getId(), skill.getName(), skill.getUsageCount(),
                            skill.getCreatedAt(), REASON_LOW_USAGE);
                }
            } catch (Exception e) {
                // INV-2: one skill's failure must not abort the batch.
                log.warn("[SkillCurator] failed to archive skill id={} name={}: {}",
                        skill.getId(), skill.getName(), e.getMessage(), e);
            }
        }

        log.info("[SkillCurator] done dryRun={} candidatesFound={} archived={} "
                        + "(minUsage={} cooldownDays={} recentUpdateGraceDays={})",
                dryRun, candidatesFound, archived,
                minUsage, props.getCooldownDays(), props.getRecentUpdateGraceDays());
        return new ConsolidationResult(candidatesFound, archived, dryRun);
    }
}
