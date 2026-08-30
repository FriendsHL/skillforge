package com.skillforge.server.service;

import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TeamTaskRuntimeCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TeamTaskRuntimeCoordinator.class);
    private final TeamTaskGraphService taskGraphService;

    public TeamTaskRuntimeCoordinator(TeamTaskGraphService taskGraphService) {
        this.taskGraphService = taskGraphService;
    }

    @EventListener
    public void onLoopFinished(SessionLoopFinishedEvent event) {
        try {
            taskGraphService.handleLoopFinished(event.sessionId(), event.finalStatus());
        } catch (Exception exception) {
            log.error("Failed to recover Team task after loop finish: sessionId={}", event.sessionId(), exception);
        }
    }

    @Scheduled(fixedDelayString = "${skillforge.team-task.lease-sweep-ms:30000}",
            initialDelayString = "${skillforge.team-task.lease-initial-delay-ms:30000}")
    public void maintainLeases() {
        for (String attemptId : taskGraphService.activeAttemptIds()) {
            try {
                taskGraphService.maintainAttempt(attemptId);
            } catch (Exception exception) {
                log.error("Failed to maintain Team task attempt: attemptId={}", attemptId, exception);
            }
        }
    }
}
