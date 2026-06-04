package com.skillforge.server.evolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BC-M1: harvesting a failed Edit span into a draft eval scenario — fixture
 * reconstruction, path rebasing (absolute → relative; repo-root → /tmp/eval/),
 * behavioral oracle, and the no-prior-content skip path.
 */
@ExtendWith(MockitoExtension.class)
class BadCaseHarvestServiceTest {

    private static final String REPO_ROOT = "/Users/youren/myspace/skillforge/";
    private static final String SESSION_ID = "sess-abcdef12-3456";
    private static final String ABS_PATH = REPO_ROOT + "skillforge-core/Foo.java";

    @Mock
    private LlmSpanRepository spanRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private EvalScenarioDraftRepository scenarioRepository;

    private BadCaseHarvestService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new BadCaseHarvestService(spanRepository, sessionRepository,
                scenarioRepository, objectMapper, REPO_ROOT);
    }

    private LlmSpanEntity toolSpan(String spanId, String name, String inputSummary,
                                   String outputSummary, String error, long startedAtSec) {
        LlmSpanEntity s = new LlmSpanEntity();
        s.setSpanId(spanId);
        s.setSessionId(SESSION_ID);
        s.setKind("tool");
        s.setName(name);
        s.setInputSummary(inputSummary);
        s.setOutputSummary(outputSummary);
        s.setError(error);
        s.setStartedAt(Instant.ofEpochSecond(startedAtSec));
        return s;
    }

    @Test
    @DisplayName("rebuilds task + fixture + behavioral oracle from a prior Read + failed Edit")
    void harvest_editStaleWithPriorRead_rebuildsScenario() {
        LlmSpanEntity read = toolSpan("read-1", "Read",
                "{\"file_path\":\"" + ABS_PATH + "\"}",
                "package com.foo;\nclass Foo {}", null, 100);
        LlmSpanEntity failedEdit = toolSpan("fail-1", "Edit",
                "{\"file_path\":\"" + ABS_PATH + "\",\"old_string\":\"stale\",\"new_string\":\"fresh\"}",
                null, "old_string not found in file", 200);
        when(spanRepository.findBySessionIdOrderByStartedAtAsc(SESSION_ID))
                .thenReturn(List.of(read, failedEdit));

        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setAgentId(7L);
        session.setMessagesJson("[{\"role\":\"user\",\"content\":\"Fix the bug in "
                + ABS_PATH + "\"}]");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(scenarioRepository.save(any(EvalScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<EvalScenarioEntity> result = service.harvestEditStaleCase(SESSION_ID, "fail-1");

        assertThat(result).isPresent();
        EvalScenarioEntity sc = result.get();
        assertThat(sc.getSourceType()).isEqualTo(EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED);
        assertThat(sc.getPurpose()).isEqualTo(EvalScenarioEntity.PURPOSE_REGRESSION);
        assertThat(sc.getStatus()).isEqualTo("draft");
        assertThat(sc.getSourceRef()).isEqualTo("session:" + SESSION_ID);
        assertThat(sc.getSourceSessionId()).isEqualTo(SESSION_ID);
        assertThat(sc.getAgentId()).isEqualTo("7");
        assertThat(sc.getOracleType()).isEqualTo("tool_error_absence");

        // Path rebase: task prefix repo-root → /tmp/eval/; fixture key is relative.
        assertThat(sc.getTask()).isEqualTo("Fix the bug in /tmp/eval/skillforge-core/Foo.java");
        assertThat(sc.getFixtureFiles())
                .containsEntry("skillforge-core/Foo.java", "package com.foo;\nclass Foo {}");

        // Oracle JSON carries the signature derived from the failed span.
        try {
            JsonNode oracle = objectMapper.readTree(sc.getOracleExpected());
            assertThat(oracle.path("tool").asText()).isEqualTo("Edit");
            assertThat(oracle.path("errorSignature").asText()).isEqualTo("old_string not found");
            assertThat(oracle.path("passWhen").asText()).isEqualTo("no_match");
            // BC-M2a: path-scope the engagement check to the harvested target file,
            // and measure over multiple rounds (recurrence rate).
            assertThat(oracle.path("filePath").asText()).isEqualTo("skillforge-core/Foo.java");
            assertThat(oracle.path("rounds").asInt()).isEqualTo(5);
        } catch (Exception e) {
            throw new AssertionError("oracleExpected not valid JSON", e);
        }
    }

    @Test
    @DisplayName("prefers a Write content arg as fixture when it is the latest prior success")
    void harvest_priorWrite_usesContentArg() {
        LlmSpanEntity write = toolSpan("write-1", "Write",
                "{\"file_path\":\"" + ABS_PATH + "\",\"content\":\"written body\"}",
                "Successfully wrote 12 bytes", null, 100);
        LlmSpanEntity failedEdit = toolSpan("fail-1", "Edit",
                "{\"file_path\":\"" + ABS_PATH + "\",\"old_string\":\"x\",\"new_string\":\"y\"}",
                null, "old_string not found in file", 200);
        when(spanRepository.findBySessionIdOrderByStartedAtAsc(SESSION_ID))
                .thenReturn(List.of(write, failedEdit));

        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setMessagesJson("[{\"role\":\"user\",\"content\":\"do a thing\"}]");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(scenarioRepository.save(any(EvalScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<EvalScenarioEntity> result = service.harvestEditStaleCase(SESSION_ID, "fail-1");

        assertThat(result).isPresent();
        assertThat(result.get().getFixtureFiles())
                .containsEntry("skillforge-core/Foo.java", "written body");
    }

    @Test
    @DisplayName("skips (empty) when there is no prior content for the path")
    void harvest_noPriorContent_skips() {
        LlmSpanEntity failedEdit = toolSpan("fail-1", "Edit",
                "{\"file_path\":\"" + ABS_PATH + "\",\"old_string\":\"x\",\"new_string\":\"y\"}",
                null, "old_string not found in file", 200);
        when(spanRepository.findBySessionIdOrderByStartedAtAsc(SESSION_ID))
                .thenReturn(List.of(failedEdit));

        Optional<EvalScenarioEntity> result = service.harvestEditStaleCase(SESSION_ID, "fail-1");

        assertThat(result).isEmpty();
        verify(scenarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("skips when the referenced span is not a failed Edit")
    void harvest_notAFailedEdit_skips() {
        LlmSpanEntity okEdit = toolSpan("ok-1", "Edit",
                "{\"file_path\":\"" + ABS_PATH + "\"}", null, null, 100);
        when(spanRepository.findBySessionIdOrderByStartedAtAsc(SESSION_ID))
                .thenReturn(List.of(okEdit));

        Optional<EvalScenarioEntity> result = service.harvestEditStaleCase(SESSION_ID, "ok-1");

        assertThat(result).isEmpty();
        verify(scenarioRepository, never()).save(any());
    }
}
