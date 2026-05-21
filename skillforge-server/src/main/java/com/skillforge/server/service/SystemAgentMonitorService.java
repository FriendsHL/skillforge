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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SYSTEM-AGENT-TYPING Phase 2.1: aggregation for the
 * {@code GET /api/system-agents/monitor} endpoint (PRD F4).
 *
 * <p>Cross-table join lives here (not in the controller) so the controller
 * stays a thin pass-through and the aggregation is unit-testable with mocked
 * repositories. The join surface:
 * <pre>
 *   t_agent (agent_type='system')
 *     ⨝ t_scheduled_task (agent_id)               — cron / last_fire
 *     ⨝ t_scheduled_task_run (latest by task_id)  — last_run_status
 *     ⨝ t_scheduled_task_run COUNT 7d             — sevenDayTriggerCount
 *     ⨝ &lt;agent-specific output table&gt; COUNT 7d   — sevenDayOutputCount
 * </pre>
 *
 * <p>Per-agent output-table mapping is hardcoded (5 known V69/V75/V79/V81/V85
 * system agents) because the table choice is determined by agent identity, not
 * data — adding a new system agent requires a code change anyway (new
 * bootstrap + migration), so a static map is the right grain.
 *
 * <p>Query plan: 1 query for system agents, 1 batched query for scheduled
 * tasks, 1 batched query for 7d trigger count, 1 per-agent query for last
 * run status (≤ 5), 1 per-agent query for 7d output count (≤ 5). Total ≤ 12
 * DB hops bounded by N=5 system agents — well within the O(1) target the
 * monitor endpoint needs.
 */
@Service
public class SystemAgentMonitorService {

    /** Window for the {@code sevenDay*} counts. */
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    /**
     * Output-entity-type label per system-agent name. Stable across the
     * canonical V69/V75/V79/V81/V85 set; unknown names default to
     * {@code "unknown"} (defensive — a future system agent without a wired
     * count will still surface in the list with 0 output count + this label
     * so the gap is visible rather than silently misleading).
     */
    private static final Map<String, String> OUTPUT_LABELS = Map.of(
            "memory-curator", "consolidations",
            "session-annotator", "annotations",
            "metrics-collector", "metrics",
            "attribution-curator", "proposals",
            "user-simulator", "trials",
            // ATTRIBUTION-DISPATCHER-AGENT (V93): dispatcher itself emits one
            // optimization-event sentinel row per dispatched pattern, so its
            // 7d output count piggy-backs the same OptimizationEventRepository
            // query as the curator (sevenDayOutputCountFor switch below). The
            // dispatcher's count being equal to the curator's count is the
            // expected 1:1 dispatch→event relationship; stage-level health
            // (proposal_pending vs proposal_rejected vs candidate_failed) is
            // surfaced separately via /api/optimization-events.
            "attribution-dispatcher", "dispatches");

    private final AgentRepository agentRepository;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ScheduledTaskRunRepository scheduledTaskRunRepository;
    private final SessionAnnotationRepository sessionAnnotationRepository;
    private final OptimizationEventRepository optimizationEventRepository;
    private final CanaryMetricSnapshotRepository canaryMetricSnapshotRepository;
    private final MemoryProposalRepository memoryProposalRepository;
    private final SimulatorTrialRepository simulatorTrialRepository;

    public SystemAgentMonitorService(
            AgentRepository agentRepository,
            ScheduledTaskRepository scheduledTaskRepository,
            ScheduledTaskRunRepository scheduledTaskRunRepository,
            SessionAnnotationRepository sessionAnnotationRepository,
            OptimizationEventRepository optimizationEventRepository,
            CanaryMetricSnapshotRepository canaryMetricSnapshotRepository,
            MemoryProposalRepository memoryProposalRepository,
            SimulatorTrialRepository simulatorTrialRepository) {
        this.agentRepository = agentRepository;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.scheduledTaskRunRepository = scheduledTaskRunRepository;
        this.sessionAnnotationRepository = sessionAnnotationRepository;
        this.optimizationEventRepository = optimizationEventRepository;
        this.canaryMetricSnapshotRepository = canaryMetricSnapshotRepository;
        this.memoryProposalRepository = memoryProposalRepository;
        this.simulatorTrialRepository = simulatorTrialRepository;
    }

