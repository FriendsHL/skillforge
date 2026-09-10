package com.skillforge.server.init;

import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.session.DurableRecoveryFailureException;
import com.skillforge.server.session.DurableRecoveryNotReadyException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableSessionRecoveryPollerTest {

    @Test
    void disabledMasterDoesNotScan() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        SessionRepository repository = mock(SessionRepository.class);
        ChatService chatService = mock(ChatService.class);

        new DurableSessionRecoveryPoller(properties, repository, chatService)
                .recoverExpiredLoops();

        verify(repository, never()).findAll();
    }

    @Test
    void enabledPollerRetriesOnlyProductionRunningSessions_andKeepsFailuresClosed() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        SessionRepository repository = mock(SessionRepository.class);
        ChatService chatService = mock(ChatService.class);
        SessionEntity leaseLive = session("lease-live", "running", "production");
        SessionEntity corrupt = session("corrupt", "running", "production");
        SessionEntity waiting = session("waiting", "waiting_user", "production");
        waiting.setUserId(41L);
        waiting.setHistoryEpoch(9L);
        SessionEntity idle = session("idle", "idle", "production");
        SessionEntity eval = session("eval", "running", SessionEntity.ORIGIN_EVAL);
        when(repository.findAll()).thenReturn(List.of(leaseLive, corrupt, waiting, idle, eval));
        doThrow(new DurableRecoveryNotReadyException())
                .when(chatService).resumeInterruptedTurnAsync("lease-live");
        doThrow(new DurableRecoveryFailureException())
                .when(chatService).resumeInterruptedTurnAsync("corrupt");

        new DurableSessionRecoveryPoller(properties, repository, chatService)
                .recoverExpiredLoops();

        verify(chatService).resumeInterruptedTurnAsync("lease-live");
        verify(chatService).resumeInterruptedTurnAsync("corrupt");
        verify(chatService).republishWaitingInteractiveControl("waiting", 41L, 9L);
        verify(chatService, never()).resumeInterruptedTurnAsync("idle");
        verify(chatService, never()).resumeInterruptedTurnAsync("eval");
    }

    private static SessionEntity session(String id, String runtimeStatus, String origin) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setRuntimeStatus(runtimeStatus);
        session.setOrigin(origin);
        return session;
    }
}
