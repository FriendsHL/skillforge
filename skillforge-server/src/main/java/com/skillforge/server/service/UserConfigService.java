package com.skillforge.server.service;

import com.skillforge.server.entity.UserConfigEntity;
import com.skillforge.server.repository.UserConfigRepository;
import org.springframework.stereotype.Service;

/**
 * Per-user CLAUDE.md storage. Superseded for prompt injection by the built-in global
 * system prompt (SKILLFORGE-SYSTEM-PROMPT, see {@code GlobalSystemPromptProvider}): these
 * methods remain for the existing REST API but the value is no longer injected into any
 * agent's system prompt.
 */
@Service
public class UserConfigService {

    private final UserConfigRepository repository;

    public UserConfigService(UserConfigRepository repository) {
        this.repository = repository;
    }

    public String getClaudeMd(Long userId) {
        return repository.findByUserId(userId)
                .map(UserConfigEntity::getClaudeMd)
                .orElse(null);
    }

    public void saveClaudeMd(Long userId, String claudeMd) {
        UserConfigEntity entity = repository.findByUserId(userId)
                .orElseGet(() -> {
                    UserConfigEntity e = new UserConfigEntity();
                    e.setUserId(userId);
                    return e;
                });
        entity.setClaudeMd(claudeMd);
        repository.save(entity);
    }
}
