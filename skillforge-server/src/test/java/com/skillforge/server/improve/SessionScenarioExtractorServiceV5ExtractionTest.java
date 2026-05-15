package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.1 — tests for the LLM-driven batch
 * extraction path {@link SessionScenarioExtractorService#extractFromSessions}
 * (the singular {@code extractFromSession(SessionEntity)} is mechanical /
 * no-LLM and covered by {@link SessionScenarioExtractorServiceTest}).
 *
 * <p>Locks three behaviours:
 * <ol>
 *   <li>Happy path — LLM returns 6 business-semantic fields → entity columns filled.</li>
 *   <li>Legacy LLM response (no 6 fields) → 6 columns stay null, other fields normal
 *       (backward compat with V84 nullable columns).</li>
 *   <li>xiaomi-mimo provider unavailable → falls back to defaultProvider with log.warn.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SessionScenarioExtractorServiceV5ExtractionTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private EvalScenarioDraftRepository evalScenarioDraftRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private LlmProvider xiaomiMimoProvider;
    @Mock private LlmProvider fallbackProvider;

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

    private SessionEntity completedSession(long agentId, String id, String userMsg) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(agentId);
        s.setTitle("Test session " + id);
        s.setRuntimeStatus("completed");
        s.setCompletedAt(Instant.now());
        try {
            s.setMessagesJson(objectMapper.writeValueAsString(List.of(
                    java.util.Map.of("role", "user", "content", userMsg),
                    java.util.Map.of("role", "assistant", "content", "ok")
            )));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    @Test
    @DisplayName("extractFromSessions: happy path — LLM returns 6 V5 fields → entity columns filled")
    void extractFromSessions_v5SixFields_happyPath() {
        when(sessionRepository.findByAgentId(42L)).thenReturn(List.of(
                completedSession(42L, "s-1", "help me optimize a slow query")
        ));
        when(llmProviderFactory.getProvider("xiaomi-mimo")).thenReturn(xiaomiMimoProvider);

        String llmJson = """
                [
                  {
                    "name": "slow-query-optimization",
                    "description": "user asks for slow SQL diagnosis",
                    "task": "diagnose and tune a slow query",
                    "oracleType": "llm_judge",
                    "oracleExpected": "agent identifies root cause and proposes index/rewrite",
                    "extractionRationale": "concrete DBA task, repeatable",
                    "businessGoal": "缩短一个具体慢 SQL 的响应时间",
                    "successCriteria": "agent 给出 root cause + 至少 1 个可执行优化方案",
                    "userPersona": "DBA 老手，技术熟练，对效率敏感",
                    "userConstraints": "不能停服，不能改业务代码",
                    "failureSignals": "用户重复粘贴同一个 SQL / 直接放弃",
                    "expectedOutcome": "agent 找出索引缺失并给出 CREATE INDEX 语句"
                  }
                ]
                """;
        LlmResponse resp = new LlmResponse();
        resp.setContent(llmJson);
        when(xiaomiMimoProvider.chat(any(LlmRequest.class))).thenReturn(resp);

        int saved = service.extractFromSessions("42", null);
        assertThat(saved).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalScenarioEntity>> captor =
                (ArgumentCaptor<List<EvalScenarioEntity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(evalScenarioDraftRepository).saveAll(captor.capture());
        List<EvalScenarioEntity> entities = captor.getValue();
        assertThat(entities).hasSize(1);
        EvalScenarioEntity e = entities.get(0);
        assertThat(e.getName()).isEqualTo("slow-query-optimization");
        assertThat(e.getTask()).isEqualTo("diagnose and tune a slow query");
        assertThat(e.getBusinessGoal()).isEqualTo("缩短一个具体慢 SQL 的响应时间");
        assertThat(e.getSuccessCriteria()).contains("root cause");
        assertThat(e.getUserPersona()).contains("DBA");
        assertThat(e.getUserConstraints()).contains("不能停服");
        assertThat(e.getFailureSignals()).contains("放弃");
        assertThat(e.getExpectedOutcome()).contains("CREATE INDEX");
    }

    @Test
    @DisplayName("extractFromSessions: legacy LLM response (no 6 fields) → 6 columns null, others normal")
    void extractFromSessions_v5LegacyResponse_nullSixFields() {
        when(sessionRepository.findByAgentId(7L)).thenReturn(List.of(
                completedSession(7L, "s-2", "explain bubble sort")
        ));
        when(llmProviderFactory.getProvider("xiaomi-mimo")).thenReturn(xiaomiMimoProvider);

        String legacyJson = """
                [
                  {
                    "name": "explain-bubble-sort",
                    "description": "user wants algorithm explanation",
                    "task": "explain bubble sort with example",
                    "oracleType": "llm_judge",
                    "oracleExpected": "step-by-step trace with 5-element array",
                    "extractionRationale": "good single-turn explain task"
                  }
                ]
                """;
        LlmResponse resp = new LlmResponse();
        resp.setContent(legacyJson);
        when(xiaomiMimoProvider.chat(any(LlmRequest.class))).thenReturn(resp);

        int saved = service.extractFromSessions("7", null);
        assertThat(saved).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalScenarioEntity>> captor =
                (ArgumentCaptor<List<EvalScenarioEntity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(evalScenarioDraftRepository).saveAll(captor.capture());
        EvalScenarioEntity e = captor.getValue().get(0);
        assertThat(e.getName()).isEqualTo("explain-bubble-sort");
        assertThat(e.getTask()).isEqualTo("explain bubble sort with example");
        // Legacy fields populated as before
        assertThat(e.getOracleExpected()).contains("step-by-step");
        assertThat(e.getExtractionRationale()).contains("single-turn");
        // V5 fields stay null — backward compat (V84 columns nullable)
        assertThat(e.getBusinessGoal()).isNull();
        assertThat(e.getSuccessCriteria()).isNull();
        assertThat(e.getUserPersona()).isNull();
        assertThat(e.getUserConstraints()).isNull();
        assertThat(e.getFailureSignals()).isNull();
        assertThat(e.getExpectedOutcome()).isNull();
    }

    @Test
    @DisplayName("extractFromSessions: xiaomi-mimo unavailable → falls back to defaultProvider")
    void extractFromSessions_xiaomiMimoUnavailable_fallbackDefaultProvider() {
        when(sessionRepository.findByAgentId(9L)).thenReturn(List.of(
                completedSession(9L, "s-3", "rename the file")
        ));
        // Critical: xiaomi-mimo not registered → factory returns null → service must
        // call factory.getProvider("claude") (defaultProvider) as the fallback path.
        when(llmProviderFactory.getProvider("xiaomi-mimo")).thenReturn(null);
        when(llmProviderFactory.getProvider("claude")).thenReturn(fallbackProvider);

        LlmResponse resp = new LlmResponse();
        resp.setContent("""
                [
                  {
                    "name": "rename-file",
                    "description": "user wants file rename",
                    "task": "rename foo.txt to bar.txt",
                    "oracleType": "llm_judge",
                    "oracleExpected": "agent uses Bash mv",
                    "extractionRationale": "trivial regression case"
                  }
                ]
                """);
        when(fallbackProvider.chat(any(LlmRequest.class))).thenReturn(resp);

        int saved = service.extractFromSessions("9", null);
        assertThat(saved).isEqualTo(1);

        // Verify the fallback provider was the one used (not xiaomi-mimo), and the
        // model field was NOT pinned to mimo-v2.5-pro since we're on the fallback.
        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(fallbackProvider).chat(reqCaptor.capture());
        org.mockito.Mockito.verify(xiaomiMimoProvider, org.mockito.Mockito.never()).chat(any());
        LlmRequest sent = reqCaptor.getValue();
        // Default provider should not have mimo-v2.5-pro forced on its request —
        // fallback path leaves model unset so the provider routes by its own default.
        assertThat(sent.getModel()).isNull();
    }
}
