package com.skillforge.server.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptReportEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptReportRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OptReportService")
class OptReportServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-22T10:00:00Z");

    @Mock private OptReportRepository reportRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private SessionService sessionService;
    @Mock private ChatService chatService;
    @Mock private UserWebSocketHandler userWebSocketHandler;

    private ObjectMapper objectMapper;
    private Clock clock;
    private OptReportService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        service = new OptReportService(reportRepository, agentRepository, sessionService,
                chatService, userWebSocketHandler, objectMapper, clock);
    }

    @Test
    @DisplayName("startReport: inserts pending row, spawns generator session, fires chatAsync, returns running entity")
    void startReport_happyPath() {
        AgentEntity target = newAgent(7L, "design-agent");
        AgentEntity generator = newAgent(100L, SystemAgentNames.REPORT_GENERATOR);
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.REPORT_GENERATOR))
                .thenReturn(Optional.of(generator));

        SessionEntity generatorSession = new SessionEntity();
        generatorSession.setId("sess-gen-1");
        when(sessionService.createSession(eq(OptReportService.SYSTEM_USER_ID), eq(100L)))
                .thenReturn(generatorSession);

        OptReportEntity result = service.startReport(7L, 7);

        assertThat(result.getId()).isNotBlank();
        assertThat(result.getAgentId()).isEqualTo(7L);
        assertThat(result.getStatus()).isEqualTo(OptReportEntity.STATUS_RUNNING);
        assertThat(result.getGeneratorSessionId()).isEqualTo("sess-gen-1");
        assertThat(result.getWindowEnd()).isEqualTo(FIXED_NOW);
        assertThat(result.getWindowStart()).isEqualTo(FIXED_NOW.minus(7, java.time.temporal.ChronoUnit.DAYS));

        // 2 saves: first pending insert, then running update.
        verify(reportRepository, times(2)).save(any(OptReportEntity.class));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService).chatAsync(eq("sess-gen-1"), promptCaptor.capture(),
                eq(OptReportService.SYSTEM_USER_ID));
        assertThat(promptCaptor.getValue()).contains("agentId=7");
        assertThat(promptCaptor.getValue()).contains("reportId=" + result.getId());
        assertThat(promptCaptor.getValue()).contains("7 天");
    }

    @Test
    @DisplayName("startReport: throws when target agent missing")
    void startReport_missingTargetAgent_throws() {
        when(agentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startReport(99L, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target agent not found");

        verify(sessionService, never()).createSession(anyLong(), anyLong());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("startReport: throws when report-generator not seeded")
    void startReport_missingGeneratorAgent_throws() {
        AgentEntity target = newAgent(7L, "design-agent");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.REPORT_GENERATOR))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startReport(7L, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("report-generator");
    }

    @Test
    @DisplayName("onReportCompleted: broadcasts WS payload with agent name + status + highlight")
    void onReportCompleted_broadcasts() {
        OptReportEntity report = new OptReportEntity();
        report.setId("rep-1");
        report.setAgentId(7L);
        report.setStatus(OptReportEntity.STATUS_COMPLETED);
        report.setUpdatedAt(FIXED_NOW);
        report.setSummaryJson("{\"summary\":\"Top issue: Bash overuse\",\"totalSessions\":12}");

        AgentEntity agent = newAgent(7L, "design-agent");
        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(agentRepository.findById(7L)).thenReturn(Optional.of(agent));

        service.onReportCompleted("rep-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(userWebSocketHandler).broadcastAll(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("type")).isEqualTo("opt_report_completed");
        assertThat(payload.get("reportId")).isEqualTo("rep-1");
        assertThat(payload.get("agentId")).isEqualTo(7L);
        assertThat(payload.get("agentName")).isEqualTo("design-agent");
        assertThat(payload.get("status")).isEqualTo("completed");
        assertThat(payload.get("summaryHighlight")).isEqualTo("Top issue: Bash overuse");
        assertThat(payload.get("completedAt")).isEqualTo(FIXED_NOW.toString());
    }

    @Test
    @DisplayName("onReportCompleted: missing report-id is a no-op (no broadcast)")
    void onReportCompleted_missingReport_noop() {
        when(reportRepository.findById("missing")).thenReturn(Optional.empty());

        service.onReportCompleted("missing");

        verify(userWebSocketHandler, never()).broadcastAll(any());
    }

    @Test
    @DisplayName("onReportCompleted: WS broadcast failure is swallowed (DB row already saved)")
    void onReportCompleted_broadcastFailure_swallowed() {
        OptReportEntity report = new OptReportEntity();
        report.setId("rep-x");
        report.setAgentId(7L);
        report.setStatus(OptReportEntity.STATUS_COMPLETED);
        when(reportRepository.findById("rep-x")).thenReturn(Optional.of(report));
        when(agentRepository.findById(7L)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("ws down"))
                .when(userWebSocketHandler).broadcastAll(any());

        // Should not propagate the exception.
        service.onReportCompleted("rep-x");

        verify(userWebSocketHandler).broadcastAll(any());
    }

    private static AgentEntity newAgent(long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        return a;
    }
}
