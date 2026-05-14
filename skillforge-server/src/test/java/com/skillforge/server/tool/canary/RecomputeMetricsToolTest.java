package com.skillforge.server.tool.canary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.canary.CanaryMetricsService;
import com.skillforge.server.canary.CanaryMetricsService.RecomputeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.4 — {@link RecomputeMetricsTool} JSON-adapter
 * tests. Business logic is in {@link CanaryMetricsService}; this suite verifies
 * the tool wiring (input parsing, window clamping, output shape, error handling).
 */
@ExtendWith(MockitoExtension.class)
class RecomputeMetricsToolTest {

    @Mock private CanaryMetricsService metricsService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RecomputeMetricsTool tool;

    @BeforeEach
    void setUp() {
        tool = new RecomputeMetricsTool(metricsService, objectMapper);
    }

    @Test
    @DisplayName("tool exposes correct name + non-empty description + non-read-only flag + schema")
    void schema_andMetadata() {
        assertThat(tool.getName()).isEqualTo("RecomputeMetrics");
        assertThat(tool.getDescription()).isNotBlank().contains("auto-rollback");
        assertThat(tool.isReadOnly()).isFalse();
        assertThat(tool.getToolSchema()).isNotNull();
        assertThat(tool.getToolSchema().getName()).isEqualTo("RecomputeMetrics");
    }

    @Test
    @DisplayName("execute with no input defaults window_hours=1 and returns ok payload with all 4 fields")
    void execute_defaultsWindowHoursTo1() throws Exception {
        when(metricsService.recompute(Duration.ofHours(1)))
                .thenReturn(new RecomputeResult(3, 2, 1));

        SkillResult result = tool.execute(null, null);

        assertThat(result.isSuccess()).isTrue();
        Map<?, ?> payload = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(payload.get("ok")).isEqualTo(true);
        assertThat(payload.get("window_hours")).isEqualTo(1);
        assertThat(payload.get("active_canaries")).isEqualTo(3);
        assertThat(payload.get("snapshots_written")).isEqualTo(2);
        assertThat(payload.get("auto_rollbacks_triggered")).isEqualTo(1);
    }

    @Test
    @DisplayName("execute honours explicit window_hours and clamps lower / upper bounds")
    void execute_honoursAndClampsWindowHours() {
        when(metricsService.recompute(any(Duration.class)))
                .thenReturn(new RecomputeResult(1, 1, 0));

        // explicit 6 → passed through unchanged
        Map<String, Object> input6 = new HashMap<>();
        input6.put("window_hours", 6);
        tool.execute(input6, null);

        // 0 → clamped to MIN=1
        Map<String, Object> input0 = new HashMap<>();
        input0.put("window_hours", 0);
        tool.execute(input0, null);

        // 999 → clamped to MAX=168
        Map<String, Object> input999 = new HashMap<>();
        input999.put("window_hours", 999);
        tool.execute(input999, null);

        ArgumentCaptor<Duration> cap = ArgumentCaptor.forClass(Duration.class);
        verify(metricsService, times(3)).recompute(cap.capture());
        assertThat(cap.getAllValues()).containsExactly(
                Duration.ofHours(6),
                Duration.ofHours(1),
                Duration.ofHours(168));
    }

    @Test
    @DisplayName("execute returns error SkillResult when service throws (error in getError, not getOutput)")
    void execute_handlesServiceException() {
        when(metricsService.recompute(any(Duration.class)))
                .thenThrow(new RuntimeException("simulated metrics failure"));

        SkillResult result = tool.execute(null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("RecomputeMetrics error")
                .contains("simulated metrics failure");
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.EXECUTION);
    }
}
