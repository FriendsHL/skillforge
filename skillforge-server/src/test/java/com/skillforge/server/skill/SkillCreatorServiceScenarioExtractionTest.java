package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.2 (2026-05-18) — scenario-extraction helper
 * coverage. Locks down the JSON contract for {@code evals/evals.json} (entries
 * 1, 2 zip-derived path) and the session→scenario heuristic (entry 4 extract
 * path).
 *
 * <p>Why one test class instead of two: both helpers live on SkillCreatorService
 * and share the same wiring, so a single fixture covers both extraction
 * dimensions efficiently.
 */
@DisplayName("SkillCreatorService — Phase 1.2 ephemeral scenario extraction")
class SkillCreatorServiceScenarioExtractionTest {

    private SkillCreatorService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        service = new SkillCreatorService(
                mock(SkillDraftRepository.class),
                mock(SkillRepository.class),
                mock(EvalScenarioDraftRepository.class),
                mock(SessionRepository.class),
                mock(SessionService.class),
                mock(ChatService.class),
                mock(AgentService.class),
                mock(SubAgentRegistry.class),
                objectMapper,
                mock(ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromZip: parses 2-eval evals.json into 2 scenarios")
    void zipExtraction_parsesEvalsJson(@TempDir Path tmp) throws IOException {
        Path evals = tmp.resolve("evals");
        Files.createDirectories(evals);
        Files.writeString(evals.resolve("evals.json"), """
                {
                  "skill_name": "test-skill",
                  "evals": [
                    {"id": 1, "prompt": "what is 2+2?", "expected_output": "4"},
                    {"id": 2, "prompt": "list the months", "expected_output": "Jan..Dec"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromZip(tmp, 42L);

        assertThat(scenarios).hasSize(2);
        assertThat(scenarios)
                .extracting(EvalScenarioEntity::getTask)
                .containsExactly("what is 2+2?", "list the months");
        assertThat(scenarios)
                .as("all scenarios must be tagged 'ephemeral' so V6 cleanup picks them up post-aggregate")
                .allMatch(s -> "ephemeral".equals(s.getStatus()));
        assertThat(scenarios)
                .extracting(EvalScenarioEntity::getAgentId)
                .as("targetAgentId is stamped on each scenario for dispatchEvaluation")
                .allMatch("42"::equals);
        assertThat(scenarios.get(0).getOracleExpected()).isEqualTo("4");
        assertThat(scenarios.get(0).getCategory()).isEqualTo("skill-creator-eval");
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromZip: missing evals.json → empty list (legacy upload path)")
    void zipExtraction_missingEvalsJson_emptyList(@TempDir Path tmp) {
        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromZip(tmp, 42L);
        assertThat(scenarios).isEmpty();
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromZip: malformed JSON → empty list (no exception)")
    void zipExtraction_malformedJson_emptyList(@TempDir Path tmp) throws IOException {
        Path evals = tmp.resolve("evals");
        Files.createDirectories(evals);
        Files.writeString(evals.resolve("evals.json"), "{not-valid-json", StandardCharsets.UTF_8);

        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromZip(tmp, 42L);
        assertThat(scenarios)
                .as("malformed evals.json must not crash the upload — fall back to legacy")
                .isEmpty();
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromZip: empty evals array → empty list")
    void zipExtraction_emptyEvalsArray_emptyList(@TempDir Path tmp) throws IOException {
        Path evals = tmp.resolve("evals");
        Files.createDirectories(evals);
        Files.writeString(evals.resolve("evals.json"), "{\"skill_name\":\"x\",\"evals\":[]}",
                StandardCharsets.UTF_8);

        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromZip(tmp, 42L);
        assertThat(scenarios).isEmpty();
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromZip: evals entry missing prompt → entry skipped, others kept")
    void zipExtraction_entryMissingPrompt_skipped(@TempDir Path tmp) throws IOException {
        Path evals = tmp.resolve("evals");
        Files.createDirectories(evals);
        Files.writeString(evals.resolve("evals.json"), """
                {
                  "evals": [
                    {"id": 1, "expected_output": "x"},
                    {"id": 2, "prompt": "valid", "expected_output": "y"},
                    {"id": 3, "prompt": "   "}
                  ]
                }
                """, StandardCharsets.UTF_8);

        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromZip(tmp, 42L);
        assertThat(scenarios).hasSize(1);
        assertThat(scenarios.get(0).getTask()).isEqualTo("valid");
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromSessions: pulls first user message from each session")
    void sessionExtraction_pullsFirstUserPrompt() {
        SessionEntity s1 = sessionWithMessages("sess-1",
                "[{\"role\":\"user\",\"content\":\"first prompt text\"},"
                        + "{\"role\":\"assistant\",\"content\":\"reply\"}]");
        SessionEntity s2 = sessionWithMessages("sess-2",
                "[{\"role\":\"user\",\"content\":\"second prompt\"}]");

        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromSessions(
                Arrays.asList(s1, s2), 99L);

        assertThat(scenarios).hasSize(2);
        assertThat(scenarios)
                .extracting(EvalScenarioEntity::getTask)
                .containsExactly("first prompt text", "second prompt");
        assertThat(scenarios)
                .extracting(EvalScenarioEntity::getSourceSessionId)
                .containsExactly("sess-1", "sess-2");
        assertThat(scenarios)
                .allMatch(s -> "ephemeral".equals(s.getStatus()));
        assertThat(scenarios)
                .allMatch(s -> "session_derived".equals(s.getCategory()));
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromSessions: array-shape user content (multimodal) extracts text block")
    void sessionExtraction_multimodalContent() {
        SessionEntity s = sessionWithMessages("sess-mm",
                "[{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"the text part\"},"
                        + "{\"type\":\"image\",\"source\":{\"data\":\"...\"}}"
                        + "]}]");

        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromSessions(
                List.of(s), 99L);
        assertThat(scenarios).hasSize(1);
        assertThat(scenarios.get(0).getTask()).isEqualTo("the text part");
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromSessions: session with no user message → skipped")
    void sessionExtraction_noUserMessage_skipped() {
        SessionEntity s = sessionWithMessages("sess-empty",
                "[{\"role\":\"assistant\",\"content\":\"bare reply\"}]");

        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromSessions(
                List.of(s), 99L);
        assertThat(scenarios).isEmpty();
    }

    @Test
    @DisplayName("buildEphemeralScenariosFromSessions: malformed messagesJson → skipped (no exception)")
    void sessionExtraction_malformedJson_skipped() {
        SessionEntity s = sessionWithMessages("sess-bad", "{not-valid-json");

        List<EvalScenarioEntity> scenarios = service.buildEphemeralScenariosFromSessions(
                List.of(s), 99L);
        assertThat(scenarios).isEmpty();
    }

    private SessionEntity sessionWithMessages(String id, String messagesJson) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(1L);
        s.setAgentId(99L);
        s.setMessageCount(1);
        s.setMessagesJson(messagesJson);
        return s;
    }
}
