package com.skillforge.server.service;

import com.skillforge.server.entity.EvalAnalysisSessionEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.EvalAnalysisSessionRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvalAnalysisSessionService")
class EvalAnalysisSessionServiceTest {

    @Mock
    private SessionService sessionService;
    @Mock
    private EvalAnalysisSessionRepository evalAnalysisSessionRepository;
    @Mock
    private EvalTaskRepository evalTaskRepository;

    private EvalAnalysisSessionService service;

    @BeforeEach
    void setUp() {
        service = new EvalAnalysisSessionService(sessionService, evalAnalysisSessionRepository, evalTaskRepository);
    }

    @Test
    @DisplayName("createScenarioHistoryAnalysisSession creates chat session + analysis link without touching sourceScenarioId")
    void createScenarioHistoryAnalysisSession_createsLink() {
        when(evalAnalysisSessionRepository.save(any(EvalAnalysisSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        SessionEntity session = new SessionEntity();
        session.setId("sess-1");
        session.setUserId(7L);
        session.setAgentId(42L);
        when(sessionService.createSession(7L, 42L)).thenReturn(session);

        SessionEntity created = service.createScenarioHistoryAnalysisSession(7L, 42L, "scenario-a");

        assertThat(created.getId()).isEqualTo("sess-1");
        ArgumentCaptor<EvalAnalysisSessionEntity> captor = ArgumentCaptor.forClass(EvalAnalysisSessionEntity.class);
        verify(evalAnalysisSessionRepository).save(captor.capture());
        EvalAnalysisSessionEntity saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo("sess-1");
        assertThat(saved.getScenarioId()).isEqualTo("scenario-a");
        assertThat(saved.getAnalysisType()).isEqualTo(EvalAnalysisSessionEntity.TYPE_SCENARIO_HISTORY);
        assertThat(saved.getTaskId()).isNull();
        assertThat(saved.getTaskItemId()).isNull();
    }

    @Test
    @DisplayName("createTaskOverallAnalysisSession updates task.analysisSessionId")
    void createTaskOverallAnalysisSession_updatesTask() {
        when(evalAnalysisSessionRepository.save(any(EvalAnalysisSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        SessionEntity session = new SessionEntity();
        session.setId("sess-2");
        when(sessionService.createSession(7L, 42L)).thenReturn(session);

        EvalTaskEntity task = new EvalTaskEntity();
        task.setId("task-1");
        when(evalTaskRepository.findById("task-1")).thenReturn(java.util.Optional.of(task));
        when(evalTaskRepository.save(any(EvalTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createTaskOverallAnalysisSession(7L, 42L, "task-1");

        assertThat(task.getAnalysisSessionId()).isEqualTo("sess-2");
        verify(evalTaskRepository).save(task);
    }

    @Test
    @DisplayName("listScenarioAnalysisSessions returns linked sessions in repository order")
    void listScenarioAnalysisSessions_returnsSessions() {
        SessionEntity first = new SessionEntity();
        first.setId("s1");
        SessionEntity second = new SessionEntity();
        second.setId("s2");
        when(evalAnalysisSessionRepository.findSessionsByScenarioIdAndUserIdOrderByUpdatedAtDesc("scenario-a", 7L))
                .thenReturn(List.of(first, second));

        List<SessionEntity> result = service.listScenarioAnalysisSessions("scenario-a", 7L);

        assertThat(result).extracting(SessionEntity::getId).containsExactly("s1", "s2");
    }

    @Test
    @DisplayName("listTaskAnalysisSessions returns link metadata together with session")
    void listTaskAnalysisSessions_returnsViews() {
        EvalAnalysisSessionEntity link = new EvalAnalysisSessionEntity();
        link.setAnalysisType(EvalAnalysisSessionEntity.TYPE_RUN_CASE);
        link.setTaskId("task-1");
        link.setTaskItemId(9L);
        link.setScenarioId("scenario-a");
        SessionEntity session = new SessionEntity();
        session.setId("s1");
        session.setUpdatedAt(LocalDateTime.now());
        when(evalAnalysisSessionRepository.findLinksAndSessionsByTaskIdAndUserIdOrderByUpdatedAtDesc("task-1", 7L))
                .thenReturn(List.<Object[]>of(new Object[]{link, session}));

        List<EvalAnalysisSessionService.TaskAnalysisSessionView> result =
                service.listTaskAnalysisSessions("task-1", 7L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).link().getTaskItemId()).isEqualTo(9L);
        assertThat(result.get(0).session().getId()).isEqualTo("s1");
    }
}
