package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TraceScenarioImportService")
class TraceScenarioImportServiceTest {

    @Mock private LlmTraceRepository traceRepository;
    @Mock private com.skillforge.observability.repository.LlmSpanRepository spanRepository;
    @Mock private SessionMessageRepository sessionMessageRepository;
    @Mock private EvalScenarioDraftRepository evalScenarioDraftRepository;

    private TraceScenarioImportService service;

    @BeforeEach
    void setUp() {
        service = new TraceScenarioImportService(
                traceRepository,
                spanRepository,
                sessionMessageRepository,
                evalScenarioDraftRepository,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("importFromTrace maps root trace messages into an eval scenario")
    void importFromTrace_mapsTraceIntoScenario() {
        LlmTraceEntity rootTrace = new LlmTraceEntity();
        rootTrace.setTraceId("trace-root");
        rootTrace.setRootTraceId("trace-root");
        rootTrace.setSessionId("sess-1");
        rootTrace.setAgentId(7L);
        rootTrace.setStartedAt(Instant.parse("2026-05-06T08:00:00Z"));

        LlmTraceEntity childTrace = new LlmTraceEntity();
        childTrace.setTraceId("trace-child");
        childTrace.setRootTraceId("trace-root");
        childTrace.setSessionId("sess-2");
        childTrace.setAgentId(7L);
        childTrace.setStartedAt(Instant.parse("2026-05-06T08:00:05Z"));

        SessionMessageEntity userMessage = new SessionMessageEntity();
        userMessage.setContentJson("[{\"type\":\"text\",\"text\":\"Investigate the flaky regression\"}]");
        SessionMessageEntity assistantMessage = new SessionMessageEntity();
        assistantMessage.setContentJson("[{\"type\":\"text\",\"text\":\"Regression isolated to the auth cache.\"}]");

        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc("trace-root"))
                .thenReturn(List.of(rootTrace, childTrace));
        when(sessionMessageRepository.findTopByTraceIdAndRoleOrderBySeqNoAsc("trace-root", "user"))
                .thenReturn(Optional.of(userMessage));
        when(sessionMessageRepository.findTopByTraceIdAndRoleOrderBySeqNoDesc("trace-root", "assistant"))
                .thenReturn(Optional.of(assistantMessage));
        when(evalScenarioDraftRepository.save(any(EvalScenarioEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EvalScenarioEntity saved = service.importFromTrace(Map.of("rootTraceId", "trace-root"));

        ArgumentCaptor<EvalScenarioEntity> captor = ArgumentCaptor.forClass(EvalScenarioEntity.class);
        verify(evalScenarioDraftRepository).save(captor.capture());
        EvalScenarioEntity persisted = captor.getValue();

        assertThat(saved).isSameAs(persisted);
        assertThat(persisted.getAgentId()).isEqualTo("7");
        assertThat(persisted.getTask()).isEqualTo("Investigate the flaky regression");
        assertThat(persisted.getOracleExpected()).isEqualTo("Regression isolated to the auth cache.");
        assertThat(persisted.getSourceSessionId()).isEqualTo("sess-1");
        assertThat(persisted.getCategory()).isEqualTo("trace_import");
        assertThat(persisted.getSplit()).isEqualTo("held_out");
        assertThat(persisted.getStatus()).isEqualTo("active");
        assertThat(persisted.getVersion()).isEqualTo(1);
        assertThat(persisted.getExtractionRationale()).isEqualTo("Imported from trace trace-root");
        assertThat(persisted.getName()).startsWith("Investigate the flaky regression");
    }

    @Test
    @DisplayName("suggestImportCandidates returns traces with error, tool failure, and high-token reasons")
    void suggestImportCandidates_returnsReasonedCandidates() {
        LlmTraceEntity errorTrace = trace("trace-error", "trace-error", "sess-error", 7L, "error", 800, 400, 1);
        errorTrace.setError("Agent loop failed");

        LlmTraceEntity highTokenTrace = trace("trace-high", "trace-high", "sess-high", 7L, "ok", 1800, 900, 0);

        com.skillforge.observability.entity.LlmSpanEntity failedTool = span("span-tool", "trace-error", "tool", "error");
        failedTool.setError("Tool failed");

        when(traceRepository.findByOriginOrderByStartedAtDesc("production"))
                .thenReturn(List.of(errorTrace, highTokenTrace));
        when(sessionMessageRepository.findFirstUserMessageContentByTraceIds(List.of("trace-error", "trace-high")))
                .thenReturn(List.of(
                        new Object[]{"trace-error", "[{\"type\":\"text\",\"text\":\"Debug the deployment\"}]"},
                        new Object[]{"trace-high", "[{\"type\":\"text\",\"text\":\"Analyze a long session\"}]"}
                ));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(List.of("trace-error", "trace-high")))
                .thenReturn(List.of(failedTool));

        List<TraceScenarioImportService.TraceImportCandidate> candidates =
                service.suggestImportCandidates(Map.of("minTokens", 2000, "limit", 10));

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).reasonCodes()).contains("agent_error", "tool_failure");
        assertThat(candidates.get(0).preview()).isEqualTo("Debug the deployment");
        assertThat(candidates.get(1).reasonCodes()).contains("high_token");
    }

    // ────────────────────────────────────────────────────────────────────────
    // detectReasons unit tests (Phase 1.2 — pure-function helper extracted
    // from suggestImportCandidates; the integration-level coverage above
    // (suggestImportCandidates_returnsReasonedCandidates) locks the wiring,
    // these tests lock the per-reason boolean behavior at the helper level.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detectReasons returns agent_error when trace status is error")
    void detectReasons_returnsAgentError_whenTraceStatusIsError() {
        LlmTraceEntity t = trace("t-1", "t-1", "s-1", 1L, "error", 0, 0, 0);

        List<String> reasons = TraceScenarioImportService.detectReasons(t, List.of(), 0, 0, 0, 2000);

        assertThat(reasons).containsExactly("agent_error");
    }

    @Test
    @DisplayName("detectReasons returns tool_failure when any tool span has error")
    void detectReasons_returnsToolFailure_whenAnyToolSpanHasError() {
        LlmTraceEntity t = trace("t-2", "t-2", "s-2", 1L, "ok", 0, 0, 0);
        com.skillforge.observability.entity.LlmSpanEntity sp = span("sp-1", "t-2", "tool", "ok");
        sp.setError("boom");

        List<String> reasons = TraceScenarioImportService.detectReasons(
                t, List.of(sp), 0, 1, 0, 2000);

        assertThat(reasons).containsExactly("tool_failure", "has_tool_calls");
    }

    @Test
    @DisplayName("detectReasons returns high_token when tokens above minTokens")
    void detectReasons_returnsHighToken_whenTokensAboveMin() {
        LlmTraceEntity t = trace("t-3", "t-3", "s-3", 1L, "ok", 1500, 600, 0);

        List<String> reasons = TraceScenarioImportService.detectReasons(t, List.of(), 2100, 0, 0, 2000);

        assertThat(reasons).containsExactly("high_token");
    }

    @Test
    @DisplayName("detectReasons returns multi_turn when llmCalls >= 2")
    void detectReasons_returnsMultiTurn_whenLlmCallsGte2() {
        LlmTraceEntity t = trace("t-4", "t-4", "s-4", 1L, "ok", 0, 0, 0);

        List<String> reasons = TraceScenarioImportService.detectReasons(t, List.of(), 0, 0, 3, 2000);

        assertThat(reasons).containsExactly("multi_turn");
    }

    @Test
    @DisplayName("detectReasons returns empty list when all conditions fail")
    void detectReasons_emptyList_whenAllConditionsFail() {
        LlmTraceEntity t = trace("t-5", "t-5", "s-5", 1L, "ok", 0, 0, 0);

        List<String> reasons = TraceScenarioImportService.detectReasons(t, List.of(), 0, 0, 0, 2000);

        assertThat(reasons).isEmpty();
    }

    @Test
    @DisplayName("detectReasons combines agent_error + tool_failure + has_tool_calls when both conditions hit")
    void detectReasons_combination_returnsAllApplicableReasons() {
        LlmTraceEntity t = trace("t-6", "t-6", "s-6", 1L, "error", 0, 0, 1);
        t.setError("Loop failed");
        com.skillforge.observability.entity.LlmSpanEntity toolFail = span("sp-tool", "t-6", "tool", "error");
        toolFail.setError("Tool died");
        com.skillforge.observability.entity.LlmSpanEntity llmErr = span("sp-llm", "t-6", "llm", "error");
        llmErr.setError("Provider 500");

        List<String> reasons = TraceScenarioImportService.detectReasons(
                t, List.of(toolFail, llmErr), 2500, 1, 2, 2000);

        // Deterministic ordering: agent_error → tool_failure → span_error → high_token → multi_turn → has_tool_calls.
        assertThat(reasons).containsExactly(
                "agent_error", "tool_failure", "span_error", "high_token", "multi_turn", "has_tool_calls");
    }

    @Test
    @DisplayName("createDraftsFromTraces imports selected root traces as reviewable drafts")
    void createDraftsFromTraces_createsDrafts() {
        LlmTraceEntity rootTrace = trace("trace-root", "trace-root", "sess-1", 7L, "error", 100, 50, 1);
        SessionMessageEntity userMessage = message("[{\"type\":\"text\",\"text\":\"Investigate tool failure\"}]");

        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc("trace-root"))
                .thenReturn(List.of(rootTrace));
        when(sessionMessageRepository.findTopByTraceIdAndRoleOrderBySeqNoAsc("trace-root", "user"))
                .thenReturn(Optional.of(userMessage));
        when(sessionMessageRepository.findTopByTraceIdAndRoleOrderBySeqNoDesc("trace-root", "assistant"))
                .thenReturn(Optional.empty());
        when(evalScenarioDraftRepository.save(any(EvalScenarioEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<EvalScenarioEntity> drafts = service.createDraftsFromTraces(Map.of("rootTraceIds", List.of("trace-root")));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).getStatus()).isEqualTo("draft");
        assertThat(drafts.get(0).getReviewedAt()).isNull();
        assertThat(drafts.get(0).getExtractionRationale()).contains("Candidate imported from trace trace-root");
    }

    private static LlmTraceEntity trace(String traceId, String rootTraceId, String sessionId, Long agentId,
                                        String status, int inputTokens, int outputTokens, int toolCalls) {
        LlmTraceEntity trace = new LlmTraceEntity();
        trace.setTraceId(traceId);
        trace.setRootTraceId(rootTraceId);
        trace.setSessionId(sessionId);
        trace.setAgentId(agentId);
        trace.setAgentName("Debug Agent");
        trace.setStatus(status);
        trace.setStartedAt(Instant.parse("2026-05-06T08:00:00Z"));
        trace.setTotalInputTokens(inputTokens);
        trace.setTotalOutputTokens(outputTokens);
        trace.setToolCallCount(toolCalls);
        return trace;
    }

    private static com.skillforge.observability.entity.LlmSpanEntity span(String spanId, String traceId,
                                                                          String kind, String finishReason) {
        com.skillforge.observability.entity.LlmSpanEntity span = new com.skillforge.observability.entity.LlmSpanEntity();
        span.setSpanId(spanId);
        span.setTraceId(traceId);
        span.setSessionId("sess-1");
        span.setKind(kind);
        span.setFinishReason(finishReason);
        span.setStartedAt(Instant.parse("2026-05-06T08:00:01Z"));
        return span;
    }

    private static SessionMessageEntity message(String contentJson) {
        SessionMessageEntity message = new SessionMessageEntity();
        message.setContentJson(contentJson);
        return message;
    }
}
