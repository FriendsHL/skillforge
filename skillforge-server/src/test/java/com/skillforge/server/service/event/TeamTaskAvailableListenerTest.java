package com.skillforge.server.service.event;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamTaskAvailableListenerTest {
    @Test
    void committedUnblockUsesPersistentMailboxAndSkipsActor() {
        SessionRepository sessions = mock(SessionRepository.class);
        SubAgentRegistry registry = mock(SubAgentRegistry.class);
        SessionEntity actor = member("worker-1");
        SessionEntity peer = member("worker-2");
        when(sessions.findByCollabRunId("team-1")).thenReturn(List.of(actor, peer));
        when(registry.nextSeqNo("worker-2")).thenReturn(11L);
        when(registry.enqueueForSession(eq("worker-2"), contains("task-1"),
                eq("team-task-42-worker-2"), eq(11L))).thenReturn(true);
        TeamTaskAvailableListener listener = new TeamTaskAvailableListener(sessions, registry);

        listener.onTaskAvailable(new TeamTaskAvailableEvent(42L, "team-1", "worker-1", "task-1", "Review"));

        verify(registry).enqueueForSession(eq("worker-2"), contains("availableOnly=true"),
                eq("team-task-42-worker-2"), eq(11L));
        verify(registry).maybeResumeSession("worker-2");
        verify(registry, never()).enqueueForSession(eq("worker-1"), anyString(), anyString(), anyLong());
    }

    private static SessionEntity member(String id) {
        SessionEntity session = new SessionEntity();
        session.setId(id); session.setCollabRunId("team-1"); session.setRuntimeStatus("idle");
        return session;
    }
}
