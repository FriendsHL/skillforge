package com.skillforge.server.service;

import com.skillforge.server.dto.SystemAgentMonitorResponse;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CanaryMetricSnapshotRepository;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.repository.ScheduledTaskRunRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SimulatorTrialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-AGENT-TYPING Phase 2.1: aggregation contract for
 * {@link SystemAgentMonitorService}. Verifies the cross-table join surface
 * (agent ⨝ scheduled_task ⨝ run ⨝ output-table) produces the right shape +
 * routes each system-agent name to its correct output table + handles the
 * "no scheduled task" / "no run history" / "V87 disabled" edge cases.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SystemAgentMonitorService.monitorAll")
class SystemAgentMonitorServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private ScheduledTaskRepository scheduledTaskRepository;
    @Mock private ScheduledTaskRunRepository scheduledTaskRunRepository;
    @Mock private SessionAnnotationRepository sessionAnnotationRepository;
    @Mock private OptimizationEventRepository optimizationEventRepository;
    @Mock private CanaryMetricSnapshotRepository canaryMetricSnapshotRepository;
    @Mock private MemoryProposalRepository memoryProposalRepository;
    @Mock private SimulatorTrialRepository simulatorTrialRepository;

    private SystemAgentMonitorService service;

    @BeforeEach
    void setUp() {
        service = new SystemAgentMonitorService(
                agentRepository,
                scheduledTaskRepository,
                scheduledTaskRunRepository,
                sessionAnnotationRepository,
                optimizationEventRepository,
                canaryMetricSnapshotRepository,
                memoryProposalRepository,
                simulatorTrialRepository);
    }

    @Test
    @DisplayName("empty system-agent list short-circuits to empty result (no DB hops beyond find)")
    void emptySystemAgents_returnsEmpty() {
        when(agentRepository.findByAgentType("system")).thenReturn(List.of());

        List<SystemAgentMonitorResponse> result = service.monitorAll();

        assertThat(result).isEmpty();
        verify(scheduledTaskRepository, never()).findByAgentIdIn(any());
    }

    @Test
    @DisplayName("aggregates one row per system agent in id-ascending order")
    void multipleSystemAgents_returnsOnePerAgent_orderedById() {
        AgentEntity a6 = agent(6L, "memory-curator", "Curates memory consolidation");
        AgentEntity a7 = agent(7L, "session-annotator", "Annotates outcomes");
        AgentEntity a8 = agent(8L, "metrics-collector", "Aggregates canary metrics");
        when(agentRepository.findByAgentType("system")).thenReturn(List.of(a8, a6, a7));  // out of order

        when(scheduledTaskRepository.findByAgentIdIn(any())).thenReturn(List.of(
                task(101L, 6L, "0 0 * * * *", Instant.parse("2026-05-17T01:00:00Z")),
                task(102L, 7L, "0 30 * * * *", Instant.parse("2026-05-17T01:30:00Z")),
                task(103L, 8L, "0 15 * * * *", Instant.parse("2026-05-17T01:15:00Z"))
        ));
        when(scheduledTaskRunRepository.findFirstByTaskIdOrderByTriggeredAtDesc(101L))
                .thenReturn(Optional.of(run("success")));
        when(scheduledTaskRunRepository.findFirstByTaskIdOrderByTriggeredAtDesc(102L))
                .thenReturn(Optional.of(run("success")));
        when(scheduledTaskRunRepository.findFirstByTaskIdOrderByTriggeredAtDesc(103L))
                .thenReturn(Optional.of(run("failure")));
        when(scheduledTaskRunRepository.countByTaskIdInAndTriggeredAtAfter(anyCollection(), any()))
                .thenReturn(5L);

        when(memoryProposalRepository.countByCreatedAtAfter(any())).thenReturn(2L);
        when(sessionAnnotationRepository.countByCreatedAtAfter(any())).thenReturn(29L);
        when(canaryMetricSnapshotRepository.countByCreatedAtAfter(any())).thenReturn(0L);

        List<SystemAgentMonitorResponse> result = service.monitorAll();

        assertThat(result)
                .as("agents ordered by id ASC regardless of repo return order")
                .extracting(SystemAgentMonitorResponse::name)
                .containsExactly("memory-curator", "session-annotator", "metrics-collector");
        assertThat(result).extracting(SystemAgentMonitorResponse::outputEntityType)
                .containsExactly("consolidations", "annotations", "metrics");
        assertThat(result).extracting(SystemAgentMonitorResponse::cronExpression)
                .containsExactly("0 0 * * * *", "0 30 * * * *", "0 15 * * * *");
        assertThat(result.get(2).lastRunStatus()).isEqualTo("failure");
    }

    @Test
    @DisplayName("system agent without a scheduled task → null cron / lastRun + 0 trigger count")
    void noScheduledTask_emitsNullsAndZeros() {
        AgentEntity orphan = agent(99L, "future-system-agent", "no cron yet");
        when(agentRepository.findByAgentType("system")).thenReturn(List.of(orphan));
        when(scheduledTaskRepository.findByAgentIdIn(any())).thenReturn(List.of());

        List<SystemAgentMonitorResponse> result = service.monitorAll();

        SystemAgentMonitorResponse got = result.get(0);
        assertThat(got.cronExpression()).isNull();
        assertThat(got.lastRunAt()).isNull();
        assertThat(got.lastRunStatus()).isNull();
        assertThat(got.sevenDayTriggerCount()).isZero();
        assertThat(got.outputEntityType())
                .as("agent name not in OUTPUT_LABELS falls back to 'unknown'")
                .isEqualTo("unknown");
        assertThat(got.sevenDayOutputCount())
                .as("unknown output entity → 0 count (defensive default)")
                .isZero();
    }

    @Test
    @DisplayName("attribution-curator routes to OptimizationEventRepository for output count")
    void attributionCurator_routesToOptimizationEventRepo() {
        AgentEntity a = agent(9L, "attribution-curator", "V3 attribution dispatcher");
        when(agentRepository.findByAgentType("system")).thenReturn(List.of(a));
        when(scheduledTaskRepository.findByAgentIdIn(any())).thenReturn(List.of(
                task(901L, 9L, "0 0 * * * *", Instant.now())));
        when(scheduledTaskRunRepository.findFirstByTaskIdOrderByTriggeredAtDesc(901L))
                .thenReturn(Optional.of(run("success")));
        when(scheduledTaskRunRepository.countByTaskIdInAndTriggeredAtAfter(anyCollection(), any()))
                .thenReturn(168L);
        when(optimizationEventRepository.countByCreatedAtAfter(any())).thenReturn(63L);

        List<SystemAgentMonitorResponse> result = service.monitorAll();

        SystemAgentMonitorResponse got = result.get(0);
        assertThat(got.outputEntityType()).isEqualTo("proposals");
        assertThat(got.sevenDayOutputCount()).isEqualTo(63L);
        verify(optimizationEventRepository).countByCreatedAtAfter(any());
        verify(memoryProposalRepository, never()).countByCreatedAtAfter(any());
        verify(sessionAnnotationRepository, never()).countByCreatedAtAfter(any());
    }

    @Test
    @DisplayName("V93 attribution-dispatcher routes to OptimizationEventRepository, outputEntityType='dispatches'")
    void attributionDispatcher_routesToOptimizationEventRepo_dispatchesLabel() {
        // V93 ATTRIBUTION-DISPATCHER-AGENT: dispatcher emits one optimization-event
        // sentinel per dispatched pattern, so its 7d output count piggy-backs the
        // same countByCreatedAtAfter query as the curator (equal-by-design 1:1
        // dispatch→event relationship). Verifies OUTPUT_LABELS extension +
        // sevenDayOutputCountFor switch case both land.
        AgentEntity a = agent(11L, "attribution-dispatcher", "V93 attribution dispatcher entry");
        when(agentRepository.findByAgentType("system")).thenReturn(List.of(a));
        when(scheduledTaskRepository.findByAgentIdIn(any())).thenReturn(List.of(
                task(1101L, 11L, "0 15 * * * *", Instant.now())));
        when(scheduledTaskRunRepository.findFirstByTaskIdOrderByTriggeredAtDesc(1101L))
                .thenReturn(Optional.of(run("success")));
        when(scheduledTaskRunRepository.countByTaskIdInAndTriggeredAtAfter(anyCollection(), any()))
                .thenReturn(168L);
        when(optimizationEventRepository.countByCreatedAtAfter(any())).thenReturn(42L);

        List<SystemAgentMonitorResponse> result = service.monitorAll();

        SystemAgentMonitorResponse got = result.get(0);
        assertThat(got.outputEntityType()).isEqualTo("dispatches");
        assertThat(got.sevenDayOutputCount()).isEqualTo(42L);
        verify(optimizationEventRepository).countByCreatedAtAfter(any());
        verify(memoryProposalRepository, never()).countByCreatedAtAfter(any());
        verify(sessionAnnotationRepository, never()).countByCreatedAtAfter(any());
    }

    @Test
    @DisplayName("user-simulator routes to SimulatorTrialRepository for output count")
    void userSimulator_routesToSimulatorTrialRepo() {
        AgentEntity a = agent(10L, "user-simulator", "V5 dynamic user sim");
        when(agentRepository.findByAgentType("system")).thenReturn(List.of(a));
        when(scheduledTaskRepository.findByAgentIdIn(any())).thenReturn(List.of());
        when(simulatorTrialRepository.countByCreatedAtAfter(any())).thenReturn(7L);

        List<SystemAgentMonitorResponse> result = service.monitorAll();

        assertThat(result.get(0).outputEntityType()).isEqualTo("trials");
        assertThat(result.get(0).sevenDayOutputCount()).isEqualTo(7L);
        verify(simulatorTrialRepository).countByCreatedAtAfter(any());
    }

    @Test
    @DisplayName("metrics-collector with V87-disabled task still surfaces — cron from task row, 0 output")
    void metricsCollectorV87Disabled_stillSurfacesWithDisabledTask() {
        AgentEntity a = agent(8L, "metrics-collector", "V77 canary metrics aggregator");
        when(agentRepository.findByAgentType("system")).thenReturn(List.of(a));
        // V87 sets enabled=false but the row + cron_expr + last_fire_at remain.
        ScheduledTaskEntity disabled = task(801L, 8L, "0 5 * * * *",
                Instant.parse("2026-05-15T00:05:00Z"));
        disabled.setEnabled(false);
        when(scheduledTaskRepository.findByAgentIdIn(any())).thenReturn(List.of(disabled));
        when(scheduledTaskRunRepository.findFirstByTaskIdOrderByTriggeredAtDesc(801L))
                .thenReturn(Optional.empty());
        when(scheduledTaskRunRepository.countByTaskIdInAndTriggeredAtAfter(anyCollection(), any()))
                .thenReturn(0L);
        when(canaryMetricSnapshotRepository.countByCreatedAtAfter(any())).thenReturn(0L);

        List<SystemAgentMonitorResponse> result = service.monitorAll();

        SystemAgentMonitorResponse got = result.get(0);
        assertThat(got.cronExpression())
                .as("cron still visible even when V87 disabled task — FE shows greyed-out state")
                .isEqualTo("0 5 * * * *");
        assertThat(got.lastRunAt()).isEqualTo(Instant.parse("2026-05-15T00:05:00Z"));
        assertThat(got.lastRunStatus())
                .as("no run row → null status")
                .isNull();
        assertThat(got.sevenDayTriggerCount()).isZero();
        assertThat(got.sevenDayOutputCount()).isZero();
        assertThat(got.outputEntityType()).isEqualTo("metrics");
    }

    private static AgentEntity agent(long id, String name, String description) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setDescription(description);
        a.setAgentType("system");
        return a;
    }

    private static ScheduledTaskEntity task(long id, long agentId, String cron, Instant lastFire) {
        ScheduledTaskEntity t = new ScheduledTaskEntity();
        t.setId(id);
        t.setAgentId(agentId);
        t.setCronExpr(cron);
        t.setLastFireAt(lastFire);
        t.setName("task-" + id);
        return t;
    }

    private static ScheduledTaskRunEntity run(String status) {
        ScheduledTaskRunEntity r = new ScheduledTaskRunEntity();
        r.setStatus(status);
        r.setTriggeredAt(Instant.now());
        return r;
    }
}
