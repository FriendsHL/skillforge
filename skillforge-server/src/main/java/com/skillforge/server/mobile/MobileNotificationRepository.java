package com.skillforge.server.mobile;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface MobileNotificationRepository extends JpaRepository<MobileNotificationEntity, UUID> {
    boolean existsByTaskIdAndKind(String taskId, String kind);
    List<MobileNotificationEntity> findTop100ByOrderByCreatedAtDesc();
}
