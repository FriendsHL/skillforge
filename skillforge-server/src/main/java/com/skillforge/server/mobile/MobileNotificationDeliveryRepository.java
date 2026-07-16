package com.skillforge.server.mobile;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;

public interface MobileNotificationDeliveryRepository extends JpaRepository<MobileNotificationDeliveryEntity, UUID> {
    boolean existsByNotificationIdAndDeviceId(UUID notificationId, UUID deviceId);
    List<MobileNotificationDeliveryEntity> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(List<String> statuses, Instant now);
}
