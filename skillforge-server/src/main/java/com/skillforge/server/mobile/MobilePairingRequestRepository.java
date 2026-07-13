package com.skillforge.server.mobile;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MobilePairingRequestRepository extends JpaRepository<MobilePairingRequestEntity, UUID> {
    List<MobilePairingRequestEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MobilePairingRequestEntity p where p.id = :id")
    Optional<MobilePairingRequestEntity> findByIdForUpdate(@Param("id") UUID id);
}
