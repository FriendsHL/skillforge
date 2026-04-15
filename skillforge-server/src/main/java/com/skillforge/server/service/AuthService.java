package com.skillforge.server.service;

import com.skillforge.server.entity.AccessTokenEntity;
import com.skillforge.server.repository.AccessTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccessTokenRepository accessTokenRepository;

    public AuthService(AccessTokenRepository accessTokenRepository) {
        this.accessTokenRepository = accessTokenRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initToken() {
        if (accessTokenRepository.count() == 0) {
            String token = UUID.randomUUID().toString().replace("-", "");
            AccessTokenEntity entity = new AccessTokenEntity();
            entity.setToken(token);
            entity.setCreatedAt(Instant.now());
            accessTokenRepository.save(entity);
            log.info("=== SkillForge Access Token: {} ===", token);
        } else {
            String token = accessTokenRepository.findFirstByOrderByIdAsc()
                    .map(AccessTokenEntity::getToken)
                    .orElse("(unknown)");
            log.info("=== SkillForge Access Token: {} ===", token);
        }
    }

    @Transactional(readOnly = true)
    public String getToken() {
        return accessTokenRepository.findFirstByOrderByIdAsc()
                .map(AccessTokenEntity::getToken)
                .orElseThrow(() -> new IllegalStateException("Access token not initialized"));
    }

    @Transactional(readOnly = true)
    public boolean isValidToken(String token) {
        return accessTokenRepository.existsByToken(token);
    }
}
