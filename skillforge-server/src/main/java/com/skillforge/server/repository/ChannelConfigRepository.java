package com.skillforge.server.repository;

import com.skillforge.server.entity.ChannelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChannelConfigRepository extends JpaRepository<ChannelConfigEntity, Long> {

    Optional<ChannelConfigEntity> findByPlatform(String platform);

    List<ChannelConfigEntity> findByActiveTrue();

    /**
     * Targeted update of only {@code config_json}. Used by the weixin cursor writer so a
     * full-entity {@code save()} cannot clobber {@code credentials_json} (bot_token) written
     * concurrently by the QR-confirm bind. Returns rows affected (0 if the config was deleted).
     */
    @Modifying
    @Query("UPDATE ChannelConfigEntity c SET c.configJson = :configJson WHERE c.id = :id")
    int updateConfigJson(@Param("id") Long id, @Param("configJson") String configJson);

    /** Read only {@code config_json} without loading the full managed entity (cursor RMW). */
    @Query("SELECT c.configJson FROM ChannelConfigEntity c WHERE c.id = :id")
    Optional<String> findConfigJsonById(@Param("id") Long id);
}
