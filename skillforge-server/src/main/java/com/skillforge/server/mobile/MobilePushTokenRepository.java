package com.skillforge.server.mobile;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface MobilePushTokenRepository extends JpaRepository<MobilePushTokenEntity, UUID> {
    Optional<MobilePushTokenEntity> findByDeviceIdAndEnvironment(UUID deviceId, String environment);
    List<MobilePushTokenEntity> findByStatus(String status);
}
