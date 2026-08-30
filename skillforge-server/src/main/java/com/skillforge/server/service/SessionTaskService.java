package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.dto.SessionTaskResponse;
import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionTaskDependencyEntity;
import com.skillforge.server.entity.SessionTaskDependencyId;
import com.skillforge.server.entity.SessionTaskEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionTaskDependencyRepository;
import com.skillforge.server.repository.SessionTaskRepository;
import com.skillforge.server.service.event.SessionTasksChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class SessionTaskService implements TaskToolOperations {
    public static final int MAX_TASKS_PER_SESSION = 500;
    public static final int MAX_METADATA_BYTES = 16 * 1024;

    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed", "deleted");
    private static final Set<String> TERMINAL = Set.of("completed", "deleted");

    private final SessionRepository sessionRepository;
    private final SessionTaskRepository taskRepository;
    private final SessionTaskDependencyRepository dependencyRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SessionTaskService(SessionRepository sessionRepository,
                              SessionTaskRepository taskRepository,
                              SessionTaskDependencyRepository dependencyRepository,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher eventPublisher,
                              Clock clock) {
        this.sessionRepository = sessionRepository;
        this.taskRepository = taskRepository;
        this.dependencyRepository = dependencyRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public record CreateCommand(String subject, String description, String activeForm,
                                String owner, Map<String, Object> metadata,
                                List<String> blockedBy) {}

    public record CreateResult(SessionTaskResponse task, SessionTaskSnapshotResponse snapshot) {}

    public record UpdateCommand(String subject, String description, String activeForm,
                                String status, boolean ownerPresent, String owner,
                                boolean metadataPresent, Map<String, Object> metadata,
                                List<String> addBlockedBy, List<String> removeBlockedBy,
                                List<String> addBlocks, List<String> removeBlocks) {}

    private record TaskGraphState(boolean blocked, List<String> blockedBy, List<String> blocks) {}

    @Transactional
    public CreateResult create(String sessionId, Long actorUserId, CreateCommand command) {
        SessionEntity session = requireAccessibleSession(sessionId, actorUserId, true);
        requireCommand(command != null, "input", "TASK_INPUT_REQUIRED", "TaskCreate input is required");
        List<SessionTaskEntity> tasks = taskRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
        if (tasks.size() >= MAX_TASKS_PER_SESSION) {
            throw error("TASK_LIMIT_REACHED", "This session already has the maximum number of tasks",
                    false, "sessionId", "Complete or delete existing tasks before creating another task");
        }

        SessionTaskEntity task = new SessionTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setSessionId(sessionId);
        task.setUserId(session.getUserId());
        task.setSubject(requiredText(command.subject(), 256, "subject"));
        task.setDescription(requiredText(command.description(), 20_000, "description"));
        task.setActiveForm(command.activeForm() == null
                ? task.getSubject() : requiredText(command.activeForm(), 512, "activeForm"));
        task.setOwner(normalizeOwner(command.owner()));
        task.setMetadata(validateMetadata(command.metadata()));
        task.setStatus("pending");
        taskRepository.save(task);

        Set<String> blockedBy = normalizedIds(command.blockedBy(), "blockedBy");
        Map<String, SessionTaskEntity> byId = byId(tasks);
        byId.put(task.getId(), task);
        for (String blockerId : blockedBy) {
            requireDependencyTarget(byId, blockerId, "blockedBy");
            dependencyRepository.save(new SessionTaskDependencyEntity(sessionId, task.getId(), blockerId));
        }
        touchTasks(tasks.stream().filter(existing -> blockedBy.contains(existing.getId())).toList());
        taskRepository.flush();
        dependencyRepository.flush();
        publishChanged(sessionId);
        SessionTaskSnapshotResponse snapshot = snapshotInternal(sessionId, true, MAX_TASKS_PER_SESSION);
        SessionTaskResponse created = snapshot.tasks().stream()
                .filter(item -> task.getId().equals(item.taskId()))
                .findFirst()
                .orElseThrow(() -> error("TASK_CREATE_INCONSISTENT", "Created task is missing from snapshot",
                        true, "taskId", "Call TaskList to refresh task state"));
        return new CreateResult(created, snapshot);
    }

    @Transactional
    public SessionTaskSnapshotResponse update(String sessionId, Long actorUserId,
                                              String taskId, UpdateCommand command) {
        return update(sessionId, actorUserId, taskId, null, command);
    }

    @Transactional
    public SessionTaskSnapshotResponse update(String sessionId, Long actorUserId,
                                              String taskId, Long expectedRevision,
                                              UpdateCommand command) {
        requireAccessibleSession(sessionId, actorUserId, true);
        requireCommand(command != null, "input", "TASK_INPUT_REQUIRED", "TaskUpdate input is required");
        SessionTaskEntity task = requireTask(sessionId, taskId);
        if (expectedRevision != null && task.getVersion() != expectedRevision) {
            throw error("TASK_REVISION_CONFLICT", "Task revision changed concurrently", true,
                    "expectedRevision", "Call TaskGet and retry with the returned version");
        }
        List<SessionTaskEntity> tasks = taskRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
        Map<String, SessionTaskEntity> byId = byId(tasks);
        List<SessionTaskDependencyEntity> deps = new ArrayList<>(dependencyRepository.findBySessionId(sessionId));
        Map<String, TaskGraphState> graphBefore = graphStates(tasks, deps, byId);

        if (command.subject() != null) task.setSubject(requiredText(command.subject(), 256, "subject"));
        if (command.description() != null) task.setDescription(requiredText(command.description(), 20_000, "description"));
        if (command.activeForm() != null) task.setActiveForm(requiredText(command.activeForm(), 512, "activeForm"));
        String desiredOwner = command.ownerPresent() ? normalizeOwner(command.owner()) : normalizeOwner(task.getOwner());
        if (command.metadataPresent()) task.setMetadata(validateMetadata(command.metadata()));

        mutateDependencies(sessionId, task, command, byId, deps);
        dependencyRepository.flush();
        deps = new ArrayList<>(dependencyRepository.findBySessionId(sessionId));

        String oldStatus = task.getStatus();
        String targetStatus = command.status() == null ? oldStatus : normalizeStatus(command.status());
        if ("in_progress".equals(targetStatus) && isBlocked(task.getId(), deps, byId)) {
            throw error("TASK_BLOCKED", "Blocked task cannot be moved to in_progress", false,
                    "status", "Complete or delete every blocking task first");
        }

        if ("in_progress".equals(targetStatus)) {
            demoteOtherInProgress(tasks, task, desiredOwner);
            taskRepository.flush();
        }
        task.setOwner(desiredOwner);
        task.setStatus(targetStatus);

        demoteAllBlockedInProgress(tasks, deps, byId);
        touchChangedGraphTasks(tasks, graphBefore, deps, byId);

        try {
            taskRepository.saveAndFlush(task);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw error("TASK_CONFLICT", "Task state changed concurrently", true,
                    "version", "Call TaskGet and retry with the latest state");
        }
        publishChanged(sessionId);
        return snapshotInternal(sessionId, true, MAX_TASKS_PER_SESSION);
    }

    @Transactional(readOnly = true)
    public SessionTaskResponse get(String sessionId, Long actorUserId, String taskId) {
        requireAccessibleSession(sessionId, actorUserId, false);
        SessionTaskEntity task = requireTask(sessionId, taskId);
        return response(task, dependencyRepository.findBySessionId(sessionId),
                byId(taskRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId)));
    }

    @Transactional(readOnly = true)
    public SessionTaskSnapshotResponse snapshot(String sessionId, Long actorUserId, boolean includeDeleted) {
        requireAccessibleSession(sessionId, actorUserId, false);
        return snapshotInternal(sessionId, includeDeleted, MAX_TASKS_PER_SESSION);
    }

    @Override
    @Transactional(readOnly = true)
    public SessionTaskSnapshotResponse snapshot(String sessionId, Long actorUserId,
                                                boolean includeDeleted, boolean availableOnly) {
        return snapshot(sessionId, actorUserId, includeDeleted);
    }

    @Transactional(readOnly = true)
    public SessionTaskSnapshotResponse snapshotForSystem(String sessionId, boolean includeDeleted, int limit) {
        if (!sessionRepository.existsById(sessionId)) {
            throw error("SESSION_NOT_FOUND", "Session not found", false, "sessionId", "Use an existing session ID");
        }
        return snapshotInternal(sessionId, includeDeleted, limit);
    }

    private SessionTaskSnapshotResponse snapshotInternal(String sessionId, boolean includeDeleted, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_TASKS_PER_SESSION));
        List<SessionTaskEntity> all = taskRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
        List<SessionTaskDependencyEntity> deps = dependencyRepository.findBySessionId(sessionId);
        Map<String, SessionTaskEntity> byId = byId(all);
        List<SessionTaskResponse> output = all.stream()
                .filter(t -> includeDeleted || !"deleted".equals(t.getStatus()))
                .limit(safeLimit)
                .map(t -> response(t, deps, byId))
                .toList();

        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("total", (long) all.size());
        for (String status : List.of("pending", "in_progress", "completed", "deleted")) {
            summary.put(status, all.stream().filter(t -> status.equals(t.getStatus())).count());
        }
        summary.put("blocked", all.stream()
                .filter(t -> !TERMINAL.contains(t.getStatus()))
                .filter(t -> isBlocked(t.getId(), deps, byId)).count());
        return new SessionTaskSnapshotResponse(sessionId, summary, output, Instant.now(clock));
    }

    private void mutateDependencies(String sessionId, SessionTaskEntity task, UpdateCommand command,
                                    Map<String, SessionTaskEntity> byId,
                                    List<SessionTaskDependencyEntity> deps) {
        Set<SessionTaskDependencyId> removals = new HashSet<>();
        normalizedIds(command.removeBlockedBy(), "removeBlockedBy")
                .forEach(id -> removals.add(new SessionTaskDependencyId(task.getId(), id)));
        normalizedIds(command.removeBlocks(), "removeBlocks")
                .forEach(id -> removals.add(new SessionTaskDependencyId(id, task.getId())));
        if (!removals.isEmpty()) dependencyRepository.deleteAllById(removals);

        List<SessionTaskDependencyEntity> afterRemoval = deps.stream()
                .filter(d -> !removals.contains(new SessionTaskDependencyId(d.getTaskId(), d.getBlockedByTaskId())))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<SessionTaskDependencyEntity> candidates = new ArrayList<>();
        for (String blocker : normalizedIds(command.addBlockedBy(), "addBlockedBy")) {
            requireDependencyTarget(byId, blocker, "addBlockedBy");
            candidates.add(new SessionTaskDependencyEntity(sessionId, task.getId(), blocker));
        }
        for (String blocked : normalizedIds(command.addBlocks(), "addBlocks")) {
            requireDependencyTarget(byId, blocked, "addBlocks");
            candidates.add(new SessionTaskDependencyEntity(sessionId, blocked, task.getId()));
        }
        List<SessionTaskDependencyEntity> additions = new ArrayList<>();
        for (SessionTaskDependencyEntity addition : candidates) {
            if (addition.getTaskId().equals(addition.getBlockedByTaskId())) {
                throw error("TASK_SELF_DEPENDENCY", "A task cannot depend on itself", false,
                        "dependencies", "Remove the task's own ID from dependencies");
            }
            boolean exists = afterRemoval.stream().anyMatch(d -> d.getTaskId().equals(addition.getTaskId())
                    && d.getBlockedByTaskId().equals(addition.getBlockedByTaskId()));
            if (!exists) {
                afterRemoval.add(addition);
                additions.add(addition);
            }
        }
        if (hasCycle(afterRemoval)) {
            throw error("TASK_DEPENDENCY_CYCLE", "Task dependency would create a cycle", false,
                    "dependencies", "Remove one of the circular dependency edges");
        }
        dependencyRepository.saveAll(additions);
    }

    private static boolean hasCycle(List<SessionTaskDependencyEntity> deps) {
        Map<String, List<String>> edges = new HashMap<>();
        for (SessionTaskDependencyEntity d : deps) {
            edges.computeIfAbsent(d.getTaskId(), ignored -> new ArrayList<>()).add(d.getBlockedByTaskId());
        }
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String node : edges.keySet()) if (cycleDfs(node, edges, visited, active)) return true;
        return false;
    }

    private static boolean cycleDfs(String node, Map<String, List<String>> edges,
                                    Set<String> visited, Set<String> active) {
        if (active.contains(node)) return true;
        if (!visited.add(node)) return false;
        active.add(node);
        for (String next : edges.getOrDefault(node, List.of())) {
            if (cycleDfs(next, edges, visited, active)) return true;
        }
        active.remove(node);
        return false;
    }

    private void demoteOtherInProgress(List<SessionTaskEntity> tasks, SessionTaskEntity target, String owner) {
        for (SessionTaskEntity candidate : tasks) {
            if (!candidate.getId().equals(target.getId())
                    && "in_progress".equals(candidate.getStatus())
                    && java.util.Objects.equals(owner, normalizeOwner(candidate.getOwner()))) {
                candidate.setStatus("pending");
                taskRepository.save(candidate);
            }
        }
    }

    private void demoteAllBlockedInProgress(List<SessionTaskEntity> tasks,
                                            List<SessionTaskDependencyEntity> deps,
                                            Map<String, SessionTaskEntity> byId) {
        for (SessionTaskEntity candidate : tasks) {
            if ("in_progress".equals(candidate.getStatus()) && isBlocked(candidate.getId(), deps, byId)) {
                candidate.setStatus("pending");
                taskRepository.save(candidate);
            }
        }
    }

    private SessionTaskResponse response(SessionTaskEntity task,
                                         List<SessionTaskDependencyEntity> deps,
                                         Map<String, SessionTaskEntity> byId) {
        List<String> blockedBy = deps.stream().filter(d -> task.getId().equals(d.getTaskId()))
                .map(SessionTaskDependencyEntity::getBlockedByTaskId).sorted().toList();
        List<String> blocks = deps.stream().filter(d -> task.getId().equals(d.getBlockedByTaskId()))
                .map(SessionTaskDependencyEntity::getTaskId).sorted().toList();
        return new SessionTaskResponse(task.getId(), task.getSubject(), task.getDescription(),
                task.getActiveForm(), task.getStatus(), task.getOwner(),
                isBlocked(task.getId(), deps, byId), blockedBy, blocks,
                task.getCreatedAt(), task.getUpdatedAt(), task.getVersion());
    }

    private Map<String, TaskGraphState> graphStates(
            List<SessionTaskEntity> tasks,
            List<SessionTaskDependencyEntity> deps,
            Map<String, SessionTaskEntity> byId) {
        Map<String, TaskGraphState> states = new HashMap<>();
        for (SessionTaskEntity task : tasks) {
            states.put(task.getId(), graphState(task.getId(), deps, byId));
        }
        return states;
    }

    private TaskGraphState graphState(
            String taskId,
            List<SessionTaskDependencyEntity> deps,
            Map<String, SessionTaskEntity> byId) {
        List<String> blockedBy = deps.stream()
                .filter(dependency -> taskId.equals(dependency.getTaskId()))
                .map(SessionTaskDependencyEntity::getBlockedByTaskId)
                .sorted()
                .toList();
        List<String> blocks = deps.stream()
                .filter(dependency -> taskId.equals(dependency.getBlockedByTaskId()))
                .map(SessionTaskDependencyEntity::getTaskId)
                .sorted()
                .toList();
        return new TaskGraphState(isBlocked(taskId, deps, byId), blockedBy, blocks);
    }

    private void touchChangedGraphTasks(
            List<SessionTaskEntity> tasks,
            Map<String, TaskGraphState> before,
            List<SessionTaskDependencyEntity> deps,
            Map<String, SessionTaskEntity> byId) {
        touchTasks(tasks.stream()
                .filter(task -> !Objects.equals(before.get(task.getId()), graphState(task.getId(), deps, byId)))
                .toList());
    }

    private void touchTasks(List<SessionTaskEntity> tasks) {
        Instant now = Instant.now(clock);
        for (SessionTaskEntity task : tasks) {
            Instant current = task.getUpdatedAt();
            task.setUpdatedAt(current != null && !now.isAfter(current) ? current.plusNanos(1) : now);
            taskRepository.save(task);
        }
    }

    private static boolean isBlocked(String taskId, List<SessionTaskDependencyEntity> deps,
                                     Map<String, SessionTaskEntity> byId) {
        return isBlocked(taskId, deps, byId, new HashSet<>());
    }

    private static boolean isBlocked(String taskId, List<SessionTaskDependencyEntity> deps,
                                     Map<String, SessionTaskEntity> byId, Set<String> visited) {
        if (!visited.add(taskId)) return false;
        for (SessionTaskDependencyEntity dep : deps) {
            if (!taskId.equals(dep.getTaskId())) continue;
            SessionTaskEntity blocker = byId.get(dep.getBlockedByTaskId());
            if (blocker != null && (!TERMINAL.contains(blocker.getStatus())
                    || isBlocked(blocker.getId(), deps, byId, visited))) return true;
        }
        return false;
    }

    private SessionEntity requireAccessibleSession(String sessionId, Long actorUserId, boolean lock) {
        if (sessionId == null || sessionId.isBlank())
            throw error("SESSION_ID_REQUIRED", "sessionId is required", false, "sessionId", "Use the current session ID");
        if (actorUserId == null)
            throw error("USER_ID_REQUIRED", "userId is required", false, "userId", "Run inside an authenticated session");
        SessionEntity session = (lock ? sessionRepository.findByIdForUpdate(sessionId) : sessionRepository.findById(sessionId))
                .orElseThrow(() -> error("SESSION_NOT_FOUND", "Session not found", false, "sessionId", "Use an existing session ID"));
        if (!Long.valueOf(0L).equals(session.getUserId()) && !actorUserId.equals(session.getUserId())) {
            throw error("TASK_NOT_FOUND", "Task session not found", false, "sessionId", "Use a session you can access");
        }
        return session;
    }

    private SessionTaskEntity requireTask(String sessionId, String taskId) {
        if (taskId == null || taskId.isBlank())
            throw error("TASK_ID_REQUIRED", "taskId is required", false, "taskId", "Call TaskList to find the task ID");
        return taskRepository.findByIdAndSessionId(taskId.trim(), sessionId)
                .orElseThrow(() -> error("TASK_NOT_FOUND", "Task not found", false, "taskId", "Call TaskList to refresh task IDs"));
    }

    private void requireDependencyTarget(Map<String, SessionTaskEntity> byId, String id, String field) {
        SessionTaskEntity target = byId.get(id);
        if (target == null || "deleted".equals(target.getStatus()))
            throw error("TASK_DEPENDENCY_NOT_FOUND", "Dependency task not found", false, field, "Use an active task ID from TaskList");
    }

    private Map<String, Object> validateMetadata(Map<String, Object> metadata) {
        Map<String, Object> value = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        try {
            if (objectMapper.writeValueAsBytes(value).length > MAX_METADATA_BYTES)
                throw error("TASK_METADATA_TOO_LARGE", "metadata exceeds 16 KiB", false, "metadata", "Store only compact task metadata");
        } catch (SessionTaskException e) {
            throw e;
        } catch (Exception e) {
            throw error("TASK_METADATA_INVALID", "metadata is not JSON serializable", false, "metadata", "Use a JSON object");
        }
        return value;
    }

    private static String requiredText(String value, int max, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty())
            throw error("TASK_FIELD_REQUIRED", field + " is required", false, field, "Provide a non-empty " + field);
        if (normalized.length() > max)
            throw error("TASK_FIELD_TOO_LONG", field + " exceeds " + max + " characters", false, field, "Shorten " + field);
        return normalized;
    }

    private static String normalizeOwner(String owner) {
        if (owner == null) return null;
        String normalized = owner.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 128)
            throw error("TASK_FIELD_TOO_LONG", "owner exceeds 128 characters", false, "owner", "Shorten owner");
        return normalized;
    }

    private static String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(normalized))
            throw error("TASK_STATUS_INVALID", "Unknown task status: " + status, false, "status", "Use pending, in_progress, completed, or deleted");
        return normalized;
    }

    private static Set<String> normalizedIds(Collection<String> ids, String field) {
        if (ids == null || ids.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank())
                throw error("TASK_ID_INVALID", field + " contains a blank task ID", false, field, "Remove blank IDs");
            out.add(id.trim());
        }
        return out;
    }

    private static Map<String, SessionTaskEntity> byId(List<SessionTaskEntity> tasks) {
        Map<String, SessionTaskEntity> result = new LinkedHashMap<>();
        for (SessionTaskEntity task : tasks) result.put(task.getId(), task);
        return result;
    }

    private void publishChanged(String sessionId) {
        eventPublisher.publishEvent(new SessionTasksChangedEvent(sessionId));
    }

    private static void requireCommand(boolean condition, String field, String code, String message) {
        if (!condition) throw error(code, message, false, field, "Provide the required input");
    }

    private static SessionTaskException error(String code, String message, boolean retryable,
                                              String failedField, String suggestedAction) {
        return new SessionTaskException(code, message, retryable, failedField, suggestedAction);
    }
}
