package com.skillforge.server.service;

import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class TeamTaskRuntimeCoordinatorTest {
    @Test
    void loopEventDelegatesToTransactionalRecoveryService() {
        TeamTaskGraphService service = mock(TeamTaskGraphService.class);
        TeamTaskRuntimeCoordinator coordinator = new TeamTaskRuntimeCoordinator(service);

        coordinator.onLoopFinished(new SessionLoopFinishedEvent("worker", "done", "completed", 7L));

        verify(service).handleLoopFinished("worker", "completed");
    }

    @Test
    void scheduledMaintenanceProcessesAttemptsIndependently() {
        TeamTaskGraphService service = mock(TeamTaskGraphService.class);
        when(service.activeAttemptIds()).thenReturn(List.of("a1", "a2"));
        doThrow(new RuntimeException("one failed")).when(service).maintainAttempt("a1");
        TeamTaskRuntimeCoordinator coordinator = new TeamTaskRuntimeCoordinator(service);

        coordinator.maintainLeases();

        verify(service).maintainAttempt("a1");
        verify(service).maintainAttempt("a2");
    }
}
