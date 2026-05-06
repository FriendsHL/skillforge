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
    @Mock private SessionMessageRepository sessionMessageRepository;
    @Mock private EvalScenarioDraftRepository evalScenarioDraftRepository;

    private TraceScenarioImportService service;

    @BeforeEach
    void setUp() {
        service = new TraceScenarioImportService(
                traceRepository,
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
}
