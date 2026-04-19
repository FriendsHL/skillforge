package com.skillforge.server.repository;

import com.skillforge.server.entity.CompiledMethodEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompiledMethodRepository extends JpaRepository<CompiledMethodEntity, Long> {

    List<CompiledMethodEntity> findByStatus(String status);

    Optional<CompiledMethodEntity> findByRef(String ref);

    boolean existsByRef(String ref);
}
