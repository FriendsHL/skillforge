package com.skillforge.workflow.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Task F — {@link JournalCacheService} lookup/filter/parse logic (repo mocked).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JournalCacheService")
class JournalCacheServiceTest {

    @Mock private FlywheelRunStepRepository stepRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private JournalCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new JournalCacheService(stepRepository, objectMapper);
    }

    private FlywheelRunStepEntity step(String kind, String status, String outputJson) {
        FlywheelRunStepEntity s = new FlywheelRunStepEntity();
        s.setStepKind(kind);
        s.setStatus(status);
        s.setStepOutputJson(outputJson);
        return s;
    }

    @Test
    @DisplayName("getCachedAgentFinalResponse returns finalResponse of a completed subagent step")
    void agentCacheHit() {
        when(stepRepository.findByRunIdAndStepIndexAndStepKind(
                "run-1", 2, FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH))
                .thenReturn(Optional.of(step(
                        FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH,
                        FlywheelRunStepEntity.STATUS_COMPLETED,
                        "{\"finalResponse\":\"hello\",\"loopCount\":1}")));

        assertThat(cache.getCachedAgentFinalResponse("run-1", 2)).contains("hello");
    }

    @Test
    @DisplayName("getCachedAgentFinalResponse misses when the step is not completed")
    void agentCacheMissOnPending() {
        when(stepRepository.findByRunIdAndStepIndexAndStepKind(
                "run-1", 2, FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH))
                .thenReturn(Optional.of(step(
                        FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH,
                        FlywheelRunStepEntity.STATUS_PENDING,
                        "{\"finalResponse\":\"hello\"}")));

        assertThat(cache.getCachedAgentFinalResponse("run-1", 2)).isEmpty();
    }

    @Test
    @DisplayName("getCachedAgentFinalResponse misses when there is no row")
    void agentCacheMissOnAbsent() {
        when(stepRepository.findByRunIdAndStepIndexAndStepKind(
                "run-1", 9, FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH))
                .thenReturn(Optional.empty());

        assertThat(cache.getCachedAgentFinalResponse("run-1", 9)).isEmpty();
    }

    @Test
    @DisplayName("getApproveDecision returns the recorded decision of a completed gate step")
    void decisionHit() {
        when(stepRepository.findByRunIdAndStepIndexAndStepKind(
                "run-1", 1, FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE))
                .thenReturn(Optional.of(step(
                        FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE,
                        FlywheelRunStepEntity.STATUS_COMPLETED,
                        "{\"approved\":true,\"reviewerId\":\"alice\"}")));

        Optional<JsonNode> decision = cache.getApproveDecision("run-1", 1);
        assertThat(decision).isPresent();
        assertThat(decision.get().path("approved").asBoolean()).isTrue();
        assertThat(decision.get().path("reviewerId").asText()).isEqualTo("alice");
    }

    @Test
    @DisplayName("getApproveDecision misses while the gate is still pending (not yet decided)")
    void decisionMissWhilePending() {
        when(stepRepository.findByRunIdAndStepIndexAndStepKind(
                "run-1", 1, FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE))
                .thenReturn(Optional.of(step(
                        FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE,
                        FlywheelRunStepEntity.STATUS_PENDING, null)));

        assertThat(cache.getApproveDecision("run-1", 1)).isEmpty();
    }
}
