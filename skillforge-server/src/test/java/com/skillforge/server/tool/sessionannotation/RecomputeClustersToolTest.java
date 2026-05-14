package com.skillforge.server.tool.sessionannotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.sessionannotation.SessionPatternClusterService;
import com.skillforge.server.sessionannotation.SessionPatternClusterService.RecomputeResult;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring-level tests for {@link RecomputeClustersTool}. The cluster service
 * tests own algorithm behavior; these tests lock JSON input parsing + output
 * shape + default / clamp arithmetic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecomputeClustersTool")
class RecomputeClustersToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private SessionPatternClusterService clusterService;

    @Test
    @DisplayName("execute uses default window=7d when window_days omitted")
    void execute_callsServiceWithDefaultWindow() throws Exception {
        when(clusterService.recompute(any(Duration.class))).thenReturn(new RecomputeResult(2, 5));
        RecomputeClustersTool tool = new RecomputeClustersTool(clusterService, objectMapper);

        SkillResult result = tool.execute(Map.of(), new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("window_days").asInt()).isEqualTo(7);
        assertThat(root.path("patterns_upserted").asInt()).isEqualTo(2);
        assertThat(root.path("members_added").asInt()).isEqualTo(5);
        assertThat(root.path("ok").asBoolean()).isTrue();

        ArgumentCaptor<Duration> dur = ArgumentCaptor.forClass(Duration.class);
        verify(clusterService).recompute(dur.capture());
        assertThat(dur.getValue()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("execute respects supplied window_days param")
    void execute_respectsWindowDaysParameter() throws Exception {
        when(clusterService.recompute(any(Duration.class))).thenReturn(new RecomputeResult(0, 0));
        RecomputeClustersTool tool = new RecomputeClustersTool(clusterService, objectMapper);

        Map<String, Object> input = new HashMap<>();
        input.put("window_days", 14);

        SkillResult result = tool.execute(input, new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("window_days").asInt()).isEqualTo(14);

        ArgumentCaptor<Duration> dur = ArgumentCaptor.forClass(Duration.class);
        verify(clusterService).recompute(dur.capture());
        assertThat(dur.getValue()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    @DisplayName("execute clamps window_days to [1, 30]")
    void execute_clampsToMaxWindow() throws Exception {
        when(clusterService.recompute(any(Duration.class))).thenReturn(new RecomputeResult(0, 0));
        RecomputeClustersTool tool = new RecomputeClustersTool(clusterService, objectMapper);

        // > max → clamped to 30
        Map<String, Object> over = new HashMap<>();
        over.put("window_days", 365);
        SkillResult resOver = tool.execute(over, new SkillContext(null, null, 0L));
        JsonNode rootOver = objectMapper.readTree(resOver.getOutput());
        assertThat(rootOver.path("window_days").asInt()).isEqualTo(30);

        // < min → clamped to 1
        Map<String, Object> under = new HashMap<>();
        under.put("window_days", 0);
        SkillResult resUnder = tool.execute(under, new SkillContext(null, null, 0L));
        JsonNode rootUnder = objectMapper.readTree(resUnder.getOutput());
        assertThat(rootUnder.path("window_days").asInt()).isEqualTo(1);

        // negative also clamped to 1
        Map<String, Object> neg = new HashMap<>();
        neg.put("window_days", -5);
        SkillResult resNeg = tool.execute(neg, new SkillContext(null, null, 0L));
        JsonNode rootNeg = objectMapper.readTree(resNeg.getOutput());
        assertThat(rootNeg.path("window_days").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("execute returns the §4.3 JSON shape")
    void execute_returnsJsonShape() throws Exception {
        when(clusterService.recompute(any(Duration.class))).thenReturn(new RecomputeResult(3, 11));
        RecomputeClustersTool tool = new RecomputeClustersTool(clusterService, objectMapper);

        SkillResult result = tool.execute(null, new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.has("ok")).isTrue();
        assertThat(root.has("window_days")).isTrue();
        assertThat(root.has("patterns_upserted")).isTrue();
        assertThat(root.has("members_added")).isTrue();
        assertThat(root.path("ok").asBoolean()).isTrue();
        assertThat(root.path("patterns_upserted").asInt()).isEqualTo(3);
        assertThat(root.path("members_added").asInt()).isEqualTo(11);
    }
}
