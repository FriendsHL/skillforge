package com.skillforge.server.mobile;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class MobileNotificationCollector {
    private final SessionRepository sessionRepository;
    private final AgentRepository agentRepository;
    private final MobileNotificationRepository notificationRepository;

    public MobileNotificationCollector(SessionRepository sessionRepository, AgentRepository agentRepository,
                                       MobileNotificationRepository notificationRepository) {
        this.sessionRepository = sessionRepository; this.agentRepository = agentRepository;
        this.notificationRepository = notificationRepository;
    }

    @EventListener
    @Transactional
    public void onLoopFinished(SessionLoopFinishedEvent event) {
        String kind = kind(event.finalStatus());
        if (kind == null || event.sessionId() == null || event.userId() == null) return;
        SessionEntity session = sessionRepository.findById(event.sessionId()).orElse(null);
        if (!eligible(session, event.userId())) return;
        AgentEntity agent = agentRepository.findById(session.getAgentId()).orElse(null);
        if (agent == null || "system".equals(agent.getAgentType())) return;
        String taskId = session.getActiveRootTraceId();
        if (taskId == null || taskId.isBlank() || notificationRepository.existsByTaskIdAndKind(taskId, kind)) return;

        MobileNotificationEntity row = new MobileNotificationEntity();
        row.setId(UUID.randomUUID()); row.setTaskId(taskId); row.setSessionId(session.getId());
        row.setUserId(event.userId()); row.setKind(kind); row.setTitle("SkillForge");
        row.setBody(body(kind, agent.getName())); row.setCreatedAt(Instant.now());
        notificationRepository.saveAndFlush(row);
    }

    static String kind(String status) {
        return switch (status == null ? "" : status) {
            case "completed" -> "completed";
            case "error" -> "error";
            case "waiting_user" -> "action_required";
            default -> null;
        };
    }

    private static boolean eligible(SessionEntity session, Long userId) {
        return session != null && userId.equals(session.getUserId())
                && session.getParentSessionId() == null
                && SessionEntity.ORIGIN_PRODUCTION.equals(session.getOrigin());
    }

    private static String body(String kind, String agentName) {
        String who = agentName == null || agentName.isBlank() ? "Agent" : agentName;
        return switch (kind) {
            case "completed" -> who + " 的任务已完成";
            case "error" -> who + " 的任务执行失败";
            default -> who + " 的任务需要你的操作";
        };
    }
}
