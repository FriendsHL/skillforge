package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.attribution.AttributionDispatcherService;
import com.skillforge.server.attribution.AttributionDispatcherService.DispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DispatchAttributionPatternsTool}. Verifies:
 * <ul>
 *   <li>default {@code max_dispatch} = 5 when input omits the param;</li>
 *   <li>input clamping to [{@link DispatchAttributionPatternsTool#MIN_MAX_DISPATCH},
 *       {@link DispatchAttributionPatternsTool#MAX_MAX_DISPATCH}];</li>
 *   <li>JSON output shape (5 dispatch-result fields + {@code ok});</li>
 *   <li>W4 fix: service-throws-exception → {@link SkillResult#error(String)} with
 *       prefix {@code "DispatchAttributionPatterns: "} so the dispatcher
 *       agent's LLM final-summary step still has structured output to react to
 *       instead of dying mid-loop;</li>
 *   <li>{@code max_dispatch=0} → clamped to {@link
 *       DispatchAttributionPatternsTool#MIN_MAX_DISPATCH} (=1), so the
 *       service is never called with 0 (which would otherwise fall back to
 *       service default 5 — surprising behavior to the caller).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DispatchAttributionPatternsToolTest {

    @Mock private AttributionDispatcherService dispatcherService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DispatchAttributionPatternsTool tool;

    @BeforeEach
    void setUp() {
        tool = new DispatchAttributionPatternsTool(dispatcherService, objectMapper);
    }

    @Test
    @DisplayName("default: empty input → service called with DEFAULT_MAX_DISPATCH=5 + JSON output carries 5 fields + ok=true")
    void execute_defaultMaxDispatch_callsServiceWithFive() throws Exception {
        when(dispatcherService.dispatchPendingPatterns(anyInt()))
                .thenReturn(new DispatchResult(12, 3, 4, 3, 2));

        SkillResult result = tool.execute(Map.of(), null);

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(dispatcherService).dispatchPendingPatterns(cap.capture());
        assertThat(cap.getValue()).isEqualTo(DispatchAttributionPatternsTool.DEFAULT_MAX_DISPATCH);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = objectMapper.readValue(
                result.getOutput(), new TypeReference<Map<String, Object>>() {});
        assertThat(payload).containsEntry("ok", true);
        assertThat(payload).containsEntry("candidates_scanned", 12);
        assertThat(payload).containsEntry("dispatched", 3);
        assertThat(payload).containsEntry("skippedSurface", 4);
        assertThat(payload).containsEntry("skippedCooldown", 3);
        assertThat(payload).containsEntry("skippedActive", 2);
    }

    @Test
    @DisplayName("clamping: max_dispatch=999 → clamped to MAX_MAX_DISPATCH=20 before service call")
    void execute_overlargeMaxDispatch_clampsToMax() {
        when(dispatcherService.dispatchPendingPatterns(anyInt()))
                .thenReturn(new DispatchResult(0, 0, 0, 0, 0));

        tool.execute(Map.of("max_dispatch", 999), null);

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(dispatcherService).dispatchPendingPatterns(cap.capture());
        assertThat(cap.getValue()).isEqualTo(DispatchAttributionPatternsTool.MAX_MAX_DISPATCH);
    }

    @Test
    @DisplayName("output shape: all 6 keys present even when result is all zeros")
    void execute_zeroResult_stillCarriesAllFields() throws Exception {
        when(dispatcherService.dispatchPendingPatterns(anyInt()))
                .thenReturn(new DispatchResult(0, 0, 0, 0, 0));

        SkillResult result = tool.execute(Map.of("max_dispatch", 7), null);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = objectMapper.readValue(
                result.getOutput(), new TypeReference<Map<String, Object>>() {});
        assertThat(payload.keySet()).containsExactlyInAnyOrder(
                "ok", "candidates_scanned", "dispatched",
                "skippedSurface", "skippedCooldown", "skippedActive");
    }

    @Test
    @DisplayName("W4 fix: service throws RuntimeException → SkillResult.error with 'DispatchAttributionPatterns:' prefix")
    void execute_serviceThrows_returnsSkillResultError() {
        when(dispatcherService.dispatchPendingPatterns(anyInt()))
                .thenThrow(new RuntimeException("db down"));

        SkillResult result = tool.execute(new HashMap<>(), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutput()).isNull();
        assertThat(result.getError()).startsWith("DispatchAttributionPatterns:");
        assertThat(result.getError()).contains("db down");
    }

    @Test
    @DisplayName("clamping floor: max_dispatch=0 → clamped to MIN_MAX_DISPATCH=1 (NOT silently rewritten to service default 5)")
    void execute_zeroMaxDispatch_clampsToOne() {
        when(dispatcherService.dispatchPendingPatterns(anyInt()))
                .thenReturn(new DispatchResult(0, 0, 0, 0, 0));

        tool.execute(Map.of("max_dispatch", 0), null);

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(dispatcherService).dispatchPendingPatterns(cap.capture());
        // Caller asked "small" — we honor with the smallest legal cap (1), not
        // the service's "0 → fall back to default 5" branch. Predictability >
        // service legacy behavior.
        assertThat(cap.getValue()).isEqualTo(DispatchAttributionPatternsTool.MIN_MAX_DISPATCH);
    }
}
