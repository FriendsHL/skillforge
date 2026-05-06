package com.skillforge.server.service;

import com.skillforge.server.entity.EvalAnalysisSessionEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.EvalAnalysisSessionRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class EvalAnalysisSessionService {

    private final SessionService sessionService;
    private final EvalAnalysisSessionRepository evalAnalysisSessionRepository;
    private final EvalTaskRepository evalTaskRepository;

    public EvalAnalysisSessionService(SessionService sessionService,
                                      EvalAnalysisSessionRepository evalAnalysisSessionRepository,
                                      EvalTaskRepository evalTaskRepository) {
        this.sessionService = sessionService;
        this.evalAnalysisSessionRepository = evalAnalysisSessionRepository;
        this.evalTaskRepository = evalTaskRepository;
    }

    @Transactional
    public SessionEntity createScenarioHistoryAnalysisSession(Long userId, Long agentId, String scenarioId) {
        SessionEntity session = sessionService.createSession(userId, agentId);
        saveLink(session.getId(), null, null, scenarioId, EvalAnalysisSessionEntity.TYPE_SCENARIO_HISTORY);
        return session;
    }

    @Transactional
    public SessionEntity createTaskOverallAnalysisSession(Long userId, Long agentId, String taskId) {
        SessionEntity session = sessionService.createSession(userId, agentId);
        saveLink(session.getId(), taskId, null, null, EvalAnalysisSessionEntity.TYPE_RUN_OVERALL);
        evalTaskRepository.findById(taskId).ifPresent(task -> {
            task.setAnalysisSessionId(session.getId());
            evalTaskRepository.save(task);
        });
        return session;
    }

    @Transactional
    public SessionEntity createTaskItemAnalysisSession(Long userId, Long agentId,
                                                       String taskId, Long taskItemId, String scenarioId) {
        SessionEntity session = sessionService.createSession(userId, agentId);
        saveLink(session.getId(), taskId, taskItemId, scenarioId, EvalAnalysisSessionEntity.TYPE_RUN_CASE);
        return session;
    }

    @Transactional(readOnly = true)
    public List<SessionEntity> listScenarioAnalysisSessions(String scenarioId, Long userId) {
        return evalAnalysisSessionRepository.findSessionsByScenarioIdAndUserIdOrderByUpdatedAtDesc(scenarioId, userId);
    }

    @Transactional(readOnly = true)
    public List<TaskAnalysisSessionView> listTaskAnalysisSessions(String taskId, Long userId) {
        return evalAnalysisSessionRepository.findLinksAndSessionsByTaskIdAndUserIdOrderByUpdatedAtDesc(taskId, userId)
                .stream()
                .map(row -> new TaskAnalysisSessionView(
                        (EvalAnalysisSessionEntity) row[0],
                        (SessionEntity) row[1]
                ))
                .collect(Collectors.toList());
    }

    private void saveLink(String sessionId, String taskId, Long taskItemId, String scenarioId, String analysisType) {
        EvalAnalysisSessionEntity entity = new EvalAnalysisSessionEntity();
        entity.setSessionId(sessionId);
        entity.setTaskId(taskId);
        entity.setTaskItemId(taskItemId);
        entity.setScenarioId(scenarioId);
        entity.setAnalysisType(analysisType);
        evalAnalysisSessionRepository.save(entity);
    }

    public record TaskAnalysisSessionView(
            EvalAnalysisSessionEntity link,
            SessionEntity session
    ) {
    }
}
