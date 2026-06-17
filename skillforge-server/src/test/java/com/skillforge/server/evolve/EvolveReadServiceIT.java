package com.skillforge.server.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.evolve.dto.EvolveIterationDto;
import com.skillforge.server.evolve.dto.EvolveRunDetailDto;
import com.skillforge.server.evolve.dto.EvolveRunSummaryDto;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — roundtrip integration test:
 * write an evolve run + 2 {@code evolve_iteration} steps via
 * {@link FlywheelRunService#appendEvolveIterationStep}, then read them back via
 * {@link EvolveReadService} and assert the correct field values and ordering.
 *
 * <p>Cases covered:
 * <ol>
 *   <li>List endpoint envelope shape + {@code iterationCount}</li>
 *   <li>{@code finalDelta} = last kept iteration's delta (null when no kept step)</li>
 *   <li>Detail endpoint: iterations parsed from step_output_json, ordered by step_index</li>
 *   <li>Detail endpoint: 404 (empty Optional) for non-evolve run</li>
 *   <li>Detail endpoint: 404 (empty Optional) for unknown run id</li>
 * </ol>
 */
@DisplayName("EvolveReadService roundtrip integration")
class EvolveReadServiceIT extends AbstractPostgresIT {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-31T10:00:00Z");

    @Autowired private FlywheelRunRepository runRepository;
    @Autowired private FlywheelRunStepRepository stepRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private SkillDraftRepository skillDraftRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private FlywheelRunService flywheelRunService;
    private EvolveReadService evolveReadService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM t_flywheel_run_step");
        jdbcTemplate.update("DELETE FROM t_flywheel_run");

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        flywheelRunService = new FlywheelRunService(
                runRepository, stepRepository,
                mock(UserWebSocketHandler.class),
                objectMapper,
                Clock.fixed(FIXED_NOW, ZoneId.of("UTC")));

        evolveReadService = new EvolveReadService(
                runRepository, stepRepository, agentRepository, skillDraftRepository, objectMapper);
    }

    // ─── case 1 + 2: list endpoint ─────────────────────────────────────────

    @Test
    @DisplayName("list: iterationCount and finalDelta derived from written steps")
    void list_iterationCountAndFinalDelta_derivedFromSteps() {
        String runId = createEvolveRun(42L);

        // Write 2 iterations: iter1 NOT kept, iter2 kept with delta=3.5
        appendIteration(runId, 1, "prompt", "Change A", "cand-1",
                70.0, 71.0, 1.0, false, null);
        appendIteration(runId, 2, "prompt", "Change B", "cand-2",
                71.0, 74.5, 3.5, true, "ab-xyz");

        List<EvolveRunSummaryDto> items = evolveReadService.listRunsForAgent(42L, 10);

        assertThat(items).hasSize(1);
        EvolveRunSummaryDto summary = items.get(0);
        assertThat(summary.evolveRunId()).isEqualTo(runId);
        assertThat(summary.iterationCount()).isEqualTo(2);
        // finalDelta = last KEPT iteration's delta = 3.5
        assertThat(summary.finalDelta()).isEqualTo(3.5);
    }

    @Test
    @DisplayName("list: finalDelta is null when no iteration was kept")
    void list_finalDelta_nullWhenNoneKept() {
        String runId = createEvolveRun(43L);
        appendIteration(runId, 1, "skill", "Attempt 1", "cand-a",
                60.0, 59.0, -1.0, false, null);

        List<EvolveRunSummaryDto> items = evolveReadService.listRunsForAgent(43L, 10);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).iterationCount()).isEqualTo(1);
        assertThat(items.get(0).finalDelta()).isNull();
    }

    @Test
    @DisplayName("list: limit caps the returned items count")
    void list_limit_caps() {
        String run1 = createEvolveRun(44L);
        String run2 = createEvolveRun(44L);

        // Both created; only 1 should come back with limit=1
        List<EvolveRunSummaryDto> items = evolveReadService.listRunsForAgent(44L, 1);
        assertThat(items).hasSize(1);
    }

    // ─── case 3: detail endpoint ────────────────────────────────────────────

    @Test
    @DisplayName("detail: iterations returned in step_index (iteration) order with correct fields")
    void detail_iterationsOrderedByStepIndex_withCorrectFields() {
        String runId = createEvolveRun(45L);

        // Insert iteration 2 first (to test ordering is by step_index, NOT insert order)
        appendIteration(runId, 2, "skill", "Second change", "cand-b",
                74.9, 73.1, -1.8, false, null);
        appendIteration(runId, 1, "prompt", "First change", "cand-a",
                72.5, 74.9, 2.4, true, "ab-1");

        Optional<EvolveRunDetailDto> detailOpt = evolveReadService.getRunDetail(runId);
        assertThat(detailOpt).isPresent();

        EvolveRunDetailDto detail = detailOpt.get();
        assertThat(detail.evolveRunId()).isEqualTo(runId);
        assertThat(detail.agentId()).isEqualTo(45L);
        assertThat(detail.status()).isEqualTo(FlywheelRunEntity.STATUS_PENDING);

        List<EvolveIterationDto> iters = detail.iterations();
        assertThat(iters).hasSize(2);

        // First by step_index=1
        EvolveIterationDto first = iters.get(0);
        assertThat(first.iteration()).isEqualTo(1);
        assertThat(first.surface()).isEqualTo("prompt");
        assertThat(first.changeDesc()).isEqualTo("First change");
        assertThat(first.candidateId()).isEqualTo("cand-a");
        assertThat(first.baselineScore()).isEqualTo(72.5);
        assertThat(first.candidateScore()).isEqualTo(74.9);
        assertThat(first.delta()).isEqualTo(2.4);
        assertThat(first.kept()).isTrue();
        assertThat(first.abRunId()).isEqualTo("ab-1");
        assertThat(first.createdAt()).isNotNull();

        // Second by step_index=2
        EvolveIterationDto second = iters.get(1);
        assertThat(second.iteration()).isEqualTo(2);
        assertThat(second.surface()).isEqualTo("skill");
        assertThat(second.kept()).isFalse();
        assertThat(second.abRunId()).isNull();
        assertThat(second.delta()).isEqualTo(-1.8);
    }

    @Test
    @DisplayName("detail: nullable scores (baselineScore/candidateScore/delta) tolerated")
    void detail_nullableScores_tolerated() {
        String runId = createEvolveRun(46L);
        // Append with no scores (not yet evaluated)
        appendIterationMinimal(runId, 1, "prompt", "Change only", "cand-c", false);

        Optional<EvolveRunDetailDto> detailOpt = evolveReadService.getRunDetail(runId);
        assertThat(detailOpt).isPresent();
        EvolveIterationDto iter = detailOpt.get().iterations().get(0);
        assertThat(iter.baselineScore()).isNull();
        assertThat(iter.candidateScore()).isNull();
        assertThat(iter.delta()).isNull();
        assertThat(iter.kept()).isFalse();
    }

    // ─── case 4 + 5: 404 cases ──────────────────────────────────────────────

    @Test
    @DisplayName("detail: empty Optional for non-evolve run (loop_kind != evolve)")
    void detail_nonEvolveRun_returnsEmpty() {
        // Insert a run with loop_kind='opt_report' directly
        String runId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, "
                        + "loop_kind, trigger_source, input_json, created_at, updated_at) "
                        + "VALUES (?, ?, NOW() - INTERVAL '7 days', NOW(), 'completed', "
                        + "'opt_report', 'api', '{}'::jsonb, NOW(), NOW())",
                runId, 99L);

        Optional<EvolveRunDetailDto> result = evolveReadService.getRunDetail(runId);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("detail: empty Optional for completely unknown run id")
    void detail_unknownRunId_returnsEmpty() {
        Optional<EvolveRunDetailDto> result = evolveReadService.getRunDetail("no-such-uuid");
        assertThat(result).isEmpty();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String createEvolveRun(long agentId) {
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("targetAgentId", agentId);
        input.put("maxIter", 10);
        FlywheelRunEntity run = flywheelRunService.startRun(
                FlywheelRunEntity.LOOP_KIND_EVOLVE,
                FlywheelRunEntity.TRIGGER_SOURCE_API,
                input,
                agentId,
                7);
        return run.getId();
    }

    private void appendIteration(String runId, int iteration, String surface,
                                  String changeDesc, String candidateId,
                                  Double baselineScore, Double candidateScore,
                                  Double delta, boolean kept, String abRunId) {
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                objectMapper.createObjectNode();
        payload.put("iteration", iteration);
        payload.put("surface", surface);
        payload.put("changeDesc", changeDesc);
        payload.put("candidateId", candidateId);
        if (baselineScore != null) payload.put("baselineScore", baselineScore);
        if (candidateScore != null) payload.put("candidateScore", candidateScore);
        if (delta != null) payload.put("delta", delta);
        payload.put("kept", kept);
        if (abRunId != null) payload.put("abRunId", abRunId);
        flywheelRunService.appendEvolveIterationStep(runId, iteration, payload);
    }

    private void appendIterationMinimal(String runId, int iteration, String surface,
                                        String changeDesc, String candidateId,
                                        boolean kept) {
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                objectMapper.createObjectNode();
        payload.put("iteration", iteration);
        payload.put("surface", surface);
        payload.put("changeDesc", changeDesc);
        payload.put("candidateId", candidateId);
        payload.put("kept", kept);
        // baselineScore / candidateScore / delta intentionally absent (nullable)
        flywheelRunService.appendEvolveIterationStep(runId, iteration, payload);
    }
}
