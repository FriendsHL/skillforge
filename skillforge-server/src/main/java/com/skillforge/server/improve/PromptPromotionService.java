package com.skillforge.server.improve;

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
    private static final double PROMOTION_DELTA_THRESHOLD_PP = 15.0;

    private final PromptAbRunRepository promptAbRunRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PromptPromotionService(PromptAbRunRepository promptAbRunRepository,
                                   PromptVersionRepository promptVersionRepository,
                                   AgentRepository agentRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.promptAbRunRepository = promptAbRunRepository;
        this.promptVersionRepository = promptVersionRepository;
        this.agentRepository = agentRepository;
        this.eventPublisher = eventPublisher;
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
        Double delta = abRun.getDeltaPassRate();
        if (delta == null || delta < PROMOTION_DELTA_THRESHOLD_PP) {
            log.info("Promotion rejected: delta {} < threshold {} for agent {}",
                    delta, PROMOTION_DELTA_THRESHOLD_PP, agentId);
            updateAbDeclineTracking(agent, triggeredByUserId);
            agentRepository.save(agent);
            return PromotionResult.rejected("Delta " + delta + " below threshold " + PROMOTION_DELTA_THRESHOLD_PP);
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

        // Atomic promotion
        Instant now = Instant.now();

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

        // Update agent
        agent.setSystemPrompt(candidate.getContent());
        agent.setActivePromptVersionId(candidate.getId());
        agent.setLastPromotedAt(now);
        agent.setAbDeclineCount(0);
        agentRepository.save(agent);

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
