package com.skillforge.server.flywheel.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FlywheelRunService")
class FlywheelRunServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-28T10:00:00Z");

    @Mock private FlywheelRunRepository runRepository;
    @Mock private FlywheelRunStepRepository stepRepository;
    @Mock private UserWebSocketHandler userWebSocketHandler;
    @Mock private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    private FlywheelRunService service;

    @BeforeEach
    void setUp() {
        // r1 W2 fix (java.md footgun #1): register JavaTimeModule so future
        // inputJson payloads carrying Instant/LocalDateTime serialize correctly.
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        service = new FlywheelRunService(runRepository, stepRepository, userWebSocketHandler,
                objectMapper, clock, applicationEventPublisher);
    }

    @Test
    @DisplayName("startRun inserts a pending row with computed window + serialized inputJson")
    void startRun_insertsPendingRow() {
        FlywheelRunEntity run = service.startRun(
                FlywheelRunEntity.LOOP_KIND_OPT_REPORT,
                FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL,
                Map.of("agentId", 7L, "windowDays", 7),
                7L,
                7);

        assertThat(run.getId()).isNotBlank();
        assertThat(run.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_PENDING);
        assertThat(run.getLoopKind()).isEqualTo("opt_report");
        assertThat(run.getTriggerSource()).isEqualTo("user_manual");
        assertThat(run.getWindowEnd()).isEqualTo(FIXED_NOW);
        assertThat(run.getInputJson()).contains("\"agentId\":7").contains("\"windowDays\":7");

        ArgumentCaptor<FlywheelRunEntity> captor = ArgumentCaptor.forClass(FlywheelRunEntity.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(run.getId());
    }

    @Test
    @DisplayName("startRun rejects illegal arg combos (windowDays<1, blank loopKind/triggerSource)")
    void startRun_validation() {
        assertThatThrownBy(() -> service.startRun(null, "cron", Map.of(), 7L, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startRun("opt_report", "", Map.of(), 7L, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startRun("opt_report", "cron", Map.of(), 0L, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startRun("opt_report", "cron", Map.of(), 7L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("attachGeneratorSession transitions pending → running + WS broadcasts flywheel_run_status_changed")
    void attachGeneratorSession_transitionsAndBroadcasts() {
        FlywheelRunEntity stored = pendingRun("run-1");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(stored));

        FlywheelRunEntity updated = service.attachGeneratorSession("run-1", "sess-99");

        assertThat(updated.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_RUNNING);
        assertThat(updated.getGeneratorSessionId()).isEqualTo("sess-99");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(userWebSocketHandler).broadcastAll(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("type")).isEqualTo("flywheel_run_status_changed");
        assertThat(payload.get("runId")).isEqualTo("run-1");
        assertThat(payload.get("oldStatus")).isEqualTo("pending");
        assertThat(payload.get("newStatus")).isEqualTo("running");
    }

    @Test
    @DisplayName("attachGeneratorSession rejects non-pending row (r1 W1 guard)")
    void attachGeneratorSession_nonPending_throws() {
        FlywheelRunEntity stored = pendingRun("run-attach-2");
        stored.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        when(runRepository.findById("run-attach-2")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.attachGeneratorSession("run-attach-2", "sess-z"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status=pending");
    }

    @Test
    @DisplayName("markCompleted persists content + summary, transitions to completed, fires WS event")
    void markCompleted_writesAndBroadcasts() {
        FlywheelRunEntity stored = pendingRun("run-2");
        stored.setStatus(FlywheelRunEntity.STATUS_RUNNING);
        when(runRepository.findById("run-2")).thenReturn(Optional.of(stored));

        FlywheelRunEntity result = service.markCompleted("run-2", "# md", "{\"x\":1}");

        assertThat(result.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_COMPLETED);
        assertThat(result.getContentMd()).isEqualTo("# md");
        assertThat(result.getSummaryJson()).isEqualTo("{\"x\":1}");

        verify(runRepository).save(result);
        verify(userWebSocketHandler).broadcastAll(any());
    }

    @Test
    @DisplayName("markCompleted rejects already-completed rows (state machine guard)")
    void markCompleted_alreadyCompleted_rejects() {
        FlywheelRunEntity stored = pendingRun("run-3");
        stored.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        when(runRepository.findById("run-3")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.markCompleted("run-3", "md", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not writable");
    }

    @Test
    @DisplayName("markError sets reason + status, fires WS event with errorReason")
    void markError_setsReason() {
        FlywheelRunEntity stored = pendingRun("run-4");
        stored.setStatus(FlywheelRunEntity.STATUS_RUNNING);
        when(runRepository.findById("run-4")).thenReturn(Optional.of(stored));

        FlywheelRunEntity result = service.markError("run-4", "boom");

        assertThat(result.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_ERROR);
        assertThat(result.getErrorReason()).isEqualTo("boom");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(userWebSocketHandler).broadcastAll(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("errorReason", "boom");
    }

    @Test
    @DisplayName("transitionStatus rejects disallowed transitions (completed → running)")
    void transitionStatus_disallowed_throws() {
        FlywheelRunEntity stored = pendingRun("run-5");
        stored.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        when(runRepository.findById("run-5")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transitionStatus("run-5", FlywheelRunEntity.STATUS_RUNNING, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Disallowed transition");
    }

    @Test
    @DisplayName("WS broadcast failure is swallowed (DB write still observable)")
    void broadcast_swallowsFailure() {
        FlywheelRunEntity stored = pendingRun("run-6");
        stored.setStatus(FlywheelRunEntity.STATUS_RUNNING);
        when(runRepository.findById("run-6")).thenReturn(Optional.of(stored));
        org.mockito.Mockito.doThrow(new RuntimeException("ws down"))
                .when(userWebSocketHandler).broadcastAll(any());

        // Should not propagate.
        FlywheelRunEntity result = service.markCompleted("run-6", "md", null);
        assertThat(result.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_COMPLETED);
    }

    private static FlywheelRunEntity pendingRun(String id) {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId(id);
        r.setAgentId(7L);
        r.setStatus(FlywheelRunEntity.STATUS_PENDING);
        r.setLoopKind(FlywheelRunEntity.LOOP_KIND_OPT_REPORT);
        r.setTriggerSource(FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL);
        return r;
    }
}
