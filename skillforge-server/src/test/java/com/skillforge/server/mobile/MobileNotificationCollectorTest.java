package com.skillforge.server.mobile;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MobileNotificationCollectorTest {
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final MobileNotificationRepository notifications = mock(MobileNotificationRepository.class);
    private final MobileNotificationCollector collector = new MobileNotificationCollector(sessions, agents, notifications);

    @Test
    void onLoopFinished_rootProductionTask_recordsSafeNotification() {
        SessionEntity session = session(null, "production", "task-1");
        AgentEntity agent = new AgentEntity(); agent.setId(3L); agent.setName("Main Assistant"); agent.setAgentType("user");
        when(sessions.findById("s1")).thenReturn(Optional.of(session));
        when(agents.findById(3L)).thenReturn(Optional.of(agent));
        when(notifications.existsByTaskIdAndKind("task-1", "completed")).thenReturn(false);

        collector.onLoopFinished(new SessionLoopFinishedEvent("s1", "secret final answer", "completed", 7L));

        ArgumentCaptor<MobileNotificationEntity> captor = ArgumentCaptor.forClass(MobileNotificationEntity.class);
        verify(notifications).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo("completed");
        assertThat(captor.getValue().getBody()).isEqualTo("Main Assistant 的任务已完成");
        assertThat(captor.getValue().getBody()).doesNotContain("secret final answer");
    }

    @Test
    void onLoopFinished_childOrInternalSession_doesNotNotify() {
        when(sessions.findById("child")).thenReturn(Optional.of(session("parent", "production", "task-child")));
        collector.onLoopFinished(new SessionLoopFinishedEvent("child", "done", "completed", 7L));
        when(sessions.findById("eval")).thenReturn(Optional.of(session(null, "eval", "task-eval")));
        collector.onLoopFinished(new SessionLoopFinishedEvent("eval", "done", "completed", 7L));
        verifyNoInteractions(agents, notifications);
    }

    @Test
    void kind_waitingUserCollapsesToActionRequired_cancelledIsSilent() {
        assertThat(MobileNotificationCollector.kind("waiting_user")).isEqualTo("action_required");
        assertThat(MobileNotificationCollector.kind("cancelled")).isNull();
        assertThat(MobileNotificationCollector.kind("aborted_by_hook")).isNull();
    }

    private SessionEntity session(String parent, String origin, String taskId) {
        SessionEntity s = new SessionEntity(); s.setId(parent == null ? "s1" : "child"); s.setUserId(7L);
        s.setAgentId(3L); s.setParentSessionId(parent); s.setOrigin(origin); s.setActiveRootTraceId(taskId); return s;
    }
}
