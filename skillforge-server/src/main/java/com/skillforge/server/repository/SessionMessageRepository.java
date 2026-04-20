package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM SessionMessageEntity m WHERE m.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") String sessionId);

    long countBySessionId(String sessionId);

    Page<SessionMessageEntity> findBySessionIdOrderBySeqNoAsc(String sessionId, Pageable pageable);

    Optional<SessionMessageEntity> findTopBySessionIdOrderBySeqNoDesc(String sessionId);

    Page<SessionMessageEntity> findBySessionIdAndSeqNoGreaterThanEqualOrderBySeqNoAsc(
            String sessionId, long seqNo, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndMsgTypeOrderBySeqNoDesc(
            String sessionId, String msgType, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndPrunedAtIsNullOrderBySeqNoAsc(
            String sessionId, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndPrunedAtIsNullAndSeqNoGreaterThanOrderBySeqNoAsc(
            String sessionId, long seqNo, Pageable pageable);
}
