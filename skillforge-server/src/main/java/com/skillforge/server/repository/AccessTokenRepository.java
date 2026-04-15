package com.skillforge.server.repository;

import com.skillforge.server.entity.AccessTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccessTokenRepository extends JpaRepository<AccessTokenEntity, Long> {
    Optional<AccessTokenEntity> findFirstByOrderByIdAsc();
    boolean existsByToken(String token);
}
