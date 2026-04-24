package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip 集成测试：验证 {@code Message.reasoningContent} 在 append / reload
 * 路径上不丢失。覆盖 NORMAL append、无 reasoningContent、混合批次以及
 * SUMMARY 类型 append 四种场景，确保 {@code SessionService.appendRowsOnce}
 * 与 {@code SessionService.toStoredMessages} 同步写入 / 回读新列。
 */
@DisplayName("SessionService.reasoning_content persistence round-trip")
class SessionServiceReasoningContentIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionMessageRepository sessionMessageRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionMessageRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(), // defaults: rowWrite/rowRead 均 true
                new ObjectMapper(),
                transactionManager
        );
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setAgentId(10L);
        s.setTitle("reasoning-content-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        sessionRepository.save(s);
        return s.getId();
    }

    @Test
    @DisplayName("append assistant message with reasoningContent → reload preserves it")
    void appendAssistant_withReasoningContent_isPersistedAndReloaded() {
        // Arrange
        String sid = newSession();
        Message assistant = Message.assistant("final answer");
        assistant.setReasoningContent("internal thinking trace that must survive");

        // Act
        sessionService.appendNormalMessages(sid, List.of(assistant));
        List<Message> reloaded = sessionService.getContextMessages(sid);

        // Assert
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).getReasoningContent())
                .isEqualTo("internal thinking trace that must survive");
    }

    @Test
    @DisplayName("append user message without reasoningContent → reloaded value is null (no default)")
    void appendUser_withoutReasoningContent_isNullOnReload() {
        // Arrange
        String sid = newSession();
        Message user = Message.user("hello");

        // Act
        sessionService.appendNormalMessages(sid, List.of(user));
        List<Message> reloaded = sessionService.getContextMessages(sid);

        // Assert
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).getReasoningContent()).isNull();
    }

    @Test
    @DisplayName("mixed batch: assistant-with-reasoning + user → each round-trips independently")
    void mixedBatch_assistantWithReasoning_andUser_roundTripsIndependently() {
        // Arrange
        String sid = newSession();
        Message assistant = Message.assistant("answer");
        assistant.setReasoningContent("why I answered");
        Message followup = Message.user("follow-up question");

        // Act
        sessionService.appendNormalMessages(sid, List.of(assistant, followup));
        List<Message> reloaded = sessionService.getContextMessages(sid);

        // Assert
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(0).getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(reloaded.get(0).getReasoningContent()).isEqualTo("why I answered");
        assertThat(reloaded.get(1).getRole()).isEqualTo(Message.Role.USER);
        assertThat(reloaded.get(1).getReasoningContent()).isNull();
    }

    @Test
    @DisplayName("SUMMARY append with reasoningContent → reload preserves field + msgType")
    void summaryAppend_withReasoningContent_roundTripsViaAppendRowsOnce() {
        // Arrange
        String sid = newSession();
        Message assistant = Message.assistant("compacted summary body");
        assistant.setReasoningContent("reasoning preserved across SUMMARY rewrite path");

        // Act — 走 appendMessages(id, List<Message>, MSG_TYPE_SUMMARY) → appendRowsOnce，
        // 与 CompactionService rewrite 收敛点一致（都经 appendRowsOnce 落行）。
        sessionService.appendMessages(sid, List.of(assistant), SessionService.MSG_TYPE_SUMMARY);

        // Assert：走真实读路径 getFullHistoryRecords → toStoredMessages
        List<SessionService.StoredMessage> records = sessionService.getFullHistoryRecords(sid);
        assertThat(records).hasSize(1);
        SessionService.StoredMessage stored = records.get(0);
        assertThat(stored.msgType()).isEqualTo(SessionService.MSG_TYPE_SUMMARY);
        assertThat(stored.message().getReasoningContent())
                .isEqualTo("reasoning preserved across SUMMARY rewrite path");
    }
}
