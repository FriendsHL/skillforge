package com.skillforge.server.service;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.server.dto.SessionTaskResponse;
import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionTaskAttemptEntity;
import com.skillforge.server.entity.SessionTaskEntity;
import com.skillforge.server.entity.SessionTaskEventEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionTaskAttemptRepository;
import com.skillforge.server.repository.SessionTaskEventRepository;
import com.skillforge.server.repository.SessionTaskRepository;
import com.skillforge.server.service.event.TeamTaskAvailableEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TeamTaskGraphService implements TaskToolOperations {
    public static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final Set<String> TERMINAL = Set.of("completed", "deleted");

    private final SessionRepository sessionRepository;
    private final CollabRunRepository collabRunRepository;
    private final SessionTaskRepository taskRepository;
    private final SessionTaskAttemptRepository attemptRepository;
    private final SessionTaskEventRepository eventRepository;
    private final SessionTaskService taskService;
    private final ApplicationEventPublisher eventPublisher;
    private final CancellationRegistry cancellationRegistry;
    private final Clock clock;

    public TeamTaskGraphService(SessionRepository sessionRepository,
                                CollabRunRepository collabRunRepository,
                                SessionTaskRepository taskRepository,
                                SessionTaskAttemptRepository attemptRepository,
                                SessionTaskEventRepository eventRepository,
                                SessionTaskService taskService,
                                ApplicationEventPublisher eventPublisher,
                                CancellationRegistry cancellationRegistry,
                                Clock clock) {
        this.sessionRepository = sessionRepository;
        this.collabRunRepository = collabRunRepository;
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
        this.cancellationRegistry = cancellationRegistry;
        this.clock = clock;
    }

    record Scope(SessionEntity actor, SessionEntity graph, CollabRunEntity collabRun, boolean leader) {
        boolean team() { return collabRun != null; }
    }

    @Override
    @Transactional
    public SessionTaskService.CreateResult create(String actorSessionId, Long actorUserId,
                                                  SessionTaskService.CreateCommand command) {
        Scope scope = resolve(actorSessionId, actorUserId, true);
        if (scope.team() && !scope.leader()) {
            throw error("TASK_LEADER_REQUIRED", "Only the Team leader may create shared tasks", false,
                    "subject", "Ask the Team leader to create the task");
        }
        SessionTaskService.CreateCommand safeCommand = scope.team()
                ? new SessionTaskService.CreateCommand(command.subject(), command.description(), command.activeForm(),
                null, command.metadata(), command.blockedBy())
                : command;
        SessionTaskService.CreateResult result = taskService.create(
                scope.graph().getId(), actorUserId, safeCommand);
        if (scope.team()) {
            record(scope, result.task().taskId(), null, "TASK_CREATED", scope.actor(),
                    null, "pending", null);
            if (result.task().blocked()) {
                record(scope, result.task().taskId(), null, "TASK_BLOCKED", scope.actor(),
                        "pending", "pending", "DEPENDENCY_ADDED");
            }
        }
        return result;
    }

    @Override
    @Transactional
    public SessionTaskSnapshotResponse update(String actorSessionId, Long actorUserId, String taskId,
                                              Long expectedRevision,
                                              SessionTaskService.UpdateCommand command) {
        Scope scope = resolve(actorSessionId, actorUserId, true);
        if (!scope.team()) {
            return taskService.update(scope.graph().getId(), actorUserId, taskId, expectedRevision, command);
        }
        if (expectedRevision == null) {
            throw error("TASK_REVISION_REQUIRED", "expectedRevision is required in a Team", false,
                    "expectedRevision", "Call TaskGet and retry with the returned version");
        }
        if (command.ownerPresent()) {
            throw error("TASK_OWNER_SERVER_CONTROLLED", "Team task owner is controlled by the runtime", false,
                    "owner", "Use status=in_progress to claim the task");
        }

        SessionTaskEntity beforeTask = requireTask(scope, taskId);
        if (beforeTask.getVersion() != expectedRevision) {
            throw error("TASK_REVISION_CONFLICT", "Task revision changed concurrently", true,
                    "expectedRevision", "Call TaskGet and retry with the returned version");
        }
        SessionTaskSnapshotResponse before = taskService.snapshot(scope.graph().getId(), actorUserId, true);
        String oldStatus = beforeTask.getStatus();
        String targetStatus = command.status() == null ? oldStatus : command.status().trim().toLowerCase(java.util.Locale.ROOT);
        SessionTaskAttemptEntity active = attemptRepository.findByTaskIdAndStatus(taskId, "ACTIVE").orElse(null);
        boolean dependencyEdit = hasItems(command.addBlockedBy()) || hasItems(command.removeBlockedBy())
                || hasItems(command.addBlocks()) || hasItems(command.removeBlocks());
        if (dependencyEdit && !scope.leader()) {
            throw error("TASK_LEADER_REQUIRED", "Only the Team leader may edit task dependencies", false,
                    "dependencies", "Ask the Team leader to update the task graph");
        }
        if (dependencyEdit && !attemptRepository.findByGraphSessionIdAndStatus(
                scope.graph().getId(), "ACTIVE").isEmpty()) {
            throw error("TASK_ACTIVE_DEPENDENCY_CHANGE", "Release active Team tasks before changing dependencies", false,
                    "dependencies", "Release every active claim, then update the graph with the latest revisions");
        }

        boolean claim = "pending".equals(oldStatus) && "in_progress".equals(targetStatus);
        boolean completing = "in_progress".equals(oldStatus) && "completed".equals(targetStatus);
        boolean releasing = "in_progress".equals(oldStatus) && "pending".equals(targetStatus);
        boolean deletingClaimed = "in_progress".equals(oldStatus) && "deleted".equals(targetStatus);
        if (claim) {
            if (active != null) {
                throw error("TASK_ALREADY_CLAIMED", "Task already has an active owner", true,
                        "status", "Call TaskGet and choose another available task");
            }
            if (!attemptRepository.findByWorkerSessionIdAndStatus(actorSessionId, "ACTIVE").isEmpty()) {
                throw error("TASK_WORKER_BUSY", "Worker already owns an active task", false,
                        "status", "Complete or release the current task before claiming another");
            }
        } else if (completing) {
            requireOwner(scope, active);
        } else if (releasing || "in_progress".equals(oldStatus)) {
            requireOwnerOrLeader(scope, active);
        } else if (!scope.leader()) {
            throw error("TASK_NOT_OWNER", "Only the Team leader may edit an unclaimed task", false,
                    "taskId", "Claim the task first or ask the Team leader to update it");
        }
        if ("completed".equals(targetStatus) && !"in_progress".equals(oldStatus)) {
            throw error("TASK_NOT_CLAIMED", "Team task must be claimed before completion", false,
                    "status", "Claim the task with status=in_progress first");
        }
        if ("deleted".equals(targetStatus) && !scope.leader()) {
            throw error("TASK_LEADER_REQUIRED", "Only the Team leader may delete a task", false,
                    "status", "Ask the Team leader to delete the task");
        }

        SessionTaskService.UpdateCommand safeCommand = withRuntimeOwner(command,
                claim ? actorSessionId : (releasing || deletingClaimed) ? null : beforeTask.getOwner(),
                claim || releasing || deletingClaimed);
        SessionTaskSnapshotResponse updated = taskService.update(
                scope.graph().getId(), actorUserId, taskId, expectedRevision, safeCommand);

        if (claim) {
            active = createAttempt(scope, taskId);
            record(scope, taskId, active, "TASK_CLAIMED", scope.actor(), oldStatus, targetStatus, null);
        } else if (completing) {
            finishAttempt(active, "COMPLETED", null);
            record(scope, taskId, active, "TASK_COMPLETED", scope.actor(), oldStatus, targetStatus, null);
        } else if (releasing) {
            finishAttempt(active, "RELEASED", "EXPLICIT_RELEASE");
            SessionTaskEventEntity released = record(scope, taskId, active, "TASK_RELEASED",
                    scope.actor(), oldStatus, targetStatus, "EXPLICIT_RELEASE");
            publishAvailable(scope, released, updated, taskId);
        } else if (deletingClaimed) {
            finishAttempt(active, "RELEASED", "LEADER_DELETED_TASK");
            record(scope, taskId, active, "TASK_RELEASED", scope.actor(), oldStatus, targetStatus,
                    "LEADER_DELETED_TASK");
        } else {
            record(scope, taskId, active, "TASK_UPDATED", scope.actor(), oldStatus, targetStatus, null);
        }
        recordGraphTransitions(scope, before, updated);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public SessionTaskResponse get(String actorSessionId, Long actorUserId, String taskId) {
        Scope scope = resolve(actorSessionId, actorUserId, false);
        return taskService.get(scope.graph().getId(), actorUserId, taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public SessionTaskSnapshotResponse snapshot(String actorSessionId, Long actorUserId,
                                                boolean includeDeleted, boolean availableOnly) {
        Scope scope = resolve(actorSessionId, actorUserId, false);
        SessionTaskSnapshotResponse snapshot = taskService.snapshot(scope.graph().getId(), actorUserId, includeDeleted);
        if (!availableOnly) return snapshot;
        Set<String> activeTaskIds = attemptRepository.findByGraphSessionIdAndStatus(
                scope.graph().getId(), "ACTIVE").stream()
                .map(SessionTaskAttemptEntity::getTaskId).collect(java.util.stream.Collectors.toSet());
        List<SessionTaskResponse> available = snapshot.tasks().stream()
                .filter(task -> "pending".equals(task.status()))
                .filter(task -> !task.blocked())
                .filter(task -> !activeTaskIds.contains(task.taskId()))
                .toList();
        return new SessionTaskSnapshotResponse(snapshot.sessionId(), snapshot.summary(), available, snapshot.generatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public SessionTaskSnapshotResponse snapshotForSystem(String actorSessionId, boolean includeDeleted, int limit) {
        SessionEntity actor = sessionRepository.findById(actorSessionId)
                .orElseThrow(() -> hidden("sessionId"));
        Scope scope = resolve(actorSessionId, actor.getUserId(), false);
        SessionTaskSnapshotResponse snapshot = taskService.snapshotForSystem(
                scope.graph().getId(), includeDeleted, limit);
        if (snapshot.tasks().size() <= limit) return snapshot;
        return new SessionTaskSnapshotResponse(snapshot.sessionId(), snapshot.summary(),
                snapshot.tasks().subList(0, Math.max(1, limit)), snapshot.generatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamTaskRuntimeView> runtime(String actorSessionId, Long actorUserId, String taskId) {
        Scope scope = resolve(actorSessionId, actorUserId, false);
        if (!scope.team()) return Optional.empty();
        requireTask(scope, taskId);
        SessionTaskAttemptEntity attempt = attemptRepository.findByTaskIdOrderByAttemptNoDesc(taskId)
                .stream().findFirst().orElse(null);
        return Optional.of(new TeamTaskRuntimeView(scope.collabRun().getCollabRunId(), scope.graph().getId(),
                attempt == null ? null : attempt.getId(), attempt == null ? null : attempt.getAttemptNo(),
                attempt == null ? null : attempt.getWorkerSessionId(), attempt == null ? null : attempt.getStatus(),
                attempt == null ? null : attempt.getHeartbeatAt(), attempt == null ? null : attempt.getExpiresAt()));
    }

    @Transactional
    public void handleLoopFinished(String workerSessionId, String finalStatus) {
        if ("waiting_user".equals(finalStatus)) return;
        for (SessionTaskAttemptEntity seed :
                attemptRepository.findByWorkerSessionIdAndStatus(workerSessionId, "ACTIVE")) {
            recoverAttempt(seed.getId(), finalStatus, false);
        }
    }

    @Transactional
    public void maintainAttempt(String attemptId) {
        SessionTaskAttemptEntity seed = attemptRepository.findByIdAndStatus(attemptId, "ACTIVE").orElse(null);
        if (seed == null) return;
        SessionEntity graph = sessionRepository.findByIdForUpdate(seed.getGraphSessionId()).orElse(null);
        if (graph == null) return;
        SessionTaskAttemptEntity active = attemptRepository.findByIdAndStatus(attemptId, "ACTIVE").orElse(null);
        if (active == null) return;
        SessionEntity worker = sessionRepository.findById(active.getWorkerSessionId()).orElse(null);
        Instant now = Instant.now(clock);
        String runtimeStatus = worker == null ? "missing" : worker.getRuntimeStatus();
        if ("running".equals(runtimeStatus) && cancellationRegistry.isRunning(active.getWorkerSessionId())) {
            active.setHeartbeatAt(now);
            active.setExpiresAt(now.plus(LEASE_DURATION));
            attemptRepository.save(active);
            return;
        }
        if ("waiting_user".equals(runtimeStatus) && now.isBefore(active.getExpiresAt())) return;
        boolean expired = !now.isBefore(active.getExpiresAt());
        recoverAttemptLocked(active, graph, runtimeStatus, expired);
    }

    List<String> activeAttemptIds() {
        return attemptRepository.findIdsByStatusOrderByExpiresAtAsc("ACTIVE");
    }

    private void recoverAttempt(String attemptId, String finalStatus, boolean expired) {
        SessionTaskAttemptEntity seed = attemptRepository.findByIdAndStatus(attemptId, "ACTIVE").orElse(null);
        if (seed == null) return;
        SessionEntity graph = sessionRepository.findByIdForUpdate(seed.getGraphSessionId()).orElse(null);
        if (graph == null) return;
        SessionTaskAttemptEntity active = attemptRepository.findByIdAndStatus(attemptId, "ACTIVE").orElse(null);
        if (active == null) return;
        recoverAttemptLocked(active, graph, finalStatus, expired);
    }

    private void recoverAttemptLocked(SessionTaskAttemptEntity active, SessionEntity graph,
                                      String runtimeStatus, boolean expired) {
        CollabRunEntity run = collabRunRepository.findById(active.getCollabRunId()).orElse(null);
        SessionTaskEntity task = taskRepository.findByIdAndSessionId(active.getTaskId(), graph.getId()).orElse(null);
        if (run == null || task == null) return;
        SessionEntity worker = sessionRepository.findById(active.getWorkerSessionId()).orElse(null);
        Scope scope = new Scope(worker == null ? graph : worker, graph, run,
                graph.getId().equals(active.getWorkerSessionId()));
        String reason = recoveryReason(runtimeStatus, expired);
        String attemptStatus = "error".equals(runtimeStatus) ? "FAILED" : expired ? "EXPIRED" : "RELEASED";
        String eventType = "FAILED".equals(attemptStatus) ? "TASK_FAILED" : "TASK_RELEASED";
        String oldStatus = task.getStatus();

        String recoveredStatus = task.getStatus();
        if ("in_progress".equals(task.getStatus())) {
            taskService.update(graph.getId(), graph.getUserId(), task.getId(), task.getVersion(),
                    new SessionTaskService.UpdateCommand(null, null, null, "pending",
                            true, null, false, null,
                            List.of(), List.of(), List.of(), List.of()));
            recoveredStatus = "pending";
            if (!active.getWorkerSessionId().equals(task.getOwner())) {
                attemptStatus = "FAILED";
                eventType = "TASK_FAILED";
                reason = "STATE_MISMATCH";
            }
        } else if (!TERMINAL.contains(task.getStatus())) {
            attemptStatus = "FAILED";
            eventType = "TASK_FAILED";
            reason = "STATE_MISMATCH";
        }
        finishAttempt(active, attemptStatus, reason);
        SessionTaskEventEntity recovered = record(scope, task.getId(), active, eventType, worker,
                oldStatus, recoveredStatus, reason);
        if ("pending".equals(recoveredStatus)) {
            publishAvailable(scope, recovered,
                    taskService.snapshotForSystem(graph.getId(), false, SessionTaskService.MAX_TASKS_PER_SESSION),
                    task.getId());
        }
    }

    private static String recoveryReason(String runtimeStatus, boolean expired) {
        if (expired) return "LEASE_EXPIRED";
        if (runtimeStatus == null || "missing".equals(runtimeStatus)) return "WORKER_MISSING";
        return switch (runtimeStatus) {
            case "error" -> "WORKER_ERROR";
            case "cancelled", "aborted_by_hook" -> "WORKER_CANCELLED";
            case "completed", "idle" -> "WORKER_EXITED_WITHOUT_COMPLETION";
            default -> "WORKER_NOT_RUNNING";
        };
    }

    private Scope resolve(String actorSessionId, Long actorUserId, boolean lockGraph) {
        if (actorSessionId == null || actorSessionId.isBlank() || actorUserId == null) {
            throw error("TASK_CONTEXT_REQUIRED", "Task tools require an authenticated session context", false,
                    "context", "Run the tool from an active SkillForge session");
        }
        SessionEntity actor = sessionRepository.findById(actorSessionId)
                .orElseThrow(() -> hidden("sessionId"));
        requireUser(actor, actorUserId);
        if (actor.getCollabRunId() == null || actor.getCollabRunId().isBlank()) {
            SessionEntity graph = lockGraph
                    ? sessionRepository.findByIdForUpdate(actorSessionId).orElseThrow(() -> hidden("sessionId"))
                    : actor;
            return new Scope(actor, graph, null, true);
        }
        CollabRunEntity run = collabRunRepository.findById(actor.getCollabRunId())
                .orElseThrow(() -> hidden("collabRunId"));
        if (lockGraph && !"RUNNING".equals(run.getStatus())) {
            throw error("TASK_TEAM_NOT_RUNNING", "Team run is not active", false,
                    "collabRunId", "Start a new Team run before changing tasks");
        }
        String leaderId = run.getLeaderSessionId();
        SessionEntity graph = (lockGraph ? sessionRepository.findByIdForUpdate(leaderId) : sessionRepository.findById(leaderId))
                .orElseThrow(() -> hidden("collabRunId"));
        requireUser(graph, actorUserId);
        if (!run.getCollabRunId().equals(graph.getCollabRunId())) {
            throw hidden("collabRunId");
        }
        return new Scope(actor, graph, run, actorSessionId.equals(leaderId));
    }

    private SessionTaskEntity requireTask(Scope scope, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw error("TASK_ID_REQUIRED", "taskId is required", false,
                    "taskId", "Call TaskList to find the task ID");
        }
        return taskRepository.findByIdAndSessionId(taskId.trim(), scope.graph().getId())
                .orElseThrow(() -> hidden("taskId"));
    }

    private void requireOwnerOrLeader(Scope scope, SessionTaskAttemptEntity active) {
        if (active == null) {
            throw error("TASK_ATTEMPT_MISSING", "Active task has no recoverable attempt", true,
                    "taskId", "Ask the Team leader to release and reclaim the task");
        }
        if (!scope.leader() && !scope.actor().getId().equals(active.getWorkerSessionId())) {
            throw error("TASK_NOT_OWNER", "Only the active owner or Team leader may update this task", false,
                    "taskId", "Choose a task owned by the current Worker");
        }
    }

    private void requireOwner(Scope scope, SessionTaskAttemptEntity active) {
        if (active == null) {
            throw error("TASK_ATTEMPT_MISSING", "Active task has no recoverable attempt", true,
                    "taskId", "Ask the Team leader to release and reclaim the task");
        }
        if (!scope.actor().getId().equals(active.getWorkerSessionId())) {
            throw error("TASK_NOT_OWNER", "Only the active owner may complete this task", false,
                    "taskId", "Release the task or ask its current Worker to complete it");
        }
    }

    private SessionTaskAttemptEntity createAttempt(Scope scope, String taskId) {
        Instant now = Instant.now(clock);
        SessionTaskAttemptEntity attempt = new SessionTaskAttemptEntity();
        attempt.setId(UUID.randomUUID().toString());
        attempt.setTaskId(taskId);
        attempt.setGraphSessionId(scope.graph().getId());
        attempt.setCollabRunId(scope.collabRun().getCollabRunId());
        attempt.setAttemptNo(attemptRepository.findMaxAttemptNo(taskId) + 1);
        attempt.setWorkerSessionId(scope.actor().getId());
        attempt.setWorkerAgentId(scope.actor().getAgentId());
        attempt.setLeaseToken(UUID.randomUUID().toString());
        attempt.setStatus("ACTIVE");
        attempt.setLeasedAt(now);
        attempt.setHeartbeatAt(now);
        attempt.setExpiresAt(now.plus(LEASE_DURATION));
        return attemptRepository.saveAndFlush(attempt);
    }

    private void finishAttempt(SessionTaskAttemptEntity attempt, String status, String reason) {
        if (attempt == null) return;
        attempt.setStatus(status);
        attempt.setReasonCode(reason);
        attempt.setFinishedAt(Instant.now(clock));
        attemptRepository.save(attempt);
    }

    private SessionTaskEventEntity record(Scope scope, String taskId, SessionTaskAttemptEntity attempt,
                                          String type, SessionEntity actor, String from, String to, String reason) {
        SessionTaskEventEntity event = new SessionTaskEventEntity();
        event.setTaskId(taskId);
        event.setGraphSessionId(scope.graph().getId());
        event.setCollabRunId(scope.collabRun().getCollabRunId());
        event.setAttemptId(attempt == null ? null : attempt.getId());
        event.setEventType(type);
        event.setActorSessionId(actor == null ? null : actor.getId());
        event.setActorAgentId(actor == null ? null : actor.getAgentId());
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setReasonCode(reason);
        event.setCreatedAt(Instant.now(clock));
        return eventRepository.save(event);
    }

    private void recordGraphTransitions(Scope scope, SessionTaskSnapshotResponse before,
                                        SessionTaskSnapshotResponse after) {
        Map<String, SessionTaskResponse> beforeById = new LinkedHashMap<>();
        before.tasks().forEach(task -> beforeById.put(task.taskId(), task));
        for (SessionTaskResponse current : after.tasks()) {
            SessionTaskResponse prior = beforeById.get(current.taskId());
            if (prior == null || prior.blocked() == current.blocked() || TERMINAL.contains(current.status())) continue;
            String type = current.blocked() ? "TASK_BLOCKED" : "TASK_UNBLOCKED";
            SessionTaskEventEntity event = record(scope, current.taskId(), null, type, scope.actor(),
                    current.status(), current.status(), "DEPENDENCY_STATE_CHANGED");
            if (!current.blocked()) {
                publishAvailable(scope, event, after, current.taskId());
            }
        }
    }

    private void publishAvailable(Scope scope, SessionTaskEventEntity event,
                                  SessionTaskSnapshotResponse snapshot, String taskId) {
        SessionTaskResponse task = snapshot.tasks().stream()
                .filter(candidate -> taskId.equals(candidate.taskId()))
                .findFirst().orElse(null);
        if (task == null || task.blocked() || !"pending".equals(task.status())) return;
        eventRepository.flush();
        eventPublisher.publishEvent(new TeamTaskAvailableEvent(event.getId(),
                scope.collabRun().getCollabRunId(), scope.actor().getId(),
                task.taskId(), task.subject()));
    }

    private static SessionTaskService.UpdateCommand withRuntimeOwner(
            SessionTaskService.UpdateCommand command, String owner, boolean ownerPresent) {
        return new SessionTaskService.UpdateCommand(command.subject(), command.description(), command.activeForm(),
                command.status(), ownerPresent, owner, command.metadataPresent(), command.metadata(),
                command.addBlockedBy(), command.removeBlockedBy(), command.addBlocks(), command.removeBlocks());
    }

    private static void requireUser(SessionEntity session, Long userId) {
        if (!Long.valueOf(0L).equals(session.getUserId()) && !userId.equals(session.getUserId())) {
            throw hidden("sessionId");
        }
    }

    private static boolean hasItems(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private static SessionTaskException hidden(String field) {
        return error("TASK_NOT_FOUND", "Task session not found", false, field,
                "Use a task from the current Team");
    }

    private static SessionTaskException error(String code, String message, boolean retryable,
                                              String field, String action) {
        return new SessionTaskException(code, message, retryable, field, action);
    }
}
