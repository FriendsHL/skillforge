package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.ChatAttachmentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalAppSourceLinkReconciliationIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository sessionMessageRepository;
    @Autowired private ChatAttachmentRepository attachmentRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM t_personal_app_preference");
        attachmentRepository.deleteAll();
        sessionMessageRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                transactionManager);
        sessionService.setPersonalAppSourceLinkReconciler(
                new PersonalAppSourceLinkReconciler(attachmentRepository));
    }

    @Test
    void appendBindsLatestAssistantReferenceAndPublishesArtifact() {
        String sessionId = newSession();
        String artifactId = newArtifact(sessionId);

        sessionService.appendNormalMessages(sessionId, List.of(
                Message.user("build an app"),
                assistantRef(artifactId),
                assistantRefAsMap(artifactId)));

        assertThat(link(artifactId)).containsAllEntriesOf(Map.of(
                "source_message_seq", 2L,
                "status", "published"));
    }

    @Test
    void rewriteRemapsThenClearsStaleSourceWithoutChangingMessageJsonShape() {
        String sessionId = newSession();
        String artifactId = newArtifact(sessionId);
        Message original = assistantRef(artifactId);
        sessionService.appendNormalMessages(sessionId, List.of(Message.user("first"), original));
        String originalJson = sessionMessageRepository
                .findTopBySessionIdOrderBySeqNoDesc(sessionId).orElseThrow().getContentJson();

        sessionService.rewriteMessages(sessionId, List.of(
                append(assistantRef(artifactId)),
                append(Message.user("first"))));

        assertThat(link(artifactId)).containsAllEntriesOf(Map.of(
                "source_message_seq", 0L,
                "status", "published"));
        String rewrittenJson = sessionMessageRepository
                .findBySessionIdOrderBySeqNoAsc(sessionId,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().get(0).getContentJson();
        assertThat(rewrittenJson).isEqualTo(originalJson);

        sessionService.rewriteMessages(sessionId, List.of(append(Message.user("restored before app"))));

        Map<String, Object> cleared = link(artifactId);
        assertThat(cleared.get("source_message_seq")).isNull();
        assertThat(cleared.get("status")).isEqualTo("uploaded");
    }

    @Test
    void branchRewriteDoesNotStealSourceLinkFromOwningSession() {
        String sourceSessionId = newSession();
        String branchSessionId = newSession();
        String artifactId = newArtifact(sourceSessionId);
        Message ref = assistantRef(artifactId);
        sessionService.appendNormalMessages(sourceSessionId, List.of(ref));

        sessionService.rewriteMessages(branchSessionId, List.of(append(ref)));

        assertThat(link(artifactId)).containsAllEntriesOf(Map.of(
                "source_message_seq", 0L,
                "status", "published"));
    }

    private String newSession() {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(1L);
        session.setAgentId(10L);
        session.setTitle("personal-app-source-link");
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        session.setOrigin(SessionEntity.ORIGIN_PRODUCTION);
        return sessionRepository.save(session).getId();
    }

    private String newArtifact(String sessionId) {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId(UUID.randomUUID().toString());
        attachment.setSessionId(sessionId);
        attachment.setUserId(1L);
        attachment.setKind("interactive");
        attachment.setMimeType("text/html");
        attachment.setFilename("app.html");
        attachment.setSizeBytes(10L);
        attachment.setStoragePath("/tmp/app.html");
        attachment.setStatus("uploaded");
        attachment.setOrigin("agent_generated");
        attachment.setSha256("a".repeat(64));
        attachment.setInteractiveManifestJson("""
                {"schemaVersion":1,"title":"App","fallback":"Fallback",
                 "permissions":[],"network":[],"initialData":{},"stateSchema":{}}
                """);
        attachment.setCreatedAt(Instant.parse("2026-07-17T01:00:00Z"));
        return attachmentRepository.saveAndFlush(attachment).getId();
    }

    private static SessionService.AppendMessage append(Message message) {
        return new SessionService.AppendMessage(
                message, SessionService.MSG_TYPE_NORMAL, Collections.emptyMap());
    }

    private static Message assistantRef(String artifactId) {
        Message message = new Message();
        message.setRole(Message.Role.ASSISTANT);
        message.setContent(List.of(ContentBlock.interactiveArtifactRef(
                artifactId, "app.html", "App", 1)));
        return message;
    }

    private static Message assistantRefAsMap(String artifactId) {
        Message message = new Message();
        message.setRole(Message.Role.ASSISTANT);
        message.setContent(List.of(Map.of(
                "type", "interactive_artifact_ref",
                "attachmentId", artifactId)));
        return message;
    }

    private Map<String, Object> link(String artifactId) {
        return jdbcTemplate.queryForMap("""
                SELECT source_message_seq, status
                FROM t_chat_attachment WHERE id = ?
                """, artifactId);
    }
}
