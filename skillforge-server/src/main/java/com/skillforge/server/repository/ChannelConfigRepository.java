package com.skillforge.server.repository;

import com.skillforge.server.entity.ChannelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelConfigRepository extends JpaRepository<ChannelConfigEntity, Long> {

    Optional<ChannelConfigEntity> findByPlatform(String platform);

    List<ChannelConfigEntity> findByActiveTrue();
}
