package com.skillforge.server.init;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * V18 数据回填：把 legacy t_session.messages_json 迁移到 t_session_message。
 * 仅回填“有 legacy 消息但行存储为空”的会话，具备幂等性。
 */
@Component
@Order(60)
public class SessionMessageStoreBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageStoreBackfill.class);
    private static final int PAGE_SIZE = 100;

    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final SessionMessageStoreProperties storeProperties;
    private final ObjectMapper objectMapper;

    public SessionMessageStoreBackfill(SessionRepository sessionRepository,
                                       SessionService sessionService,
                                       SessionMessageStoreProperties storeProperties,
                                       ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.storeProperties = storeProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!storeProperties.isBackfillEnabled()) {
            log.info("SessionMessageStoreBackfill skipped because backfill-enabled=false");
            return;
        }
        if (!storeProperties.isRowWriteEnabled()) {
            log.info("SessionMessageStoreBackfill skipped because row-write-enabled=false");
            return;
        }
        int maxSessions = Math.max(1, storeProperties.getMaxBackfillSessionsPerStartup());
        int migratedSessions = 0;
        while (true) {
            if (migratedSessions >= maxSessions) {
                log.info("SessionMessageStoreBackfill reached startup cap: {} session(s)", maxSessions);
                break;
            }
            Page<SessionEntity> page = sessionRepository
                    .findLegacySessionsWithoutRowMessages(PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            int migratedThisRound = 0;
            for (SessionEntity session : page.getContent()) {
                try {
                    List<Message> legacy = parseLegacyMessages(session.getMessagesJson());
                    List<SessionService.AppendMessage> wraps = new ArrayList<>(legacy.size());
                    for (Message msg : legacy) {
                        wraps.add(new SessionService.AppendMessage(
                                msg, SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()));
                    }
                    sessionService.rewriteMessages(session.getId(), wraps);
                    migratedSessions++;
                    migratedThisRound++;
                    if (migratedSessions >= maxSessions) {
                        break;
                    }
                } catch (Exception e) {
                    log.warn("SessionMessageStoreBackfill failed for session {}: {}",
                            session.getId(), e.getMessage());
                }
            }
            if (migratedThisRound == 0) {
                log.warn("SessionMessageStoreBackfill made no progress in this round; stopping to avoid endless retry");
                break;
            }
        }
        if (migratedSessions > 0) {
            log.info("SessionMessageStoreBackfill migrated {} session(s) to row storage", migratedSessions);
        } else {
            log.debug("SessionMessageStoreBackfill: no legacy sessions to migrate");
        }
    }

    private List<Message> parseLegacyMessages(String messagesJson) throws Exception {
        if (messagesJson == null || messagesJson.isBlank()) {
            return Collections.emptyList();
        }
        return objectMapper.readValue(messagesJson, new TypeReference<List<Message>>() {});
    }
}
