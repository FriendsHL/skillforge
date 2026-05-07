package com.skillforge.server.mcp.repository;

import com.skillforge.server.mcp.entity.McpServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServerEntity, Long> {

    Optional<McpServerEntity> findByName(String name);

    List<McpServerEntity> findByEnabledTrue();

    boolean existsByName(String name);
}
