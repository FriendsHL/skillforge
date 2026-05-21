package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.memory.llmsynth.LlmMemorySynthesisScheduler;
import com.skillforge.server.memory.llmsynth.LlmMemorySynthesisScheduler.SchedulerSummary;
import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.service.MemoryProposalService;
import com.skillforge.server.service.scheduling.ScheduledTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the dogfood-path {@code POST /api/admin/memory/llm-synthesis/run-once}
 * endpoint refactor (V69).
 *
 * <p>Focuses on the run-once branching: dogfood happy path / skip-if-running collision /
 * legacy fallback when memory-curator seed is absent. Other endpoints are covered
 * by existing tests; this only covers the runOnce changes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMemoryLlmSynthesisController#runOnce (V69 dogfood)")
class AdminMemoryLlmSynthesisControllerRunOnceTest {

    @Mock private LlmMemorySynthesisScheduler scheduler;
    @Mock private MemoryProposalService proposalService;
    @Mock private MemoryRepository memoryRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private ScheduledTaskRepository scheduledTaskRepository;
    @Mock private ScheduledTaskExecutor scheduledTaskExecutor;

    private ObjectMapper objectMapper;
    private AdminMemoryLlmSynthesisController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new AdminMemoryLlmSynthesisController(
                scheduler, proposalService, memoryRepository, objectMapper,
                agentRepository, scheduledTaskRepository, scheduledTaskExecutor);
    }

    @Test
    @DisplayName("dogfood path: fires the seeded ScheduledTask and returns 202 with sessionId")
    void runOnce_dogfoodHappyPath_returnsAccepted() {
        AgentEntity agent = newAgent(101L);
        ScheduledTaskEntity task = newTask(42L, 101L);
        when(agentRepository.findFirstByName(SystemAgentNames.MEMORY_CURATOR))
                .thenReturn(Optional.of(agent));
        when(scheduledTaskRepository.findAll()).thenReturn(List.of(task));
        when(scheduledTaskExecutor.fireForResult(42L, true))
                .thenReturn(Optional.of(new ScheduledTaskExecutor.FireOutcome(
                        42L, 777L, "sess-curator-xyz", true)));

        ResponseEntity<?> resp = controller.runOnce(/* userId */ null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("ok")).isEqualTo(true);
        assertThat(body.get("ran")).isEqualTo("memory-curator-scheduled-task");
        assertThat(body.get("taskId")).isEqualTo(42L);
        assertThat(body.get("runId")).isEqualTo(777L);
        assertThat(body.get("sessionId")).isEqualTo("sess-curator-xyz");
        assertThat(body.get("status")).isEqualTo("queued");
        // Counts default to 0 — the dogfood path doesn't know counts at trigger time.
        assertThat(body.get("dedupCount")).isEqualTo(0);

        // Legacy scheduler MUST NOT be called when the dogfood path succeeded.
        verify(scheduler, never()).runOnce(anyLong());
    }

    @Test
    @DisplayName("dogfood path: skip-if-running collision returns 409 without invoking legacy fallback")
    void runOnce_skipIfRunning_returns409() {
        AgentEntity agent = newAgent(101L);
        ScheduledTaskEntity task = newTask(42L, 101L);
        when(agentRepository.findFirstByName(SystemAgentNames.MEMORY_CURATOR))
                .thenReturn(Optional.of(agent));
        when(scheduledTaskRepository.findAll()).thenReturn(List.of(task));
        when(scheduledTaskExecutor.fireForResult(42L, true)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.runOnce(null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        verify(scheduler, never()).runOnce(anyLong());
    }

    @Test
    @DisplayName("legacy fallback: no memory-curator agent → calls LlmMemorySynthesisScheduler.runOnce with bypassGate=true (R2-1)")
    void runOnce_noSeededAgent_fallsBackToLegacyScheduler() {
        when(agentRepository.findFirstByName(SystemAgentNames.MEMORY_CURATOR))
                .thenReturn(Optional.empty());
        // R2-1: controller passes bypassGate=true so default-disabled scheduler still runs.
        when(scheduler.runOnce(eq(7L), eq(true))).thenReturn(new SchedulerSummary(
                /* eligible */ 1, /* succeeded */ 1, /* failed */ 0,
                /* dedup */ 2, /* reflection */ 1, /* optimize */ 0, /* contradiction */ 0,
                /* inputTokens */ 1234L, /* outputTokens */ 56L, /* estimatedUsd */ 0.0007));

        ResponseEntity<?> resp = controller.runOnce(7L);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("ran")).isEqualTo("memory-llm-synthesis-legacy");
        assertThat(body.get("dedupCount")).isEqualTo(2);
        // Executor MUST NOT be touched when falling back.
        verify(scheduledTaskExecutor, never()).fireForResult(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("legacy fallback: agent exists but no matching scheduled-task → still falls back")
    void runOnce_agentExistsButNoTask_fallsBack() {
        AgentEntity agent = newAgent(101L);
        // Another task in DB belongs to a different agent.
        ScheduledTaskEntity unrelated = newTask(1L, 999L);
        when(agentRepository.findFirstByName(SystemAgentNames.MEMORY_CURATOR))
                .thenReturn(Optional.of(agent));
        when(scheduledTaskRepository.findAll()).thenReturn(List.of(unrelated));
        // R2-1: controller calls 2-arg runOnce with bypassGate=true.
        when(scheduler.runOnce(any(), eq(true))).thenReturn(new SchedulerSummary(
                0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0.0));

        ResponseEntity<?> resp = controller.runOnce(null);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(scheduledTaskExecutor, never()).fireForResult(anyLong(), anyBoolean());
    }

    private static AgentEntity newAgent(long id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(SystemAgentNames.MEMORY_CURATOR);
        return a;
    }

    private static ScheduledTaskEntity newTask(long taskId, long agentId) {
        ScheduledTaskEntity t = new ScheduledTaskEntity();
        t.setId(taskId);
        t.setAgentId(agentId);
        t.setCreatorUserId(0L);       // SYSTEM marker — must match the filter
        t.setName("memory-curator nightly");
        t.setEnabled(false);
        t.setCronExpr("0 30 4 * * *");
        return t;
    }
}