    /**
     * Build one {@link SystemAgentMonitorResponse} per {@code agent_type='system'}
     * agent. Ordering is by {@code t_agent.id} ascending so the V69/V75/V79/V81/V85
     * insertion order is preserved (memory-curator first, user-simulator last).
     *
     * <p>Read-only — the aggregation only issues SELECT statements. Marked
     * {@code @Transactional(readOnly = true)} so the JDBC driver can route to a
     * read replica when the deployment configures one.
     */
    @Transactional(readOnly = true)
    public List<SystemAgentMonitorResponse> monitorAll() {
        List<AgentEntity> repoSystemAgents = agentRepository.findByAgentType("system");
        if (repoSystemAgents.isEmpty()) {
            return List.of();
        }
        // Copy before sort: the repository may return an immutable list (e.g. test
        // doubles via List.of(...)), and the JPA spec doesn't guarantee a mutable
        // list either way. We need ascending id order to keep the response
        // deterministic across DB backends.
        List<AgentEntity> systemAgents = new ArrayList<>(repoSystemAgents);
        systemAgents.sort(Comparator.comparing(AgentEntity::getId));

        List<Long> agentIds = systemAgents.stream().map(AgentEntity::getId).toList();

        // Batched lookup: every scheduled task for these agents. An agent may have
        // 0 or 1 tasks (today: 1 per system agent), but the contract supports >1
        // — pick the most recently fired one as the "primary" cron for the card.
        Map<Long, ScheduledTaskEntity> primaryTaskByAgent = primaryTaskByAgentId(agentIds);

        // Batched 7d trigger count across all system-agent tasks.
        Instant cutoff = Instant.now().minus(SEVEN_DAYS);
        List<Long> taskIds = primaryTaskByAgent.values().stream()
                .map(ScheduledTaskEntity::getId)
                .toList();
        Map<Long, Long> sevenDayTriggerByTask = sevenDayTriggerByTaskId(taskIds, cutoff);

        List<SystemAgentMonitorResponse> result = new ArrayList<>(systemAgents.size());
        for (AgentEntity agent : systemAgents) {
            ScheduledTaskEntity task = primaryTaskByAgent.get(agent.getId());
            Optional<ScheduledTaskRunEntity> latestRun = task == null
                    ? Optional.empty()
                    : scheduledTaskRunRepository.findFirstByTaskIdOrderByTriggeredAtDesc(task.getId());

            String cronExpression = task == null ? null : task.getCronExpr();
            Instant lastRunAt = task == null ? null : task.getLastFireAt();
            String lastRunStatus = latestRun.map(ScheduledTaskRunEntity::getStatus).orElse(null);
            long sevenDayTriggerCount = task == null
                    ? 0L
                    : sevenDayTriggerByTask.getOrDefault(task.getId(), 0L);
            long sevenDayOutputCount = sevenDayOutputCountFor(agent.getName(), cutoff);
            String outputEntityType = OUTPUT_LABELS.getOrDefault(agent.getName(), "unknown");

            result.add(new SystemAgentMonitorResponse(
                    agent.getId(),
                    agent.getName(),
                    agent.getDescription(),
                    cronExpression,
                    lastRunAt,
                    lastRunStatus,
                    sevenDayTriggerCount,
                    sevenDayOutputCount,
                    outputEntityType));
        }
        return result;
    }

    /**
     * Group every scheduled task for the supplied agent ids by {@code agentId},
     * picking the most-recently-fired task per agent (ties broken by id DESC so
     * a never-fired task still has a stable winner). Empty agent ids returns an
     * empty map without hitting the DB.
     */
    private Map<Long, ScheduledTaskEntity> primaryTaskByAgentId(List<Long> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        List<ScheduledTaskEntity> tasks = scheduledTaskRepository.findByAgentIdIn(agentIds);
        Map<Long, ScheduledTaskEntity> byAgent = new HashMap<>();
        for (ScheduledTaskEntity t : tasks) {
            ScheduledTaskEntity prev = byAgent.get(t.getAgentId());
            if (prev == null || isMoreRecent(t, prev)) {
                byAgent.put(t.getAgentId(), t);
            }
        }
        return byAgent;
    }

    private static boolean isMoreRecent(ScheduledTaskEntity candidate, ScheduledTaskEntity incumbent) {
        Instant candFire = candidate.getLastFireAt();
        Instant incFire = incumbent.getLastFireAt();
        if (candFire != null && incFire != null) {
            int cmp = candFire.compareTo(incFire);
            if (cmp != 0) return cmp > 0;
        } else if (candFire != null) {
            return true;
        } else if (incFire != null) {
            return false;
        }
        // Both null fire times → fall back to id DESC for stability.
        return candidate.getId() > incumbent.getId();
    }

    /**
     * Single batched COUNT GROUP BY task_id is overkill for N=5; instead the
     * repository exposes a {@code countByTaskIdInAndTriggeredAtAfter(...)}
     * one-shot total, and we re-issue per-task counts since per-task accuracy
     * matters for the FE card. With N=5 this is at most 5 small index-backed
     * COUNTs — no need for a fancier GROUP BY query.
     */
    private Map<Long, Long> sevenDayTriggerByTaskId(List<Long> taskIds, Instant cutoff) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> out = new HashMap<>();
        for (Long id : taskIds) {
            long count = scheduledTaskRunRepository.countByTaskIdInAndTriggeredAtAfter(
                    List.of(id), cutoff);
            out.put(id, count);
        }
        return out;
    }

    private long sevenDayOutputCountFor(String agentName, Instant cutoff) {
        return switch (agentName) {
            case "session-annotator" -> sessionAnnotationRepository.countByCreatedAtAfter(cutoff);
            case "attribution-curator" -> optimizationEventRepository.countByCreatedAtAfter(cutoff);
            case "metrics-collector" -> canaryMetricSnapshotRepository.countByCreatedAtAfter(cutoff);
            case "memory-curator" -> memoryProposalRepository.countByCreatedAtAfter(cutoff);
            case "user-simulator" -> simulatorTrialRepository.countByCreatedAtAfter(cutoff);
            // V93: dispatcher row count == curator row count by design (each
            // dispatch writes one OptimizationEvent sentinel that the curator
            // then updates in place). Re-using countByCreatedAtAfter keeps the
            // metric truthful without introducing a parallel "dispatched"
            // sub-query that would diverge from curator state.
            case "attribution-dispatcher" -> optimizationEventRepository.countByCreatedAtAfter(cutoff);
            default -> 0L;
        };
    }
}
