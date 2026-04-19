package com.skillforge.server.repository;

import com.skillforge.server.entity.AgentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<AgentEntity, Long> {

    List<AgentEntity> findByOwnerId(Long ownerId);

    List<AgentEntity> findByStatus(String status);

    List<AgentEntity> findByIsPublicTrue();

    boolean existsByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AgentEntity a WHERE a.id = :id")
    Optional<AgentEntity> findByIdForUpdate(@Param("id") Long id);
}
