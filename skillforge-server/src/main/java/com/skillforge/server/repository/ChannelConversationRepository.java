package com.skillforge.server.repository;

import com.skillforge.server.entity.ChannelConversationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ChannelConversationRepository
        extends JpaRepository<ChannelConversationEntity, Long> {

    /** PESSIMISTIC_WRITE to serialize concurrent "none exists → create" races (H4). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ChannelConversationEntity c " +
           "WHERE c.platform = :platform AND c.conversationId = :conversationId " +
           "AND c.closedAt IS NULL")
    Optional<ChannelConversationEntity> findActiveForUpdate(
            @Param("platform") String platform,
            @Param("conversationId") String conversationId);

    /** Close an active conversation row immediately via UPDATE (bypasses Hibernate ActionQueue INSERT-before-UPDATE ordering). */
    @Modifying
    @Query("UPDATE ChannelConversationEntity c SET c.closedAt = :closedAt WHERE c.id = :id AND c.closedAt IS NULL")
    int closeById(@Param("id") Long id, @Param("closedAt") Instant closedAt);

    Optional<ChannelConversationEntity> findBySessionIdAndClosedAtIsNull(String sessionId);

    Page<ChannelConversationEntity> findByPlatformOrderByCreatedAtDesc(String platform, Pageable pageable);

    Page<ChannelConversationEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
