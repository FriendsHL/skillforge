package com.skillforge.server.improve;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.event.SkillAbCompletedEvent;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SKILL-EVOLVE-LOOP Phase 5 (cron 0 0 5 * * TUE) — weekly auto-evolve of
 * under-performing skills. Combined with the
 * {@link SkillAbCompletedEvent} listener that surfaces promote results to the
 * skill owner via WS toast (INV-9) without polling (INV-10).
 *
 * <p>Run logic per skill:
 * <ol>
 *   <li>{@link SkillEvalHistoryRepository#findFirstBySkillIdOrderByCreatedAtDesc}
 *       — must have history (skill must have been EVAL'd at least once;
 *       the Phase 3 weekly evaluator runs Mondays so by Tuesday 05:00 it
 *       has fresh data). Absent → skip — operator visibility via INFO log.</li>
 *   <li>If {@code latest.compositeScore >= scoreThreshold} → skip (INV-4).</li>
 *   <li>Otherwise resolve a host agent (first agent owned by the skill's
 *       owner; mirrors {@link SkillScheduledEvaluator#resolveHostAgentId})
 *       and call {@link SkillEvolutionService#createAndTrigger} with
 *       {@code triggeredByUserId = SYSTEM_USER_ID}.</li>
 * </ol>
 *
 * <p>Per-skill failures are isolated (INV-2): a single skill's evolve
 * failure does not abort the loop.
 *
 * <p>{@link #onAbCompleted} fires for every A/B run that finishes (cron-
 * driven OR manually triggered from the dashboard). Only promotes are
 * surfaced via WS push (INV-9) — failed candidates are visible in the
 * dashboard's evolution-runs list but do not toast.
 */
@Component
public class SkillSelfImproveLoop {

    private static final Logger log = LoggerFactory.getLogger(SkillSelfImproveLoop.class);

    /**
     * INV-11: cron-triggered evolves are owned by a synthetic system user.
     * SkillEvolutionService.createAndTrigger does not gate on ownership
     * (only on {@code source == "system"} for the parent skill itself), so
     * 0L is safe — it lands in {@code triggered_by_user_id} as a marker
     * distinguishing cron-driven from operator-driven runs.
     */
    public static final long SYSTEM_USER_ID = 0L;

    private final SkillRepository skillRepository;
    private final SkillEvalHistoryRepository skillEvalHistoryRepository;
    private final AgentService agentService;
    private final SkillEvolutionService skillEvolutionService;
    private final UserWebSocketHandler userWebSocketHandler;
    /**
     * Phase 1.6 R3 fix (2026-05-17) — used by
     * {@link #cleanupAttributionLoserCandidate} to remove the on-disk SKILL.md
     * file when an attribution transient candidate loses its A/B run. Pre-R3
     * only the DB row was deleted, leaking the file under
     * {@code ~/.skillforge/skills/evolution-fork/...}.
     */
    private final com.skillforge.server.skill.SkillStorageService skillStorageService;

    private final boolean enabled;
    private final double scoreThreshold;

    public SkillSelfImproveLoop(SkillRepository skillRepository,
                                SkillEvalHistoryRepository skillEvalHistoryRepository,
                                AgentService agentService,
                                SkillEvolutionService skillEvolutionService,
                                UserWebSocketHandler userWebSocketHandler,
                                com.skillforge.server.skill.SkillStorageService skillStorageService,
                                @Value("${skillforge.skill-self-improve.scheduled-enabled:true}")
                                boolean enabled,
                                @Value("${skillforge.skill-self-improve.score-threshold:60.0}")
                                double scoreThreshold) {
        this.skillRepository = skillRepository;
        this.skillEvalHistoryRepository = skillEvalHistoryRepository;
        this.agentService = agentService;
        this.skillEvolutionService = skillEvolutionService;
        this.userWebSocketHandler = userWebSocketHandler;
        this.skillStorageService = skillStorageService;
        this.enabled = enabled;
        this.scoreThreshold = scoreThreshold;
    }

    /** Cron: 0 0 5 * * TUE — Tuesday 05:00, 1h after Phase 3 weekly eval. */
    @Scheduled(cron = "0 0 5 * * TUE")
    public void scheduledRun() {
        runOnce();
    }

    /** Public for direct test invocation; cron path is {@link #scheduledRun}. */
    public void runOnce() {
        if (!enabled) {
            log.info("SkillSelfImproveLoop disabled via skillforge.skill-self-improve.scheduled-enabled=false");
            return;
        }
        List<SkillEntity> skills;
        try {
            skills = skillRepository.findByIsSystemFalseAndEnabledTrue();
        } catch (Exception e) {
            log.error("SkillSelfImproveLoop: failed to query enabled skills: {}", e.getMessage(), e);
            return;
        }
        if (skills == null || skills.isEmpty()) {
            log.info("SkillSelfImproveLoop: 0 enabled skills");
            return;
        }

        int triggered = 0;
        int skipped = 0;
        int failed = 0;

        for (SkillEntity skill : skills) {
            Long skillId = skill.getId();
            try {
                Optional<SkillEvalHistoryEntity> latest =
                        skillEvalHistoryRepository.findFirstBySkillIdOrderByCreatedAtDesc(skillId);
                if (latest.isEmpty()) {
                    log.info("SkillSelfImproveLoop: skillId={} has no eval history yet, skipping",
                            skillId);
                    skipped++;
                    continue;
                }
                Double composite = latest.get().getCompositeScore();
                if (composite == null) {
                    log.info("SkillSelfImproveLoop: skillId={} latest history has null composite_score, skipping",
                            skillId);
                    skipped++;
                    continue;
                }
                if (composite >= scoreThreshold) {
                    log.info("SkillSelfImproveLoop: skillId={} composite={} >= threshold={}, skipping",
                            skillId, composite, scoreThreshold);
                    skipped++;
                    continue;
                }

                String agentId = resolveHostAgentId(skill);
                if (agentId == null) {
                    log.warn("SkillSelfImproveLoop: skillId={} has no usable host agent, skipping",
                            skillId);
                    skipped++;
                    continue;
                }

                skillEvolutionService.createAndTrigger(skillId, agentId, SYSTEM_USER_ID);
                triggered++;
                log.info("SkillSelfImproveLoop: skillId={} triggered evolve (composite={}, threshold={})",
                        skillId, composite, scoreThreshold);
            } catch (Exception e) {
                // INV-2: per-skill isolation.
                failed++;
                log.warn("SkillSelfImproveLoop: skillId={} evolve trigger failed: {}",
                        skillId, e.getMessage(), e);
            }
        }
        log.info("SkillSelfImproveLoop: done totalSkills={} triggered={} skipped={} failed={}",
                skills.size(), triggered, skipped, failed);
    }

    /**
     * INV-10 listener: AFTER_COMMIT + REQUIRES_NEW so we observe the final
     * persisted state of the A/B run (not a half-flushed in-flight TX) and
     * any DB reads here run in their own fresh TX (the publishing TX is
     * already committed and unavailable for joining).
     *
     * <p>INV-9: only promote events trigger a WS toast. Rejected candidates
     * are visible in the dashboard's evolve-runs list but don't push.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAbCompleted(SkillAbCompletedEvent event) {
        if (event == null || event.getSkillId() == null) {
            return;
        }
        if (!event.isPromoted()) {
            log.info("SelfImproveLoop: A/B not promoted skillId={} abRunId={} baseline={} candidate={}",
                    event.getSkillId(), event.getEvolutionAbRunId(),
                    event.getBaselineScore(), event.getCandidateScore());
            // FLYWHEEL-LOOP-CLOSURE Phase 1.4f (2026-05-17) — loser cleanup
            // for attribution-derived transient candidate skills. Dual pivot
            // (source + name suffix regex) means production A/B losers from
            // other sources (V2/V4 dedicated forks, manual operator A/B) are
            // untouched. Best-effort: any failure logs WARN but does not
            // propagate (we're in an AFTER_COMMIT listener — original A/B
            // outcome has been persisted regardless).
            cleanupAttributionLoserCandidate(event.getSkillId());
            return;
        }

        SkillEntity skill = skillRepository.findById(event.getSkillId()).orElse(null);
        if (skill == null) {
            log.warn("SelfImproveLoop: skill vanished after A/B promote, skillId={}",
                    event.getSkillId());
            return;
        }
        Long ownerId = skill.getOwnerId();
        if (ownerId == null) {
            log.warn("SelfImproveLoop: skill has no ownerId, can't push WS skillId={}",
                    event.getSkillId());
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "skill_auto_upgraded");
        payload.put("skillId", event.getSkillId());
        payload.put("skillName", skill.getName());
        payload.put("oldVersion", event.getOldVersion());
        payload.put("newVersion", event.getNewVersion());
        payload.put("baselineScore", event.getBaselineScore());
        payload.put("candidateScore", event.getCandidateScore());

        try {
            userWebSocketHandler.broadcast(ownerId, payload);
            log.info("SelfImproveLoop: pushed skill_auto_upgraded skillId={} ownerId={} oldVersion={} newVersion={}",
                    event.getSkillId(), ownerId, event.getOldVersion(), event.getNewVersion());
        } catch (Exception e) {
            log.warn("SelfImproveLoop: WS push failed skillId={} ownerId={}: {}",
                    event.getSkillId(), ownerId, e.getMessage());
        }
    }

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4f (2026-05-17) — loser cleanup for
     * attribution transient candidate (Ratify #7-B path (a) cleanup arm).
     *
     * <p>Dual pivot for safety:
     * <ol>
     *   <li>{@code source == "attribution_ab_transient"} — written by
     *       {@code SkillDraftService.promoteDraftToTransientSkill} (Phase 1.4c).</li>
     *   <li>name regex {@code .*_candidate_[0-9a-f]{8}$} — the 8-char hex
     *       UUID slice suffix written by the same helper.</li>
     * </ol>
     * Both must match before delete — defence against false-positive cleanup
     * of any production skill that happens to share one marker.
     *
     * <p>Paired baseline (same name + {@code "_baseline_empty"} suffix,
     * source {@code "attribution_ab_baseline_empty"}) deleted with same dual
     * pivot.
     *
     * <p>Package-private for unit-test access; pure best-effort (catches any
     * thrown to avoid masking original A/B outcome path).
     */
    void cleanupAttributionLoserCandidate(Long candidateSkillId) {
        if (candidateSkillId == null) return;
        try {
            SkillEntity candidate = skillRepository.findById(candidateSkillId).orElse(null);
            if (candidate == null
                    || !"attribution_ab_transient".equals(candidate.getSource())
                    || candidate.getName() == null
                    || !candidate.getName().matches(".*_candidate_[0-9a-f]{8}$")) {
                // Not an attribution transient candidate — leave it alone
                // (e.g., V2/V4 production fork that lost its A/B).
                return;
            }
            String baselineName = candidate.getName() + "_baseline_empty";
            // R3 fix (Phase 1.6 dogfood, 2026-05-17): capture skillPath BEFORE
            // the DB row delete so we can clean up the on-disk SKILL.md file
            // afterwards (pre-R3 the row went away but the file leaked).
            String candidateSkillPath = candidate.getSkillPath();
            skillRepository.delete(candidate);
            if (candidateSkillPath != null && !candidateSkillPath.isBlank()) {
                skillStorageService.delete(java.nio.file.Path.of(candidateSkillPath));
            }
            log.info("SelfImproveLoop: cleaned up loser transient candidate skillId={} "
                            + "(name={} source={} skillPath={})",
                    candidate.getId(), candidate.getName(), candidate.getSource(),
                    candidateSkillPath);
            skillRepository.findByName(baselineName)
                    .filter(b -> "attribution_ab_baseline_empty".equals(b.getSource()))
                    .ifPresent(b -> {
                        String baselineSkillPath = b.getSkillPath();
                        skillRepository.delete(b);
                        if (baselineSkillPath != null && !baselineSkillPath.isBlank()) {
                            skillStorageService.delete(java.nio.file.Path.of(baselineSkillPath));
                        }
                        log.info("SelfImproveLoop: cleaned up paired baseline skillId={} "
                                        + "(name={} skillPath={})",
                                b.getId(), b.getName(), baselineSkillPath);
                    });
        } catch (RuntimeException e) {
            log.warn("SelfImproveLoop: loser cleanup failed for candidateSkillId={}: {}",
                    candidateSkillId, e.getMessage());
        }
    }

    /**
     * Pick a host agent for the skill — first agent owned by the skill's
     * owner. Mirrors {@link SkillScheduledEvaluator#resolveHostAgentId}.
     * Returns null when no usable candidate exists (caller skips + warns).
     */
    private String resolveHostAgentId(SkillEntity skill) {
        Long ownerId = skill.getOwnerId();
        if (ownerId == null) {
            return null;
        }
        List<AgentEntity> candidates;
        try {
            candidates = agentService.listAgents(ownerId);
        } catch (Exception e) {
            log.warn("SkillSelfImproveLoop: failed to list agents for ownerId={}: {}",
                    ownerId, e.getMessage());
            return null;
        }
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return String.valueOf(candidates.get(0).getId());
    }
}
