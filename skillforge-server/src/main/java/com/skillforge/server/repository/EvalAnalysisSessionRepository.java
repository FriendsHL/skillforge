package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalAnalysisSessionEntity;
import com.skillforge.server.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EvalAnalysisSessionRepository extends JpaRepository<EvalAnalysisSessionEntity, Long> {

    @Query("""
            SELECT s FROM EvalAnalysisSessionEntity eas
            JOIN SessionEntity s ON s.id = eas.sessionId
            WHERE eas.scenarioId = :scenarioId
              AND s.userId = :userId
            ORDER BY s.updatedAt DESC
            """)
    List<SessionEntity> findSessionsByScenarioIdAndUserIdOrderByUpdatedAtDesc(
            @Param("scenarioId") String scenarioId,
            @Param("userId") Long userId);

    @Query("""
            SELECT eas, s FROM EvalAnalysisSessionEntity eas
            JOIN SessionEntity s ON s.id = eas.sessionId
            WHERE eas.taskId = :taskId
              AND s.userId = :userId
            ORDER BY s.updatedAt DESC
            """)
    List<Object[]> findLinksAndSessionsByTaskIdAndUserIdOrderByUpdatedAtDesc(
            @Param("taskId") String taskId,
            @Param("userId") Long userId);
}
