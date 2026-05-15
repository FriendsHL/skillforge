package com.skillforge.server.controller;

import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillEvolutionRunEntity;
import com.skillforge.server.entity.SubAgentRunEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.repository.ScheduledTaskRunRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import com.skillforge.server.repository.SkillEvolutionRunRepository;
import com.skillforge.server.repository.SubAgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified task-run inventory across all run-tracking tables. Single endpoint
 * surfacing what's running / has run, so operators don't have to drill into
 * each subsystem's drawer to see activity.
 *
 * <p>Currently merges 6 tables:
 * <ul>
 *   <li>{@code t_scheduled_task_run} — cron + manual scheduled task fires
 *   <li>{@code t_subagent_run} — async sub-agent dispatches from parent sessions
 *   <li>{@code t_skill_evolution_run} — skill self-improvement loop runs
 *   <li>{@code t_skill_ab_run} — skill A/B candidate eval runs
 *   <li>{@code t_prompt_ab_run} — prompt A/B candidate eval runs
 *   <li>{@code t_collab_run} — multi-agent collaboration runs
 * </ul>
 *
 * <p>Wire shape: {@link TaskRunItem} record. Time-ordered DESC by
 * {@code triggeredAt}. Filter via {@code source} query param to narrow to
 * one subsystem; omit for the combined feed.
 *
 * <p>Auth: bearer-token gated like every other {@code /api/**} endpoint via
 * {@code WebMvcConfig}'s {@code AuthInterceptor}. No userId filtering today —
 * single-tenant dogfood pattern. When multi-tenant lands, add ownership
 * filter analogous to {@code ScheduledTaskService.assertOwnership}.
 */
@RestController
@RequestMapping("/api/tasks")
public class TasksController {

    private static final Logger log = LoggerFactory.getLogger(TasksController.class);

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private static final Set<String> KNOWN_SOURCES = Set.of(
            "scheduled_task", "subagent", "skill_evolution",
            "skill_ab", "prompt_ab", "collab");

    private final ScheduledTaskRunRepository scheduledTaskRunRepository;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final SubAgentRunRepository subAgentRunRepository;
    private final SkillEvolutionRunRepository skillEvolutionRunRepository;
    private final SkillAbRunRepository skillAbRunRepository;
    private final PromptAbRunRepository promptAbRunRepository;
    private final CollabRunRepository collabRunRepository;

    public TasksController(ScheduledTaskRunRepository scheduledTaskRunRepository,
                           ScheduledTaskRepository scheduledTaskRepository,
                           SubAgentRunRepository subAgentRunRepository,
                           SkillEvolutionRunRepository skillEvolutionRunRepository,
                           SkillAbRunRepository skillAbRunRepository,
                           PromptAbRunRepository promptAbRunRepository,
                           CollabRunRepository collabRunRepository) {
        this.scheduledTaskRunRepository = scheduledTaskRunRepository;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.subAgentRunRepository = subAgentRunRepository;
        this.skillEvolutionRunRepository = skillEvolutionRunRepository;
        this.skillAbRunRepository = skillAbRunRepository;
        this.promptAbRunRepository = promptAbRunRepository;
        this.collabRunRepository = collabRunRepository;
    }

    @GetMapping("/runs")
    public ResponseEntity<List<TaskRunItem>> listRuns(
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        List<TaskRunItem> combined = new ArrayList<>();

        // Each subsystem returns at most safeLimit rows; merge + sort + cut.
        // Per-source over-fetch is bounded so worst case = 6 × MAX_LIMIT.
        if (source == null || "scheduled_task".equals(source)) {
            combined.addAll(loadScheduledTaskRuns(safeLimit));
        }
        if (source == null || "subagent".equals(source)) {
            combined.addAll(loadSubAgentRuns(safeLimit));
        }
        if (source == null || "skill_evolution".equals(source)) {
            combined.addAll(loadSkillEvolutionRuns(safeLimit));
        }
        if (source == null || "skill_ab".equals(source)) {
            combined.addAll(loadSkillAbRuns(safeLimit));
        }
        if (source == null || "prompt_ab".equals(source)) {
            combined.addAll(loadPromptAbRuns(safeLimit));
        }
        if (source == null || "collab".equals(source)) {
            combined.addAll(loadCollabRuns(safeLimit));
        }

        // Reject unknown source param explicitly — silent empty would be
        // misleading.
        if (source != null && !KNOWN_SOURCES.contains(source)) {
            return ResponseEntity.badRequest().build();
        }

        combined.sort(Comparator
                .comparing((TaskRunItem t) -> t.triggeredAt() == null ? Instant.EPOCH : t.triggeredAt())
                .reversed());
        if (combined.size() > safeLimit) {
            combined = combined.subList(0, safeLimit);
        }
        log.debug("listRuns source={} limit={} returned={}", source, safeLimit, combined.size());
        return ResponseEntity.ok(combined);
    }

    // -----------------------------------------------------------------------
    // Per-source loaders
    // -----------------------------------------------------------------------

    private List<TaskRunItem> loadScheduledTaskRuns(int limit) {
        List<ScheduledTaskRunEntity> rows = scheduledTaskRunRepository
                .findAll(PageRequest.of(0, limit,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "triggeredAt")))
                .getContent();
        // Batch task-name lookup — operators want "metrics-collector-hourly"
        // not "task_id=5".
        Map<Long, String> taskNameById = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Long> taskIds = rows.stream().map(ScheduledTaskRunEntity::getTaskId).distinct().toList();
            for (ScheduledTaskEntity t : scheduledTaskRepository.findAllById(taskIds)) {
                taskNameById.put(t.getId(), t.getName());
            }
        }
        List<TaskRunItem> out = new ArrayList<>(rows.size());
        for (ScheduledTaskRunEntity r : rows) {
            String name = taskNameById.getOrDefault(r.getTaskId(), "task#" + r.getTaskId());
            out.add(new TaskRunItem(
                    "scheduled_task:" + r.getId(),
                    "scheduled_task",
                    name,
                    r.getStatus(),
                    r.getTriggeredAt(),
                    r.getFinishedAt(),
                    r.getTriggeredSessionId(),
                    r.isManual() ? "manual trigger" : "cron fire",
                    truncate(r.getErrorMessage(), 200)));
        }
        return out;
    }

    private List<TaskRunItem> loadSubAgentRuns(int limit) {
        List<SubAgentRunEntity> rows = subAgentRunRepository
                .findAll(PageRequest.of(0, limit,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "spawnedAt")))
                .getContent();
        List<TaskRunItem> out = new ArrayList<>(rows.size());
        for (SubAgentRunEntity r : rows) {
            String name = r.getChildAgentName() != null ? r.getChildAgentName() : "subagent";
            out.add(new TaskRunItem(
                    "subagent:" + r.getRunId(),
                    "subagent",
                    name,
                    r.getStatus(),
                    r.getSpawnedAt(),
                    r.getCompletedAt(),
                    r.getChildSessionId(),
                    truncate(r.getTask(), 200),
                    null));
        }
        return out;
    }

    private List<TaskRunItem> loadSkillEvolutionRuns(int limit) {
        List<SkillEvolutionRunEntity> rows = skillEvolutionRunRepository
                .findAll(PageRequest.of(0, limit,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .getContent();
        List<TaskRunItem> out = new ArrayList<>(rows.size());
        for (SkillEvolutionRunEntity r : rows) {
            out.add(new TaskRunItem(
                    "skill_evolution:" + r.getId(),
                    "skill_evolution",
                    "skill#" + r.getSkillId(),
                    r.getStatus(),
                    r.getCreatedAt(),
                    r.getCompletedAt(),
                    null,
                    truncate(r.getEvolutionReasoning(), 200),
                    truncate(r.getFailureReason(), 200)));
        }
        return out;
    }

    private List<TaskRunItem> loadSkillAbRuns(int limit) {
        List<SkillAbRunEntity> rows = skillAbRunRepository
                .findAll(PageRequest.of(0, limit,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .getContent();
        List<TaskRunItem> out = new ArrayList<>(rows.size());
        for (SkillAbRunEntity r : rows) {
            String summary = String.format("skill A/B baseline=%s candidate=%s%s",
                    r.getParentSkillId(),
                    r.getCandidateSkillId(),
                    r.isPromoted() ? " [promoted]" : "");
            out.add(new TaskRunItem(
                    "skill_ab:" + r.getId(),
                    "skill_ab",
                    "skill_ab#" + r.getId().substring(0, Math.min(8, r.getId().length())),
                    r.getStatus(),
                    r.getCreatedAt(),
                    r.getCompletedAt(),
                    null,
                    summary,
                    truncate(r.getFailureReason(), 200)));
        }
        return out;
    }

    private List<TaskRunItem> loadPromptAbRuns(int limit) {
        // PromptAbRunEntity has startedAt instead of createdAt — use it.
        List<PromptAbRunEntity> rows = promptAbRunRepository
                .findAll(PageRequest.of(0, limit,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "startedAt")))
                .getContent();
        List<TaskRunItem> out = new ArrayList<>(rows.size());
        for (PromptAbRunEntity r : rows) {
            String summary = String.format("prompt A/B agent=%s version=%s%s",
                    truncate(r.getAgentId(), 32),
                    truncate(r.getPromptVersionId(), 32),
                    r.isPromoted() ? " [promoted]" : "");
            out.add(new TaskRunItem(
                    "prompt_ab:" + r.getId(),
                    "prompt_ab",
                    "prompt_ab#" + r.getId().substring(0, Math.min(8, r.getId().length())),
                    r.getStatus(),
                    r.getStartedAt(),
                    r.getCompletedAt(),
                    null,
                    summary,
                    truncate(r.getFailureReason(), 200)));
        }
        return out;
    }

    private List<TaskRunItem> loadCollabRuns(int limit) {
        List<CollabRunEntity> rows = collabRunRepository
                .findAll(PageRequest.of(0, limit,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .getContent();
        List<TaskRunItem> out = new ArrayList<>(rows.size());
        for (CollabRunEntity r : rows) {
            String summary = String.format("collab leader=%s max_depth=%d max_agents=%d type=%s",
                    truncate(r.getLeaderSessionId(), 16),
                    r.getMaxDepth(),
                    r.getMaxTotalAgents(),
                    r.getRunType() != null ? r.getRunType() : "unknown");
            out.add(new TaskRunItem(
                    "collab:" + r.getCollabRunId(),
                    "collab",
                    "collab#" + r.getCollabRunId().substring(0, Math.min(8, r.getCollabRunId().length())),
                    r.getStatus(),
                    r.getCreatedAt(),
                    r.getCompletedAt(),
                    r.getLeaderSessionId(),
                    summary,
                    null));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /**
     * Unified task-run row. Most fields are nullable because different
     * subsystems track different lifecycle metadata.
     *
     * @param runId             composite id: {@code "<source>:<native_id>"}
     * @param source            one of KNOWN_SOURCES
     * @param name              human-readable task / agent / skill identifier
     * @param status            subsystem-specific status string
     * @param triggeredAt       row's "started at" timestamp (sort key)
     * @param finishedAt        nullable when still running
     * @param sessionId         linked session, for drill-down. Null when not applicable.
     * @param detail            brief description / summary for the row
     * @param errorMessage      truncated failure reason, null on success
     */
    public record TaskRunItem(
            String runId,
            String source,
            String name,
            String status,
            Instant triggeredAt,
            Instant finishedAt,
            String sessionId,
            String detail,
            String errorMessage
    ) {}
}
