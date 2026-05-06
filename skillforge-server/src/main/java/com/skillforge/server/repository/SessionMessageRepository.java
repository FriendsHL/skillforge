package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM SessionMessageEntity m WHERE m.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") String sessionId);

    long countBySessionId(String sessionId);

    Page<SessionMessageEntity> findBySessionIdOrderBySeqNoAsc(String sessionId, Pageable pageable);

    Optional<SessionMessageEntity> findTopBySessionIdOrderBySeqNoDesc(String sessionId);

    Optional<SessionMessageEntity> findTopBySessionIdAndMsgTypeAndPrunedAtIsNullOrderBySeqNoDesc(
            String sessionId, String msgType);

    Optional<SessionMessageEntity> findTopByTraceIdAndRoleOrderBySeqNoAsc(String traceId, String role);

    Optional<SessionMessageEntity> findTopByTraceIdAndRoleOrderBySeqNoDesc(String traceId, String role);

    Page<SessionMessageEntity> findBySessionIdAndSeqNoGreaterThanEqualOrderBySeqNoAsc(
            String sessionId, long seqNo, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndMsgTypeOrderBySeqNoDesc(
            String sessionId, String msgType, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndPrunedAtIsNullOrderBySeqNoAsc(
            String sessionId, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndPrunedAtIsNullAndSeqNoGreaterThanOrderBySeqNoAsc(
            String sessionId, long seqNo, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndMsgTypeAndPrunedAtIsNullAndSeqNoGreaterThanOrderBySeqNoAsc(
            String sessionId, String msgType, long seqNo, Pageable pageable);

    long countBySessionIdAndRoleAndMsgTypeAndPrunedAtIsNullAndSeqNoGreaterThan(
            String sessionId, String role, String msgType, long seqNo);

    Optional<SessionMessageEntity> findBySessionIdAndMessageTypeAndControlId(
            String sessionId, String messageType, String controlId);

    Optional<SessionMessageEntity> findTopBySessionIdAndMessageTypeAndAnsweredAtIsNullOrderBySeqNoDesc(
            String sessionId, String messageType);

    /**
     * OBS-2 M3 follow-up — restore "trace title = first user message" semantics
     * after /api/traces switched to t_llm_trace (which has no input column).
     * <p>
     * Returns one row per traceId: (traceId, contentJson) of the earliest user
     * message belonging to that trace. PostgreSQL DISTINCT ON guarantees the
     * first row per trace_id given the ORDER BY (trace_id, seq_no ASC).
     */
    @Query(value = "SELECT DISTINCT ON (trace_id) trace_id, content_json " +
                   "FROM t_session_message " +
                   "WHERE trace_id IN (:traceIds) AND role = 'user' " +
                   "ORDER BY trace_id, seq_no ASC",
           nativeQuery = true)
    List<Object[]> findFirstUserMessageContentByTraceIds(
            @Param("traceIds") Collection<String> traceIds);
}
