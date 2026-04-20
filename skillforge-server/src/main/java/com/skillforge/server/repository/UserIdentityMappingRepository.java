package com.skillforge.server.repository;

import com.skillforge.server.entity.UserIdentityMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserIdentityMappingRepository
        extends JpaRepository<UserIdentityMappingEntity, Long> {

    Optional<UserIdentityMappingEntity> findByPlatformAndPlatformUserId(
            String platform, String platformUserId);
}
