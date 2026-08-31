package com.skillforge.server.service.event;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class TeamTaskAvailableListener {
    private static final Logger log = LoggerFactory.getLogger(TeamTaskAvailableListener.class);
    private final SessionRepository sessionRepository;
    private final SubAgentRegistry registry;

    public TeamTaskAvailableListener(SessionRepository sessionRepository, SubAgentRegistry registry) {
        this.sessionRepository = sessionRepository;
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskAvailable(TeamTaskAvailableEvent event) {
        String payload = "[TeamTaskEvent]\n"
                + "A shared task is now available. Call TaskList with availableOnly=true before claiming.\n"
                + "taskId: " + event.taskId() + "\n"
                + "subject: " + event.subject() + "\n"
                + "[/TeamTaskEvent]";
        for (SessionEntity member : sessionRepository.findByCollabRunId(event.collabRunId())) {
            if (member.getId().equals(event.actorSessionId())) continue;
            String messageId = messageIdFor(event.eventId(), member.getId());
            try {
                if (registry.enqueueForSession(member.getId(), payload, messageId,
                        registry.nextSeqNo(member.getId()))) {
                    registry.maybeResumeSession(member.getId());
                }
            } catch (Exception exception) {
                log.error("Failed to notify Team member about available task: memberSessionId={} taskId={}",
                        member.getId(), event.taskId(), exception);
            }
        }
    }

    static String messageIdFor(Long eventId, String memberSessionId) {
        String dedupKey = "team-task-" + eventId + "-" + memberSessionId;
        return UUID.nameUUIDFromBytes(dedupKey.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
