package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-V2 M2: tests for {@link SessionScenarioExtractorService#extractFromSession}.
 *
 * <p>Locks both branches:
 * <ul>
 *   <li>session with 1 user message → single-turn entity (legacy regression)</li>
 *   <li>session with ≥2 user messages → multi-turn entity with conversation_turns
 *       JSON containing assistant placeholders</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionScenarioExtractorServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private EvalScenarioDraftRepository evalScenarioDraftRepository;
    @Mock private LlmProviderFactory llmProviderFactory;

    private SessionScenarioExtractorService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("claude");
        service = new SessionScenarioExtractorService(
                sessionRepository, evalScenarioDraftRepository,
                llmProviderFactory, objectMapper, props);
    }

    private SessionEntity sessionWithMessages(String json) {
        SessionEntity s = new SessionEntity();
        s.setId("session-test-1");
        s.setAgentId(42L);
        s.setTitle("Test session");
        s.setMessagesJson(json);
        return s;
    }

    @Test
    @DisplayName("extractFromSession: single user message → single-turn entity (regression)")
    void singleUserMessage_returnsSingleTurn() throws Exception {
        String json = objectMapper.writeValueAsString(List.of(
                Map.of("role", "user", "content", "Help me debug a thing")
        ));
        SessionEntity session = sessionWithMessages(json);

        EvalScenarioEntity entity = service.extractFromSession(session);

        assertThat(entity).isNotNull();
        assertThat(entity.getConversationTurns()).isNull();
        assertThat(entity.getTask()).isEqualTo("Help me debug a thing");
        assertThat(entity.getAgentId()).isEqualTo("42");
        assertThat(entity.getStatus()).isEqualTo("draft");
        assertThat(entity.getOracleType()).isEqualTo("llm_judge");
        assertThat(entity.getSourceSessionId()).isEqualTo("session-test-1");
    }

    @Test
    @DisplayName("extractFromSession: 3 user messages → multi-turn entity with assistant placeholders")
    void multiUserMessage_returnsMultiTurn() throws Exception {
        String json = objectMapper.writeValueAsString(List.of(
                Map.of("role", "user", "content", "first user msg"),
                Map.of("role", "assistant", "content", "first assistant reply"),
                Map.of("role", "user", "content", "second user msg"),
                Map.of("role", "assistant", "content", "second assistant reply"),
                Map.of("role", "user", "content", "third user msg")
        ));
        SessionEntity session = sessionWithMessages(json);

        EvalScenarioEntity entity = service.extractFromSession(session);

        assertThat(entity).isNotNull();
        assertThat(entity.getConversationTurns()).isNotBlank();
        List<Map<String, String>> turns = objectMapper.readValue(
                entity.getConversationTurns(),
                new TypeReference<List<Map<String, String>>>() {});
        // 3 users + 2 assistants from session, plus a trailing assistant placeholder
        // (session ends on user, runtime needs an assistant slot).
        assertThat(turns).hasSize(6);
        assertThat(turns.get(0)).containsEntry("role", "user").containsEntry("content", "first user msg");
        assertThat(turns.get(1)).containsEntry("role", "assistant").containsEntry("content", "<placeholder>");
        assertThat(turns.get(3)).containsEntry("role", "assistant").containsEntry("content", "<placeholder>");
        assertThat(turns.get(5)).containsEntry("role", "assistant").containsEntry("content", "<placeholder>");

        // Task is a multi-turn summary (not just one user msg).
        assertThat(entity.getTask()).contains("Multi-turn session").contains("user messages");
        assertThat(entity.getExtractionRationale()).contains("Multi-turn");
    }

    @Test
    @DisplayName("extractFromSession: ends on assistant → no trailing placeholder padded")
    void multiUserMessage_endsOnAssistant_noPadding() throws Exception {
        String json = objectMapper.writeValueAsString(List.of(
                Map.of("role", "user", "content", "msg 1"),
                Map.of("role", "assistant", "content", "reply 1"),
                Map.of("role", "user", "content", "msg 2"),
                Map.of("role", "assistant", "content", "reply 2")
        ));
        SessionEntity session = sessionWithMessages(json);

        EvalScenarioEntity entity = service.extractFromSession(session);

        List<Map<String, String>> turns = objectMapper.readValue(
                entity.getConversationTurns(),
                new TypeReference<List<Map<String, String>>>() {});
        assertThat(turns).hasSize(4);
        assertThat(turns.get(3)).containsEntry("role", "assistant");
    }

    @Test
    @DisplayName("extractFromSession: empty messagesJson → null (skip)")
    void emptyMessages_returnsNull() {
        SessionEntity session = sessionWithMessages("[]");
        assertThat(service.extractFromSession(session)).isNull();
    }

    @Test
    @DisplayName("extractFromSession: tolerates content blocks list (text blocks concatenated)")
    void contentBlocks_extractedAsText() throws Exception {
        String json = objectMapper.writeValueAsString(List.of(
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", "block 1"),
                        Map.of("type", "tool_use", "id", "abc", "name", "Read")
                )),
                Map.of("role", "assistant", "content", "ok"),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", "block 2 line 1"),
                        Map.of("type", "text", "text", "block 2 line 2")
                ))
        ));
        SessionEntity session = sessionWithMessages(json);

        EvalScenarioEntity entity = service.extractFromSession(session);

        assertThat(entity).isNotNull();
        assertThat(entity.getConversationTurns()).isNotBlank();
        List<Map<String, String>> turns = objectMapper.readValue(
                entity.getConversationTurns(),
                new TypeReference<List<Map<String, String>>>() {});
        assertThat(turns.get(0).get("content")).isEqualTo("block 1"); // tool_use stripped
        assertThat(turns.get(2).get("content")).isEqualTo("block 2 line 1\nblock 2 line 2");
    }
}
