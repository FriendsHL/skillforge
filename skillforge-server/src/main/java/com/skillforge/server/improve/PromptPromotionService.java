package com.skillforge.server.improve;

import com.skillforge.server.config.EvolveThresholdProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.improve.event.ImprovePausedEvent;
import com.skillforge.server.improve.event.PromptPromotedEvent;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class PromptPromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromptPromotionService.class);

    private final PromptAbRunRepository promptAbRunRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;
    // F5 (2026-06-07): promote threshold moved from a hardcoded 15.0 constant into
    // EvolveThresholdProperties (skillforge.evolve.thresholds.prompt-delta-pp,
    // default 5) — shared with GetAbResultTool's advisory wouldPromote so the
    // advisory signal can't drift from this real gate.
    private final EvolveThresholdProperties thresholds;

    public PromptPromotionService(PromptAbRunRepository promptAbRunRepository,
                                   PromptVersionRepository promptVersionRepository,
                                   AgentRepository agentRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   EvolveThresholdProperties thresholds) {
        this.promptAbRunRepository = promptAbRunRepository;
        this.promptVersionRepository = promptVersionRepository;
        this.agentRepository = agentRepository;
        this.eventPublisher = eventPublisher;
        this.thresholds = thresholds;
    }

    @Transactional
    public PromotionResult evaluateAndPromote(String abRunId, String agentId) {
        PromptAbRunEntity abRun = promptAbRunRepository.findById(abRunId)
                .orElseThrow(() -> new RuntimeException("AB run not found: " + abRunId));

        // Pessimistic write lock to prevent concurrent promotions for the same agent
        AgentEntity agent = agentRepository.findByIdForUpdate(Long.parseLong(agentId))
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

        PromptVersionEntity candidate = promptVersionRepository.findById(abRun.getPromptVersionId())
                .orElseThrow(() -> new RuntimeException("Prompt version not found: " + abRun.getPromptVersionId()));

        Long triggeredByUserId = abRun.getTriggeredByUserId();

        // Guard 1: delta < threshold — any sub-threshold result counts as a decline
        double deltaThresholdPp = thresholds.getPromptDeltaPp();
        Double delta = abRun.getDeltaPassRate();
        if (delta == null || delta < deltaThresholdPp) {
            log.info("Promotion rejected: delta {} < threshold {} for agent {}",
                    delta, deltaThresholdPp, agentId);
            updateAbDeclineTracking(agent, triggeredByUserId);
            agentRepository.save(agent);
            return PromotionResult.rejected("Delta " + delta + " below threshold " + deltaThresholdPp);
        }

        // Guard 2: already promoted today
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long promotedToday = promptAbRunRepository.countPromotedSince(agentId, startOfDay);
        if (promotedToday > 0) {
            log.info("Promotion rejected: already promoted today for agent {}", agentId);
            return PromotionResult.rejected("Already promoted today");
        }

        // Guard 3: 24h cooldown from last promotion
        if (agent.getLastPromotedAt() != null
                && agent.getLastPromotedAt().isAfter(Instant.now().minusSeconds(24 * 3600))) {
            log.info("Promotion rejected: 24h cooldown active for agent {}", agentId);
            return PromotionResult.rejected("24h cooldown active");
        }

        // Guard 4: auto-improve paused
        if (agent.isAutoImprovePaused()) {
            log.info("Promotion rejected: auto-improve paused for agent {}", agentId);
            return PromotionResult.rejected("Auto-improve paused");
        }

        // Atomic promotion (deprecate old → activate candidate → point agent at it).
        // Shared with promoteByHuman via activateVersion() to avoid a divergent
        // hand-copied state machine over user-visible agent.systemPrompt.
        Instant now = Instant.now();
        activateVersion(agent, candidate, now);

        // Update AB run
        abRun.setPromoted(true);
        promptAbRunRepository.save(abRun);

        log.info("Prompt promoted: agentId={}, versionId={}, delta={}",
                agentId, candidate.getId(), delta);

        // Publish event (broadcast fires after commit via @TransactionalEventListener)
        eventPublisher.publishEvent(new PromptPromotedEvent(
                agentId, candidate.getId(), delta, candidate.getVersionNumber(),
                abRun.getTriggeredByUserId()));

        return PromotionResult.promoted(candidate.getId());
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — human adoption of a prompt version from the
     * evolve trajectory. Mirrors the atomic-promote section of
     * {@link #evaluateAndPromote} (deprecate old active → activate candidate →
     * update agent → publish event) but DELIBERATELY SKIPS all four automatic
     * gates (delta threshold / already-promoted-today / 24h cooldown /
     * auto-improve-paused): the operator clicking "Approve &amp; Adopt" IS the
     * gate. There is no {@code PromptAbRunEntity} in this path, so we neither
     * touch {@code abRun.setPromoted} nor {@code updateAbDeclineTracking}.
     *
     * <p>Idempotent (INV): re-adopting an already-active version is a no-op —
     * returns {@link PromotionResult#promoted} with no DB write and no event.
     *
     * <p>{@code abDeclineCount} is reset to 0 on a successful adopt — adopting a
     * better version clears the consecutive-decline streak the same way an
     * automatic promotion does.
     *
     * @param promptVersionId candidate version to activate
     * @param agentId         target agent id (the version must belong to it)
     * @param userId          operator id (threaded into the broadcast event)
     * @throws IllegalArgumentException version not found, or it belongs to a
     *                                  different agent (ownership), or the agent
     *                                  row is missing
     */
    @Transactional
    public PromotionResult promoteByHuman(String promptVersionId, String agentId, Long userId) {
        PromptVersionEntity version = promptVersionRepository.findById(promptVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prompt version not found: " + promptVersionId));

        // Ownership: a version may only be adopted onto the agent it belongs to
        // (defense-in-depth — AgentBundleAdoptionService pre-validates, but the
        // service is also reachable directly).
        if (!agentId.equals(version.getAgentId())) {
            throw new IllegalArgumentException(
                    "prompt version " + promptVersionId + " belongs to agent " + version.getAgentId()
                            + " but adopt targets agent " + agentId
                            + " — a version may only be adopted onto its owning agent");
        }

        // Idempotency: already active → no-op (no DB write, no event re-publish).
        if ("active".equals(version.getStatus())) {
            log.info("promoteByHuman no-op: version {} already active for agent {}",
                    promptVersionId, agentId);
            return PromotionResult.promoted(version.getId());
        }

        // Pessimistic write lock to prevent concurrent promotions for the same agent.
        AgentEntity agent = agentRepository.findByIdForUpdate(Long.parseLong(agentId))
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        // (gates 1-4 deliberately skipped — the human operator is the gate)
        // Same atomic activation as the automatic path, via activateVersion().
        Instant now = Instant.now();
        activateVersion(agent, version, now);

        log.info("Prompt promoted by human: agentId={}, versionId={}, userId={}",
                agentId, version.getId(), userId);

        // Publish event (broadcast fires after commit). delta=null — no A/B run
        // backs a human adoption.
        eventPublisher.publishEvent(new PromptPromotedEvent(
                agentId, version.getId(), null, version.getVersionNumber(), userId));

        return PromotionResult.promoted(version.getId());
    }

    /**
     * Rolls back to a deprecated prompt version. Must be called within a transaction.
     * Does NOT reset abDeclineCount — use the /resume endpoint for that.
     */
    @Transactional
    public void rollbackToVersion(AgentEntity agent, PromptVersionEntity targetVersion) {
        Instant now = Instant.now();

        // Deprecate current active version
        if (agent.getActivePromptVersionId() != null) {
            promptVersionRepository.findById(agent.getActivePromptVersionId())
                    .ifPresent(current -> {
                        current.setStatus("deprecated");
                        current.setDeprecatedAt(now);
                        promptVersionRepository.save(current);
                    });
        }

        // Reactivate target version
        targetVersion.setStatus("active");
        targetVersion.setDeprecatedAt(null);
        promptVersionRepository.save(targetVersion);

        // Update agent
        agent.setSystemPrompt(targetVersion.getContent());
        agent.setActivePromptVersionId(targetVersion.getId());
        agentRepository.save(agent);

        log.info("Rolled back agent {} to prompt version {}", agent.getId(), targetVersion.getId());
    }

    /**
     * Shared atomic version activation used by BOTH the automatic
     * ({@link #evaluateAndPromote}) and human ({@link #promoteByHuman}) promote
     * paths: deprecate the agent's current active prompt version, activate
     * {@code candidate}, and point the agent's runtime config (systemPrompt /
     * activePromptVersionId / lastPromotedAt) at it, resetting the decline streak.
     *
     * <p>Callers own everything that differs between the two paths — gating, the
     * {@code abRun.setPromoted} marking (automatic only), and the
     * {@link PromptPromotedEvent} publish (delta differs: real value vs null).
     * Consolidated here (design review W1) so the user-visible
     * {@code agent.systemPrompt} state transition lives in ONE place instead of a
     * hand-copied third copy. Must run inside the caller's {@code @Transactional}.
     *
     * <p>Note: {@link #rollbackToVersion} is intentionally NOT routed through this
     * helper — it has different semantics (clears {@code deprecatedAt} on the
     * reactivated target, no {@code promotedAt}, no decline reset).
     */
    private void activateVersion(AgentEntity agent, PromptVersionEntity candidate, Instant now) {
        // Deprecate old active version
        if (agent.getActivePromptVersionId() != null) {
            promptVersionRepository.findById(agent.getActivePromptVersionId())
                    .ifPresent(oldVersion -> {
                        oldVersion.setStatus("deprecated");
                        oldVersion.setDeprecatedAt(now);
                        promptVersionRepository.save(oldVersion);
                    });
        }

        // Promote candidate
        candidate.setStatus("active");
        candidate.setPromotedAt(now);
        promptVersionRepository.save(candidate);

        // Update agent runtime config
        agent.setSystemPrompt(candidate.getContent());
        agent.setActivePromptVersionId(candidate.getId());
        agent.setLastPromotedAt(now);
        agent.setAbDeclineCount(0);
        agentRepository.save(agent);
    }

    /**
     * Increments the decline counter on every sub-threshold result (regardless of sign).
     * Triggers auto-pause after 3 consecutive sub-threshold results.
     */
    private void updateAbDeclineTracking(AgentEntity agent, Long triggeredByUserId) {
        int newCount = agent.getAbDeclineCount() + 1;
        agent.setAbDeclineCount(newCount);
        if (newCount >= 3) {
            agent.setAutoImprovePaused(true);
            log.warn("Auto-improve paused for agent {}: {} consecutive sub-threshold results",
                    agent.getId(), newCount);
            eventPublisher.publishEvent(new ImprovePausedEvent(
                    String.valueOf(agent.getId()), newCount, triggeredByUserId));
        }
    }
}
