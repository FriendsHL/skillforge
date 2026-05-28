package com.skillforge.server.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OptReportService (post-V124 back-compat surface)")
class OptReportServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-22T10:00:00Z");

    @Mock private FlywheelRunRepository runRepository;
    @Mock private FlywheelRunService flywheelRunService;
    @Mock private AgentRepository agentRepository;
    @Mock private SessionService sessionService;
    @Mock private ChatService chatService;
    @Mock private UserWebSocketHandler userWebSocketHandler;

    private ObjectMapper objectMapper;
    private OptReportService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new OptReportService(runRepository, flywheelRunService, agentRepository, sessionService,
                chatService, userWebSocketHandler, objectMapper);
    }

    @Test
    @DisplayName("startReport: delegates to FlywheelRunService.startRun, spawns generator session, fires chatAsync, returns running entity")
    void startReport_happyPath() {
        AgentEntity target = newAgent(7L, "design-agent");
        AgentEntity generator = newAgent(100L, SystemAgentNames.REPORT_GENERATOR);
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.REPORT_GENERATOR))
                .thenReturn(Optional.of(generator));

        // FlywheelRunService.startRun returns the pending entity (with a fresh id).
        FlywheelRunEntity pending = newRun("rep-uuid", 7L, FlywheelRunEntity.STATUS_PENDING);
        pending.setLoopKind(FlywheelRunEntity.LOOP_KIND_OPT_REPORT);
        pending.setTriggerSource(FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL);
        when(flywheelRunService.startRun(
                eq(FlywheelRunEntity.LOOP_KIND_OPT_REPORT),
                eq(FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL),
                any(),
                eq(7L),
                eq(7))).thenReturn(pending);

        SessionEntity generatorSession = new SessionEntity();
        generatorSession.setId("sess-gen-1");
        when(sessionService.createSession(eq(OptReportService.SYSTEM_USER_ID), eq(100L)))
                .thenReturn(generatorSession);

        // attachGeneratorSession returns the running entity.
        FlywheelRunEntity running = newRun("rep-uuid", 7L, FlywheelRunEntity.STATUS_RUNNING);
        running.setGeneratorSessionId("sess-gen-1");
        when(flywheelRunService.attachGeneratorSession(eq("rep-uuid"), eq("sess-gen-1")))
                .thenReturn(running);

        FlywheelRunEntity result = service.startReport(7L, 7);

        assertThat(result.getId()).isEqualTo("rep-uuid");
        assertThat(result.getAgentId()).isEqualTo(7L);
        assertThat(result.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_RUNNING);
        assertThat(result.getGeneratorSessionId()).isEqualTo("sess-gen-1");

        // input_json carrying agentId + windowDays is passed to FlywheelRunService.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(flywheelRunService).startRun(anyString(), anyString(), inputCaptor.capture(), anyLong(), anyInt());
        Map<String, Object> input = inputCaptor.getValue();
        assertThat(input).containsEntry("agentId", 7L).containsEntry("windowDays", 7);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService).chatAsync(eq("sess-gen-1"), promptCaptor.capture(),
                eq(OptReportService.SYSTEM_USER_ID));
        assertThat(promptCaptor.getValue()).contains("agentId=7");
        assertThat(promptCaptor.getValue()).contains("reportId=rep-uuid");
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
        verify(flywheelRunService, never()).startRun(anyString(), anyString(), any(), anyLong(), anyInt());
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
        FlywheelRunEntity report = newRun("rep-1", 7L, FlywheelRunEntity.STATUS_COMPLETED);
        report.setUpdatedAt(FIXED_NOW);
        report.setSummaryJson("{\"summary\":\"Top issue: Bash overuse\",\"totalSessions\":12}");

        AgentEntity agent = newAgent(7L, "design-agent");
        when(runRepository.findById("rep-1")).thenReturn(Optional.of(report));
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
        when(runRepository.findById("missing")).thenReturn(Optional.empty());

        service.onReportCompleted("missing");

        verify(userWebSocketHandler, never()).broadcastAll(any());
    }

    @Test
    @DisplayName("onReportCompleted: WS broadcast failure is swallowed (DB row already saved)")
    void onReportCompleted_broadcastFailure_swallowed() {
        FlywheelRunEntity report = newRun("rep-x", 7L, FlywheelRunEntity.STATUS_COMPLETED);
        when(runRepository.findById("rep-x")).thenReturn(Optional.of(report));
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

    private static FlywheelRunEntity newRun(String id, long agentId, String status) {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId(id);
        r.setAgentId(agentId);
        r.setStatus(status);
        r.setLoopKind(FlywheelRunEntity.LOOP_KIND_OPT_REPORT);
        r.setTriggerSource(FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL);
        return r;
    }
}
