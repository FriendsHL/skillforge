package com.skillforge.server.repository;

import com.skillforge.server.entity.ChannelMessageDedupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChannelMessageDedupRepository
        extends JpaRepository<ChannelMessageDedupEntity, String> {

    /**
     * INSERT ON CONFLICT DO NOTHING. Returns 1 on fresh insert, 0 on duplicate.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO t_channel_message_dedup (platform_message_id, platform, created_at)
            VALUES (:messageId, :platform, NOW())
            ON CONFLICT (platform_message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(@Param("platform") String platform,
                     @Param("messageId") String messageId);

    default boolean tryInsert(String platform, String messageId) {
        return insertIgnore(platform, messageId) > 0;
    }
}
