package com.skillforge.server.service.event;

import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.service.SessionTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SessionTasksChangedListener {
    private static final Logger log = LoggerFactory.getLogger(SessionTasksChangedListener.class);
    private final SessionTaskService taskService;
    private final ChatEventBroadcaster broadcaster;

    public SessionTasksChangedListener(SessionTaskService taskService, ChatEventBroadcaster broadcaster) {
        this.taskService = taskService;
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(SessionTasksChangedEvent event) {
        try {
            SessionTaskSnapshotResponse snapshot = taskService.snapshotForSystem(
                    event.sessionId(), true, SessionTaskService.MAX_TASKS_PER_SESSION);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", snapshot.sessionId());
            payload.put("summary", snapshot.summary());
            payload.put("tasks", snapshot.tasks());
            payload.put("generatedAt", snapshot.generatedAt());
            broadcaster.sessionTasksSnapshot(event.sessionId(), payload);
        } catch (Exception e) {
            log.warn("Session task snapshot broadcast failed: sessionId={}", event.sessionId(), e);
        }
    }
}
