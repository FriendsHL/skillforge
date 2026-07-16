package com.skillforge.server.mobile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MobileDeviceRepository extends JpaRepository<MobileDeviceEntity, UUID> {
    Optional<MobileDeviceEntity> findByTokenHash(String tokenHash);
    List<MobileDeviceEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<MobileDeviceEntity> findByUserIdAndStatus(Long userId, String status);
}
