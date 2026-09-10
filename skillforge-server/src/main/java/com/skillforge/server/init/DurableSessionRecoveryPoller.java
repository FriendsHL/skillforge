package com.skillforge.server.init;

import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.exception.RetryBusyException;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.session.DurableRecoveryNotReadyException;
import com.skillforge.server.session.DurableRecoveryRetryableException;
import com.skillforge.server.session.UnknownOutcomePostActionOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Eventually claims durable loops left behind by a stopped JVM. */
@Component
public class DurableSessionRecoveryPoller {

    private static final Logger log = LoggerFactory.getLogger(
            DurableSessionRecoveryPoller.class);

    private final SessionHistoryProperties properties;
    private final SessionRepository sessionRepository;
    private final ChatService chatService;
    private UnknownOutcomePostActionOrchestrator postActionOrchestrator;

    public DurableSessionRecoveryPoller(
            SessionHistoryProperties properties,
            SessionRepository sessionRepository,
            ChatService chatService) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository");
        this.chatService = Objects.requireNonNull(chatService, "chatService");
    }

    @Autowired
    void configurePostActionRecovery(
            UnknownOutcomePostActionOrchestrator postActionOrchestrator) {
        this.postActionOrchestrator = Objects.requireNonNull(
                postActionOrchestrator, "postActionOrchestrator");
    }

    @Scheduled(initialDelay = 30_000L, fixedDelay = 30_000L)
    void recoverExpiredLoops() {
        if (!properties.isEnabled()) return;
        Set<String> postActionSessions = postActionOrchestrator != null
                ? postActionOrchestrator.recoverPendingContinuations()
                : Set.of();
        List<SessionEntity> sessions = sessionRepository.findAll();
        for (SessionEntity session : sessions) {
            if (SessionEntity.ORIGIN_EVAL.equals(session.getOrigin())) {
                continue;
            }
            if (postActionSessions.contains(session.getId())) continue;
            try {
                if ("waiting_user".equals(session.getRuntimeStatus())) {
                    if (session.getParentSessionId() == null) {
                        chatService.republishWaitingInteractiveControl(
                                session.getId(), session.getUserId(),
                                session.getHistoryEpoch());
                    }
                } else if ("running".equals(session.getRuntimeStatus())) {
                    chatService.resumeInterruptedTurnAsync(session.getId());
                }
            } catch (RetryBusyException locallyRunning) {
                // Expected for a healthy loop on this instance.
            } catch (DurableRecoveryNotReadyException leaseStillLive) {
                log.debug("Durable recovery not claimable yet: sessionId={}",
                        session.getId());
            } catch (DurableRecoveryRetryableException transientFailure) {
                log.info("Durable recovery will retry: sessionId={}", session.getId());
            } catch (RuntimeException recoveryFailure) {
                log.error("Durable recovery poll failed closed: sessionId={}",
                        session.getId(), recoveryFailure);
            }
        }
    }
}
