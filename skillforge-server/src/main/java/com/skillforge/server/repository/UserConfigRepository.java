package com.skillforge.server.repository;

import com.skillforge.server.entity.UserConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserConfigRepository extends JpaRepository<UserConfigEntity, Long> {
    Optional<UserConfigEntity> findByUserId(Long userId);
}
