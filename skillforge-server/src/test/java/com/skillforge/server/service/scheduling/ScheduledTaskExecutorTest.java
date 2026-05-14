package com.skillforge.server.service.scheduling;

import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.ScheduledTaskService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledTaskExecutor}. Mocks all collaborators —
 * focuses on branching logic (skip-if-running, new vs reuse session, terminal
 * status mapping, channel push isolation, one-shot completion).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduledTaskExecutor")
class ScheduledTaskExecutorTest {

    @Mock private ScheduledTaskRepository repository;
    @Mock private ScheduledTaskService scheduledTaskService;
    @Mock private SessionService sessionService;
    @Mock private ChatService chatService;
    @Mock private UserTaskScheduler userTaskScheduler;
    @Mock private SchedulerChannelDispatcher channelDispatcher;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UserTaskScheduler> schedulerProvider =
            (ObjectProvider<UserTaskScheduler>) org.mockito.Mockito.mock(ObjectProvider.class);

    private ScheduledTaskExecutor executor;

    @BeforeEach
    void setUp() {
        when(schedulerProvider.getObject()).thenReturn(userTaskScheduler);
        when(schedulerProvider.getIfAvailable()).thenReturn(userTaskScheduler);
        executor = new ScheduledTaskExecutor(
                repository, scheduledTaskService, sessionService, chatService,
                schedulerProvider, channelDispatcher, "https://dash.test");
    }

    private ScheduledTaskEntity newCronTask(long id) {
        ScheduledTaskEntity t = new ScheduledTaskEntity();
        t.setId(id);
        t.setName("daily");
        t.setCreatorUserId(7L);
        t.setAgentId(42L);
        t.setCronExpr("0 0 9 * * *");
        t.setTimezone("Asia/Shanghai");
        t.setPromptTemplate("hello");
        t.setSessionMode(ScheduledTaskEntity.SESSION_MODE_NEW);
        t.setEnabled(true);
        t.setStatus(ScheduledTaskEntity.STATUS_IDLE);
        t.setConcurrencyPolicy("skip-if-running");
        return t;
    }

    private ScheduledTaskEntity newReuseTask(long id) {
        ScheduledTaskEntity t = newCronTask(id);
        t.setSessionMode(ScheduledTaskEntity.SESSION_MODE_REUSE);
        return t;
    }

    private ScheduledTaskRunEntity runFor(long taskId, long runId, boolean manual) {
        ScheduledTaskRunEntity r = new ScheduledTaskRunEntity();
        r.setId(runId);
        r.setTaskId(taskId);
        r.setManual(manual);
        r.setStatus(ScheduledTaskRunEntity.STATUS_RUNNING);
        return r;
    }

    private SessionEntity newSession(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(42L);
        return s;
    }

