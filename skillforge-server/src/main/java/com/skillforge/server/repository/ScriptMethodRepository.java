package com.skillforge.server.repository;

import com.skillforge.server.entity.ScriptMethodEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScriptMethodRepository extends JpaRepository<ScriptMethodEntity, Long> {

    Optional<ScriptMethodEntity> findByRef(String ref);

    List<ScriptMethodEntity> findByEnabledTrue();

    List<ScriptMethodEntity> findByOwnerId(Long ownerId);

    boolean existsByRef(String ref);
}
