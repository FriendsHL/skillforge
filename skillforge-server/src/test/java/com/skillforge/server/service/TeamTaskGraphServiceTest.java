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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import com.skillforge.server.service.event.TeamTaskAvailableEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamTaskGraphServiceTest {
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final CollabRunRepository collabs = mock(CollabRunRepository.class);
    private final SessionTaskRepository tasks = mock(SessionTaskRepository.class);
    private final SessionTaskAttemptRepository attempts = mock(SessionTaskAttemptRepository.class);
    private final SessionTaskEventRepository events = mock(SessionTaskEventRepository.class);
    private final SessionTaskService taskService = mock(SessionTaskService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final CancellationRegistry cancellationRegistry = mock(CancellationRegistry.class);
    private TeamTaskGraphService service;

    @BeforeEach
    void setUp() {
        SessionEntity leader = session("leader", 7L, 3L, "team-1");
        SessionEntity worker = session("worker", 7L, 9L, "team-1");
        CollabRunEntity run = new CollabRunEntity();
        run.setCollabRunId("team-1"); run.setLeaderSessionId("leader"); run.setStatus("RUNNING");
        when(sessions.findById("leader")).thenReturn(Optional.of(leader));
        when(sessions.findById("worker")).thenReturn(Optional.of(worker));
        when(sessions.findByIdForUpdate("leader")).thenReturn(Optional.of(leader));
        when(collabs.findById("team-1")).thenReturn(Optional.of(run));
        when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attempts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new TeamTaskGraphService(sessions, collabs, tasks, attempts, events,
                taskService, publisher,
                cancellationRegistry,
                Clock.fixed(Instant.parse("2026-08-12T06:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void workerReadsLeaderGraphWithoutSupplyingGraphId() {
        SessionTaskResponse response = response("t1", "pending", null, false, 4L);
        when(taskService.get("leader", 7L, "t1")).thenReturn(response);

        assertThat(service.get("worker", 7L, "t1")).isEqualTo(response);
        verify(taskService).get("leader", 7L, "t1");
    }

    @Test
    void workerCannotCreateOrReshapeSharedGraph() {
        SessionTaskService.CreateCommand create = new SessionTaskService.CreateCommand(
                "S", "D", "A", null, Map.of(), List.of());
        assertThatThrownBy(() -> service.create("worker", 7L, create))
                .isInstanceOfSatisfying(SessionTaskException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_LEADER_REQUIRED"));

        SessionTaskEntity row = task("t1", "pending", null);
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        when(taskService.snapshot("leader", 7L, true))
                .thenReturn(snapshot(response("t1", "pending", null, false, 0L)));
        SessionTaskService.UpdateCommand dependencyEdit = new SessionTaskService.UpdateCommand(
                null, null, null, null, false, null, false, null,
                List.of("blocker"), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> service.update("worker", 7L, "t1", 0L, dependencyEdit))
                .isInstanceOfSatisfying(SessionTaskException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_LEADER_REQUIRED"));
    }

    @Test
    void leaderCannotIndirectlyReshapeDagWhileAnotherTaskIsActive() {
        SessionTaskEntity row = task("t1", "pending", null);
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        when(taskService.snapshot("leader", 7L, true))
                .thenReturn(snapshot(response("t1", "pending", null, false, 0L)));
        when(attempts.findByGraphSessionIdAndStatus("leader", "ACTIVE"))
                .thenReturn(List.of(activeAttempt()));
        SessionTaskService.UpdateCommand dependencyEdit = new SessionTaskService.UpdateCommand(
                null, null, null, null, false, null, false, null,
                List.of(), List.of(), List.of("other-active-task"), List.of());

        assertThatThrownBy(() -> service.update("leader", 7L, "t1", 0L, dependencyEdit))
                .isInstanceOfSatisfying(SessionTaskException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_ACTIVE_DEPENDENCY_CHANGE"));
        verify(taskService, never()).update(anyString(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void teamUpdateRequiresExpectedRevisionBeforeMutation() {
        assertThatThrownBy(() -> service.update("worker", 7L, "t1", null,
                update("in_progress")))
                .isInstanceOfSatisfying(SessionTaskException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_REVISION_REQUIRED"));
        verifyNoInteractions(taskService);
    }

    @Test
    void workerClaimUsesServerIdentityAndCreatesActiveAttempt() {
        SessionTaskEntity row = task("t1", "pending", null);
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        SessionTaskSnapshotResponse before = snapshot(response("t1", "pending", null, false, 0L));
        SessionTaskSnapshotResponse after = snapshot(response("t1", "in_progress", "worker", false, 1L));
        when(taskService.snapshot("leader", 7L, true)).thenReturn(before);
        when(taskService.update(eq("leader"), eq(7L), eq("t1"), eq(0L), any())).thenReturn(after);
        when(attempts.findByTaskIdAndStatus("t1", "ACTIVE")).thenReturn(Optional.empty());
        when(attempts.findByWorkerSessionIdAndStatus("worker", "ACTIVE")).thenReturn(List.of());
        when(attempts.findMaxAttemptNo("t1")).thenReturn(0);

        service.update("worker", 7L, "t1", 0L, update("in_progress"));

        ArgumentCaptor<SessionTaskService.UpdateCommand> command =
                ArgumentCaptor.forClass(SessionTaskService.UpdateCommand.class);
        verify(taskService).update(eq("leader"), eq(7L), eq("t1"), eq(0L), command.capture());
        assertThat(command.getValue().ownerPresent()).isTrue();
        assertThat(command.getValue().owner()).isEqualTo("worker");
        ArgumentCaptor<SessionTaskAttemptEntity> attempt =
                ArgumentCaptor.forClass(SessionTaskAttemptEntity.class);
        verify(attempts).saveAndFlush(attempt.capture());
        assertThat(attempt.getValue().getWorkerSessionId()).isEqualTo("worker");
        assertThat(attempt.getValue().getWorkerAgentId()).isEqualTo(9L);
        assertThat(attempt.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(attempt.getValue().getExpiresAt())
                .isEqualTo(Instant.parse("2026-08-12T06:02:00Z"));
        verify(events).save(argThat(event -> "TASK_CLAIMED".equals(event.getEventType())));
    }

    @Test
    void nonOwnerCannotCompleteClaimedTask() {
        SessionEntity other = session("other", 7L, 10L, "team-1");
        when(sessions.findById("other")).thenReturn(Optional.of(other));
        SessionTaskEntity row = task("t1", "in_progress", "worker");
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        when(taskService.snapshot("leader", 7L, true))
                .thenReturn(snapshot(response("t1", "in_progress", "worker", false, 0L)));
        SessionTaskAttemptEntity active = new SessionTaskAttemptEntity();
        active.setWorkerSessionId("worker"); active.setStatus("ACTIVE");
        when(attempts.findByTaskIdAndStatus("t1", "ACTIVE")).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.update("other", 7L, "t1", 0L, update("completed")))
                .isInstanceOfSatisfying(SessionTaskException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_NOT_OWNER"));
        verify(taskService, never()).update(anyString(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void leaderCannotCompleteTaskClaimedByWorker() {
        SessionTaskEntity row = task("t1", "in_progress", "worker");
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        when(taskService.snapshot("leader", 7L, true))
                .thenReturn(snapshot(response("t1", "in_progress", "worker", false, 0L)));
        SessionTaskAttemptEntity active = activeAttempt();
        when(attempts.findByTaskIdAndStatus("t1", "ACTIVE")).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.update("leader", 7L, "t1", 0L, update("completed")))
                .isInstanceOfSatisfying(SessionTaskException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_NOT_OWNER"));
        verify(taskService, never()).update(anyString(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void leaderDeletingClaimedTaskClosesAttemptAndClearsOwner() {
        SessionTaskEntity row = task("t1", "in_progress", "worker");
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        SessionTaskSnapshotResponse before = snapshot(response("t1", "in_progress", "worker", false, 0L));
        SessionTaskSnapshotResponse after = snapshot(response("t1", "deleted", null, false, 1L));
        when(taskService.snapshot("leader", 7L, true)).thenReturn(before);
        when(taskService.update(eq("leader"), eq(7L), eq("t1"), eq(0L), any())).thenReturn(after);
        SessionTaskAttemptEntity active = activeAttempt();
        when(attempts.findByTaskIdAndStatus("t1", "ACTIVE")).thenReturn(Optional.of(active));

        service.update("leader", 7L, "t1", 0L, update("deleted"));

        ArgumentCaptor<SessionTaskService.UpdateCommand> command =
                ArgumentCaptor.forClass(SessionTaskService.UpdateCommand.class);
        verify(taskService).update(eq("leader"), eq(7L), eq("t1"), eq(0L), command.capture());
        assertThat(command.getValue().ownerPresent()).isTrue();
        assertThat(command.getValue().owner()).isNull();
        assertThat(active.getStatus()).isEqualTo("RELEASED");
        assertThat(active.getReasonCode()).isEqualTo("LEADER_DELETED_TASK");
    }

    @Test
    void explicitReleasePublishesAvailableEventAfterPersistedAudit() {
        SessionTaskEntity row = task("t1", "in_progress", "worker");
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        SessionTaskSnapshotResponse before = snapshot(response("t1", "in_progress", "worker", false, 0L));
        SessionTaskSnapshotResponse after = snapshot(response("t1", "pending", null, false, 1L));
        when(taskService.snapshot("leader", 7L, true)).thenReturn(before);
        when(taskService.update(eq("leader"), eq(7L), eq("t1"), eq(0L), any())).thenReturn(after);
        SessionTaskAttemptEntity active = activeAttempt();
        when(attempts.findByTaskIdAndStatus("t1", "ACTIVE")).thenReturn(Optional.of(active));
        when(events.save(any())).thenAnswer(invocation -> {
            SessionTaskEventEntity event = invocation.getArgument(0);
            event.setId(42L);
            return event;
        });

        service.update("worker", 7L, "t1", 0L, update("pending"));

        verify(events).flush();
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(TeamTaskAvailableEvent.class, available -> {
            assertThat(available.eventId()).isEqualTo(42L);
            assertThat(available.taskId()).isEqualTo("t1");
        });
    }

    @Test
    void availableOnlyReturnsPendingUnblockedTasksWithoutActiveAttempt() {
        SessionTaskSnapshotResponse all = snapshot(
                response("ready", "pending", null, false, 0L),
                response("blocked", "pending", null, true, 0L),
                response("running", "in_progress", "worker", false, 0L));
        when(taskService.snapshot("leader", 7L, false)).thenReturn(all);
        when(attempts.findByGraphSessionIdAndStatus("leader", "ACTIVE")).thenReturn(List.of());

        SessionTaskSnapshotResponse available = service.snapshot("worker", 7L, false, true);

        assertThat(available.tasks()).extracting(SessionTaskResponse::taskId).containsExactly("ready");
    }

    @Test
    void systemSnapshotForWorkerReloadsLeaderGraphAfterCompact() {
        SessionTaskSnapshotResponse leaderSnapshot = snapshot(response("t1", "pending", null, false, 0L));
        when(taskService.snapshotForSystem("leader", false, 20)).thenReturn(leaderSnapshot);

        SessionTaskSnapshotResponse recovered = service.snapshotForSystem("worker", false, 20);

        assertThat(recovered).isSameAs(leaderSnapshot);
        verify(taskService).snapshotForSystem("leader", false, 20);
    }

    @Test
    void runningWorkerRenewsLeaseWithoutReadingOrMutatingTask() {
        SessionTaskAttemptEntity active = activeAttempt();
        when(attempts.findByIdAndStatus("attempt-1", "ACTIVE"))
                .thenReturn(Optional.of(active), Optional.of(active));
        when(cancellationRegistry.isRunning("worker")).thenReturn(true);

        service.maintainAttempt("attempt-1");

        assertThat(active.getHeartbeatAt()).isEqualTo(Instant.parse("2026-08-12T06:00:00Z"));
        assertThat(active.getExpiresAt()).isEqualTo(Instant.parse("2026-08-12T06:02:00Z"));
        verify(attempts).save(active);
        verifyNoInteractions(taskService);
    }

    @Test
    void staleDatabaseRunningStateDoesNotRenewZombieWorker() {
        SessionTaskAttemptEntity active = activeAttempt();
        SessionTaskEntity row = task("t1", "in_progress", "worker");
        when(attempts.findByIdAndStatus("attempt-1", "ACTIVE"))
                .thenReturn(Optional.of(active), Optional.of(active));
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        when(cancellationRegistry.isRunning("worker")).thenReturn(false);
        when(taskService.update(eq("leader"), eq(7L), eq("t1"), eq(0L), any()))
                .thenReturn(snapshot(response("t1", "pending", null, false, 1L)));
        when(taskService.snapshotForSystem("leader", false, SessionTaskService.MAX_TASKS_PER_SESSION))
                .thenReturn(snapshot(response("t1", "pending", null, false, 1L)));

        service.maintainAttempt("attempt-1");

        assertThat(active.getStatus()).isEqualTo("RELEASED");
        assertThat(active.getReasonCode()).isEqualTo("WORKER_NOT_RUNNING");
        verify(taskService).update(eq("leader"), eq(7L), eq("t1"), eq(0L), any());
    }

    @Test
    void recoveryRepairsInProgressOwnerMismatchInsteadOfLeavingStuckTask() {
        SessionTaskAttemptEntity active = activeAttempt();
        SessionTaskEntity row = task("t1", "in_progress", "other-worker");
        when(attempts.findByIdAndStatus("attempt-1", "ACTIVE"))
                .thenReturn(Optional.of(active), Optional.of(active));
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        when(cancellationRegistry.isRunning("worker")).thenReturn(false);
        when(taskService.update(eq("leader"), eq(7L), eq("t1"), eq(0L), any()))
                .thenReturn(snapshot(response("t1", "pending", null, false, 1L)));
        when(taskService.snapshotForSystem("leader", false, SessionTaskService.MAX_TASKS_PER_SESSION))
                .thenReturn(snapshot(response("t1", "pending", null, false, 1L)));

        service.maintainAttempt("attempt-1");

        verify(taskService).update(eq("leader"), eq(7L), eq("t1"), eq(0L), any());
        assertThat(active.getStatus()).isEqualTo("FAILED");
        assertThat(active.getReasonCode()).isEqualTo("STATE_MISMATCH");
        verify(events).save(argThat(event -> "TASK_FAILED".equals(event.getEventType())
                && "pending".equals(event.getToStatus())));
    }

    @Test
    void completedLoopWithoutTaskCompletionReleasesClaim() {
        SessionTaskAttemptEntity active = activeAttempt();
        SessionTaskEntity row = task("t1", "in_progress", "worker");
        when(attempts.findByWorkerSessionIdAndStatus("worker", "ACTIVE")).thenReturn(List.of(active));
        when(attempts.findByIdAndStatus("attempt-1", "ACTIVE"))
                .thenReturn(Optional.of(active), Optional.of(active));
        when(tasks.findByIdAndSessionId("t1", "leader")).thenReturn(Optional.of(row));
        when(taskService.update(eq("leader"), eq(7L), eq("t1"), eq(0L), any()))
                .thenAnswer(invocation -> {
                    // Real JPA updates the already-managed row in the same persistence context.
                    // Preserve this shape so owner validation cannot accidentally inspect the
                    // cleared post-recovery owner instead of the pre-recovery owner.
                    row.setStatus("pending");
                    row.setOwner(null);
                    return snapshot(response("t1", "pending", null, false, 1L));
                });
        when(taskService.snapshotForSystem("leader", false, SessionTaskService.MAX_TASKS_PER_SESSION))
                .thenReturn(snapshot(response("t1", "pending", null, false, 1L)));

        service.handleLoopFinished("worker", "completed");

        ArgumentCaptor<SessionTaskService.UpdateCommand> command =
                ArgumentCaptor.forClass(SessionTaskService.UpdateCommand.class);
        verify(taskService).update(eq("leader"), eq(7L), eq("t1"), eq(0L), command.capture());
        assertThat(command.getValue().status()).isEqualTo("pending");
        assertThat(command.getValue().ownerPresent()).isTrue();
        assertThat(command.getValue().owner()).isNull();
        assertThat(active.getStatus()).isEqualTo("RELEASED");
        assertThat(active.getReasonCode()).isEqualTo("WORKER_EXITED_WITHOUT_COMPLETION");
        verify(events).save(argThat(event -> "TASK_RELEASED".equals(event.getEventType())
                && "in_progress".equals(event.getFromStatus())
                && "pending".equals(event.getToStatus())));
    }

    @Test
    void waitingUserLoopDoesNotReleaseLeaseImmediately() {
        service.handleLoopFinished("worker", "waiting_user");
        verifyNoInteractions(attempts);
    }

    private static SessionEntity session(String id, Long userId, Long agentId, String collabRunId) {
        SessionEntity session = new SessionEntity();
        session.setId(id); session.setUserId(userId); session.setAgentId(agentId);
        session.setCollabRunId(collabRunId); session.setRuntimeStatus("running");
        return session;
    }

    private static SessionTaskEntity task(String id, String status, String owner) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setId(id); task.setSessionId("leader"); task.setUserId(7L);
        task.setSubject(id); task.setDescription(id); task.setActiveForm(id);
        task.setStatus(status); task.setOwner(owner);
        return task;
    }

    private static SessionTaskAttemptEntity activeAttempt() {
        SessionTaskAttemptEntity active = new SessionTaskAttemptEntity();
        active.setId("attempt-1"); active.setTaskId("t1"); active.setGraphSessionId("leader");
        active.setCollabRunId("team-1"); active.setWorkerSessionId("worker");
        active.setWorkerAgentId(9L); active.setAttemptNo(1); active.setStatus("ACTIVE");
        active.setHeartbeatAt(Instant.parse("2026-08-12T05:59:00Z"));
        active.setExpiresAt(Instant.parse("2026-08-12T06:01:00Z"));
        return active;
    }

    private static SessionTaskService.UpdateCommand update(String status) {
        return new SessionTaskService.UpdateCommand(null, null, null, status,
                false, null, false, Map.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static SessionTaskSnapshotResponse snapshot(SessionTaskResponse... tasks) {
        return new SessionTaskSnapshotResponse("leader", Map.of("total", (long) tasks.length),
                List.of(tasks), Instant.EPOCH);
    }

    private static SessionTaskResponse response(String id, String status, String owner,
                                                boolean blocked, long version) {
        return new SessionTaskResponse(id, id, id, id, status, owner, blocked,
                List.of(), List.of(), Instant.EPOCH, Instant.EPOCH, version);
    }
}