    @Test
    @DisplayName("fire skips when scheduler reports running (INV-4)")
    void fire_skipsWhenAlreadyRunning() {
        ScheduledTaskEntity task = newCronTask(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(1L)).thenReturn(false);

        executor.fire(1L, false);

        verify(scheduledTaskService).markRunSkipped(1L, false);
        verify(scheduledTaskService, never()).markRunStart(anyLong(), anyBoolean());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("fire (session_mode=new) creates fresh session and dispatches chatAsync")
    void fire_newSession_dispatches() {
        ScheduledTaskEntity task = newCronTask(2L);
        when(repository.findById(2L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(2L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(2L, false)).thenReturn(runFor(2L, 100L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-A"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.fire(2L, false);

        verify(scheduledTaskService).attachRunSession(100L, "sess-A");
        verify(chatService).chatAsync("sess-A", "hello", 7L, false);
        assertThat(executor.peekContext("sess-A")).isPresent();
    }

    @Test
    @DisplayName("fire (session_mode=reuse) creates session first time, reuses it second time")
    void fire_reuseSession_persistsAndReuses() {
        ScheduledTaskEntity task = newReuseTask(3L);
        when(repository.findById(3L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(3L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(3L, false))
                .thenReturn(runFor(3L, 200L, false))
                .thenReturn(runFor(3L, 201L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-reuse"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> {
            ScheduledTaskEntity saved = inv.getArgument(0);
            // Echo the persisted reused_session_id back so the second findById sees it.
            task.setReusedSessionId(saved.getReusedSessionId());
            return saved;
        });

        executor.fire(3L, false);
        // Pretend the loop hasn't finished yet — manually clear running for the second fire.
        when(userTaskScheduler.tryMarkRunning(3L)).thenReturn(true);
        executor.fire(3L, false);

        verify(sessionService, times(1)).createSession(7L, 42L);
        verify(chatService, times(2)).chatAsync(eq("sess-reuse"), anyString(), eq(7L), eq(false));
    }

    @Test
    @DisplayName("fire (session_mode=reuse) syncs reusedSessionId onto outer task ref before save (stale-entity overwrite guard)")
    void fire_reuseSession_syncsSessionIdOntoTaskBeforeSave() {
        // Regression for the prod bug where two consecutive fires of a reuse-mode
        // task both opened fresh sessions and the t_scheduled_task.reused_session_id
        // column stayed NULL: fire() reads `task` at the top, openSessionForTask
        // saves the new id on a separate `refreshed` instance, but the outer
        // task ref is stale (reusedSessionId=null) — the trailing save(task) at
        // the end of fire() emits a full JPA UPDATE and wipes the column. The fix
        // syncs the session id onto the outer ref before that save. This test
        // deliberately does NOT echo the persisted id back onto `task` in the
        // save mock — that echo trick is what masked the bug in the older test
        // case below; here we want the assertion to fail loudly without the fix.
        // CRITICAL: outer fire() and openSessionForTask each call findById and
        // get back separate managed entities in prod (Hibernate); using a
        // single shared instance here would let the openSessionForTask write
        // bleed onto the outer ref and mask the very bug we're testing. Two
        // independent instances reproduce the real JPA behavior.
        ScheduledTaskEntity outerTask = newReuseTask(11L);
        ScheduledTaskEntity innerTask = newReuseTask(11L);
        when(repository.findById(11L))
                .thenReturn(Optional.of(outerTask))   // fire() top
                .thenReturn(Optional.of(innerTask));  // openSessionForTask body
        when(userTaskScheduler.tryMarkRunning(11L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(11L, false)).thenReturn(runFor(11L, 1100L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-sync"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.fire(11L, false);

        // Two saves: (1) openSessionForTask saves `refreshed` with new
        // reused_session_id, (2) fire() saves outer `task` with last_fire_at +
        // status. The fix is that save (2) also carries the session id.
        ArgumentCaptor<ScheduledTaskEntity> saveCaptor = ArgumentCaptor.forClass(ScheduledTaskEntity.class);
        verify(repository, times(2)).save(saveCaptor.capture());
        ScheduledTaskEntity outerSave = saveCaptor.getAllValues().get(1);
        assertThat(outerSave.getReusedSessionId())
                .as("outer save must carry reused_session_id; otherwise the JPA full UPDATE wipes the column")
                .isEqualTo("sess-sync");
        assertThat(outerSave.getLastFireAt()).isNotNull();
        assertThat(outerSave.getStatus()).isEqualTo(ScheduledTaskEntity.STATUS_RUNNING);
    }

    @Test
    @DisplayName("fire (session_mode=new) does NOT mutate task.reusedSessionId on the outer save")
    void fire_newSession_doesNotTouchReusedSessionId() {
        // Symmetry guard for the reuse-mode fix: session_mode=new must leave
        // reused_session_id alone on the outer save (otherwise we'd start writing
        // ephemeral session ids on tasks the user explicitly opted out of reuse for).
        ScheduledTaskEntity task = newCronTask(12L);
        when(repository.findById(12L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(12L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(12L, false)).thenReturn(runFor(12L, 1200L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-new-only"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.fire(12L, false);

        // session_mode=new takes the second branch in openSessionForTask, which
        // does not save a `refreshed` row — so only the outer fire() save runs.
        ArgumentCaptor<ScheduledTaskEntity> saveCaptor = ArgumentCaptor.forClass(ScheduledTaskEntity.class);
        verify(repository, times(1)).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getReusedSessionId())
                .as("session_mode=new must never set reused_session_id")
                .isNull();
    }

    @Test
    @DisplayName("fire bypasses enabled flag when manual=true (INV-10)")
    void fire_manual_bypassesEnabled() {
        ScheduledTaskEntity task = newCronTask(4L);
        task.setEnabled(false);
        when(repository.findById(4L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(4L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(4L, true)).thenReturn(runFor(4L, 300L, true));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-M"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.fire(4L, true);

        verify(chatService).chatAsync("sess-M", "hello", 7L, false);
    }

    @Test
    @DisplayName("fire on disabled task (manual=false) does nothing — no run row, no dispatch")
    void fire_disabled_noOp() {
        ScheduledTaskEntity task = newCronTask(5L);
        task.setEnabled(false);
        when(repository.findById(5L)).thenReturn(Optional.of(task));

        executor.fire(5L, false);

        verify(userTaskScheduler, never()).tryMarkRunning(anyLong());
        verify(scheduledTaskService, never()).markRunStart(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("session finished (success) → mark success, push channel text, clear running")
    void onSessionFinished_success() {
        ScheduledTaskEntity task = newCronTask(6L);
        task.setStatus(ScheduledTaskEntity.STATUS_RUNNING);
        task.setChannelTarget("{\"channelType\":\"feishu\",\"channelId\":\"oc_x\"}");
        when(repository.findById(6L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(6L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(6L, false)).thenReturn(runFor(6L, 400L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-OK"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        executor.fire(6L, false);

        executor.onSessionFinished(new SessionLoopFinishedEvent(
                "sess-OK", "All done.", "completed", 7L));

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(scheduledTaskService).markRunFinish(
                eq(400L), statusCaptor.capture(), any(), any(Instant.class), eq("sess-OK"));
        assertThat(statusCaptor.getValue()).isEqualTo(ScheduledTaskRunEntity.STATUS_SUCCESS);
        verify(channelDispatcher).pushResult(eq(task.getChannelTarget()),
                eq("sess-OK"), eq(400L), eq("All done."));
        verify(userTaskScheduler).clearRunning(6L);
        assertThat(executor.peekContext("sess-OK")).isEmpty();
    }

    @Test
    @DisplayName("session finished (error) → mark failure, push warning text with truncated error")
    void onSessionFinished_error() {
        ScheduledTaskEntity task = newCronTask(7L);
        task.setStatus(ScheduledTaskEntity.STATUS_RUNNING);
        task.setChannelTarget("{\"channelType\":\"feishu\",\"channelId\":\"oc_x\"}");
        when(repository.findById(7L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(7L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(7L, false)).thenReturn(runFor(7L, 500L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-ERR"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        executor.fire(7L, false);

        executor.onSessionFinished(new SessionLoopFinishedEvent(
                "sess-ERR", "Provider 503 retry exhausted", "error", 7L));

        verify(scheduledTaskService).markRunFinish(
                eq(500L), eq(ScheduledTaskRunEntity.STATUS_FAILURE),
                anyString(), any(Instant.class), eq("sess-ERR"));
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(channelDispatcher).pushResult(any(), eq("sess-ERR"), eq(500L), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("失败");
        assertThat(textCaptor.getValue()).contains("daily");
    }

    @Test
    @DisplayName("session finished (waiting_user) → run.status=paused with paused channel notice")
    void onSessionFinished_waitingUser() {
        ScheduledTaskEntity task = newCronTask(8L);
        task.setStatus(ScheduledTaskEntity.STATUS_RUNNING);
        task.setChannelTarget("{\"channelType\":\"feishu\",\"channelId\":\"oc_x\"}");
        when(repository.findById(8L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(8L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(8L, false)).thenReturn(runFor(8L, 600L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-PAUSE"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        executor.fire(8L, false);

        executor.onSessionFinished(new SessionLoopFinishedEvent(
                "sess-PAUSE", null, "waiting_user", 7L));

        verify(scheduledTaskService).markRunFinish(
                eq(600L), eq(ScheduledTaskRunEntity.STATUS_PAUSED),
                anyString(), any(Instant.class), eq("sess-PAUSE"));
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(channelDispatcher).pushResult(any(), eq("sess-PAUSE"), eq(600L), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("暂停");
    }

    @Test
    @DisplayName("channel push failure does NOT change task run terminal status (INV-9)")
    void onSessionFinished_pushFailure_doesNotAffectStatus() {
        ScheduledTaskEntity task = newCronTask(9L);
        task.setStatus(ScheduledTaskEntity.STATUS_RUNNING);
        task.setChannelTarget("{\"channelType\":\"feishu\",\"channelId\":\"oc_x\"}");
        when(repository.findById(9L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(9L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(9L, false)).thenReturn(runFor(9L, 700L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-OK2"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(channelDispatcher.pushResult(any(), anyString(), anyLong(), anyString()))
                .thenReturn("oh no the bot is down");
        executor.fire(9L, false);

        executor.onSessionFinished(new SessionLoopFinishedEvent(
                "sess-OK2", "yay", "completed", 7L));

        // Status is still success despite push error; error_message captures the push problem.
        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(scheduledTaskService).markRunFinish(
                eq(700L), eq(ScheduledTaskRunEntity.STATUS_SUCCESS),
                errCaptor.capture(), any(Instant.class), eq("sess-OK2"));
        assertThat(errCaptor.getValue()).contains("oh no the bot is down");
    }

    @Test
    @DisplayName("one-shot task is auto-disabled and marked completed on first finish (INV-2)")
    void onSessionFinished_oneShot_autoDisables() {
        ScheduledTaskEntity task = newCronTask(10L);
        task.setCronExpr(null);
        task.setOneShotAt(Instant.now().plusSeconds(60));
        task.setStatus(ScheduledTaskEntity.STATUS_RUNNING);
        when(repository.findById(10L)).thenReturn(Optional.of(task));
        when(userTaskScheduler.tryMarkRunning(10L)).thenReturn(true);
        when(scheduledTaskService.markRunStart(10L, false)).thenReturn(runFor(10L, 800L, false));
        when(sessionService.createSession(7L, 42L)).thenReturn(newSession("sess-1S"));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        executor.fire(10L, false);

        executor.onSessionFinished(new SessionLoopFinishedEvent(
                "sess-1S", "done", "completed", 7L));

        ArgumentCaptor<ScheduledTaskEntity> saveCaptor = ArgumentCaptor.forClass(ScheduledTaskEntity.class);
        // Two saves: (1) fire() sets last_fire_at + status=running; (2) handleOneShotCompletion
        // sets enabled=false + status=completed. The cron-only re-status save is intentionally
        // skipped for one-shot (handleOneShotCompletion already wrote the terminal status).
        verify(repository, times(2)).save(saveCaptor.capture());
        ScheduledTaskEntity terminal = saveCaptor.getAllValues().get(saveCaptor.getAllValues().size() - 1);
        assertThat(terminal.isEnabled()).isFalse();
        assertThat(terminal.getStatus()).isEqualTo(ScheduledTaskEntity.STATUS_COMPLETED);
        verify(userTaskScheduler).unschedule(10L);
    }

    @Test
    @DisplayName("event for unknown session is ignored (no NPE, no markRunFinish)")
    void onSessionFinished_unknownSession_isNoOp() {
        executor.onSessionFinished(new SessionLoopFinishedEvent(
                "sess-not-ours", "x", "completed", 7L));
        verify(scheduledTaskService, never()).markRunFinish(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("mapFinalStatus maps loop terminal status → run status correctly")
    void mapFinalStatus_pure() {
        assertThat(ScheduledTaskExecutor.mapFinalStatus("completed"))
                .isEqualTo(ScheduledTaskRunEntity.STATUS_SUCCESS);
        assertThat(ScheduledTaskExecutor.mapFinalStatus("error"))
                .isEqualTo(ScheduledTaskRunEntity.STATUS_FAILURE);
        assertThat(ScheduledTaskExecutor.mapFinalStatus("waiting_user"))
                .isEqualTo(ScheduledTaskRunEntity.STATUS_PAUSED);
        assertThat(ScheduledTaskExecutor.mapFinalStatus("cancelled"))
                .isEqualTo(ScheduledTaskRunEntity.STATUS_FAILURE);
        assertThat(ScheduledTaskExecutor.mapFinalStatus(null))
                .isEqualTo(ScheduledTaskRunEntity.STATUS_FAILURE);
    }

    @Test
    @DisplayName("composeChannelMessage composes warnings with task name, truncated error, and absolute paused link")
    void composeChannelMessage_pure() {
        ScheduledTaskEntity task = newCronTask(99L);
        task.setName("nightly");
        String dash = "https://dash.test";
        String successMsg = ScheduledTaskExecutor.composeChannelMessage(task, "completed", "hello world", dash);
        assertThat(successMsg).isEqualTo("hello world");

        String errorMsg = ScheduledTaskExecutor.composeChannelMessage(task, "error", "boom", dash);
        assertThat(errorMsg).contains("失败").contains("nightly").contains("boom");

        String pausedMsg = ScheduledTaskExecutor.composeChannelMessage(task, "waiting_user", null, dash);
        // r2 W4: paused message must include absolute URL so feishu users can click.
        assertThat(pausedMsg).contains("暂停").contains("nightly")
                .contains("https://dash.test/schedules/99");

        // Truncation: 250-char string trimmed to 200 + ellipsis.
        String long200 = "x".repeat(250);
        String truncated = ScheduledTaskExecutor.composeChannelMessage(task, "error", long200, dash);
        assertThat(truncated.length()).isLessThan(250);
    }

    @Test
    @DisplayName("r2 W1: handleOneShotMissed writes a skipped run row and auto-disables the task")
    void handleOneShotMissed_writesSkippedAndDisables() {
        ScheduledTaskEntity task = newCronTask(50L);
        task.setCronExpr(null);
        task.setOneShotAt(Instant.now().minusSeconds(60));
        task.setEnabled(true);
        task.setStatus(ScheduledTaskEntity.STATUS_IDLE);
        when(scheduledTaskService.markRunStart(50L, false)).thenReturn(runFor(50L, 9000L, false));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.handleOneShotMissed(task);

        // Skipped run row written via markRunFinish.
        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(scheduledTaskService).markRunFinish(
                eq(9000L),
                eq(ScheduledTaskRunEntity.STATUS_SKIPPED),
                errCaptor.capture(),
                any(Instant.class),
                isNull());
        assertThat(errCaptor.getValue()).contains("missed at startup");
        // Task auto-disabled + status=completed (handleOneShotCompletion path).
        assertThat(task.isEnabled()).isFalse();
        assertThat(task.getStatus()).isEqualTo(ScheduledTaskEntity.STATUS_COMPLETED);
        assertThat(task.getNextFireAt()).isNull();
    }
}
