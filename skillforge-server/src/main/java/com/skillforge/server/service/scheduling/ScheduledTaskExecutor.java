package com.skillforge.server.service.scheduling;

import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.ScheduledTaskService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P12 scheduled-task execution engine.
 *
 * <p>Two halves:
 * <ol>
 *   <li>{@link #fire(long, boolean)} — called by {@link UserTaskScheduler} when a
 *       cron / one-shot trigger fires (or via manual REST trigger). Decides
 *       whether to skip (INV-4), opens / reuses a session (INV-5/6), launches
 *       {@code ChatService.chatAsync} and records a {@code running} run row.</li>
 *   <li>{@link #onSessionFinished(SessionLoopFinishedEvent)} — listens for
 *       {@link SessionLoopFinishedEvent} and, if the finished session belongs to
 *       one of our in-flight runs, transitions it to {@code success} /
 *       {@code failure} / {@code paused}, pushes channel (INV-9) and clears the
 *       running marker. One-shot tasks are auto-disabled on first finish (INV-2).</li>
 * </ol>
 *
 * <p>State map: {@link #inFlightSessions} keys by sessionId so the listener can
 * find the run that owns each finished session. Concurrent map covers the
 * read/write race between fire and finish on different threads.
 */
@Component
public class ScheduledTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskExecutor.class);
    private static final int ERROR_TRUNCATE_MAX = 200;

    private final ScheduledTaskRepository repository;
    private final ScheduledTaskService scheduledTaskService;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final ObjectProvider<UserTaskScheduler> userTaskSchedulerProvider;
    private final SchedulerChannelDispatcher channelDispatcher;
    /** r2 W4: operator-facing FE host (no trailing slash). Used to build clickable
     *  links in channel push messages so feishu/telegram users can jump straight
     *  to the schedule's run history page on a paused run. */
    private final String dashboardUrl;

    /**
     * sessionId → in-flight run context. Populated when a session is opened for
     * a fire, removed on terminal SessionLoopFinishedEvent.
     */
    private final ConcurrentHashMap<String, RunContext> inFlightSessions = new ConcurrentHashMap<>();

    /**
     * Per-task lock to serialize the "create + persist reused_session_id" critical
     * section in session_mode=reuse — without this, two near-simultaneous fires of
     * the same task could each create a session, only one would win the DB write,
     * and the loser session would orphan.
     */
    private final ConcurrentHashMap<Long, Object> taskCriticalSections = new ConcurrentHashMap<>();

    public ScheduledTaskExecutor(ScheduledTaskRepository repository,
                                 ScheduledTaskService scheduledTaskService,
                                 SessionService sessionService,
                                 ChatService chatService,
                                 ObjectProvider<UserTaskScheduler> userTaskSchedulerProvider,
                                 SchedulerChannelDispatcher channelDispatcher,
                                 @Value("${app.dashboard-url:http://localhost:5173}") String dashboardUrl) {
        this.repository = repository;
        this.scheduledTaskService = scheduledTaskService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.userTaskSchedulerProvider = userTaskSchedulerProvider;
        this.channelDispatcher = channelDispatcher;
        // Trim trailing slash so callers that always concatenate with "/schedules/{id}"
        // don't accidentally produce double-slash URLs.
        this.dashboardUrl = stripTrailingSlash(dashboardUrl);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Fire a single trigger for the given task. Called from the scheduler thread
     * pool — must not block on long work; we hand off to {@code chatAsync}.
     *
     * @param taskId task id
     * @param manual {@code true} for REST manual trigger / fire-now (INV-10 — bypasses enabled)
     */
    public void fire(long taskId, boolean manual) {
        fireForResult(taskId, manual);
    }

    /**
     * Same as {@link #fire(long, boolean)} but returns the run/session ids of the
     * launched run. Used by admin endpoints (V69 dogfood {@code AdminMemoryLlmSynthesisController.runOnce})
     * that need to return the {@code sessionId} to the caller so the FE can jump
     * to the live session trace.
     *
     * <p>Returns {@link Optional#empty()} for the no-op paths (task not found /
     * disabled+!manual / skip-if-running) — caller can distinguish via the
     * empty Optional whether anything actually ran.
     *
     * @param taskId task id
     * @param manual {@code true} for REST manual trigger (bypasses enabled flag)
     * @return outcome with runId + sessionId, or empty when the fire was a no-op
     *         (task missing / disabled / already running).
     */
    public Optional<FireOutcome> fireForResult(long taskId, boolean manual) {
        ScheduledTaskEntity task = repository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("ScheduledTaskExecutor.fire: task {} not found — possibly deleted", taskId);
            return Optional.empty();
        }
        // INV-1: respect enabled. Manual trigger bypasses (INV-10).
        if (!manual && !task.isEnabled()) {
            log.debug("Skipping fire for disabled task {} (manual=false)", taskId);
            return Optional.empty();
        }
        UserTaskScheduler scheduler = userTaskSchedulerProvider.getObject();
        // INV-4: skip if already running.
        if (!scheduler.tryMarkRunning(taskId)) {
            log.info("Skipping fire for task {} — previous run still in flight (skip-if-running)", taskId);
            scheduledTaskService.markRunSkipped(taskId, manual);
            return Optional.empty();
        }
        boolean handedOff = false;
        try {
            ScheduledTaskRunEntity run = scheduledTaskService.markRunStart(taskId, manual);
            String sessionId;
            try {
                sessionId = openSessionForTask(task);
            } catch (Exception e) {
                log.error("Failed to open session for task {}: {}", taskId, e.getMessage(), e);
                finishRun(run.getId(), task, null, ScheduledTaskRunEntity.STATUS_FAILURE,
                        "session open failed: " + e.getMessage());
                handleOneShotCompletion(task);
                return Optional.empty();
            }
            // Persist sessionId on the run row so the FE history can link to it
            // (does NOT finalize — keeps run.status = running until the loop completes).
            scheduledTaskService.attachRunSession(run.getId(), sessionId);
            inFlightSessions.put(sessionId, new RunContext(taskId, run.getId(), manual));
            // Update last_fire_at for FE display.
            task.setLastFireAt(Instant.now());
            task.setStatus(ScheduledTaskEntity.STATUS_RUNNING);
            // reuse 模式：openSessionForTask 内部用 refreshed 实例 save 了 reused_session_id，
            // 但本地 task 引用是 fire() 开头 findById 拿的快照（reusedSessionId=null）。
            // 不同步就会被下面的 save(task) 用 null 覆盖回去，导致每次 fire 都建新 session。
            if (ScheduledTaskEntity.SESSION_MODE_REUSE.equals(task.getSessionMode())) {
                task.setReusedSessionId(sessionId);
            }
            repository.save(task);
            // Hand off to ChatService — it submits to chatLoopExecutor (does not block).
            chatService.chatAsync(sessionId, task.getPromptTemplate(), task.getCreatorUserId(), false);
            handedOff = true;
            return Optional.of(new FireOutcome(taskId, run.getId(), sessionId, manual));
        } finally {
            // If anything threw before chatAsync returned, clear running so future cron fires aren't blocked.
            if (!handedOff) {
                scheduler.clearRunning(taskId);
            }
        }
    }

    /**
     * Outcome of {@link #fireForResult(long, boolean)} — runId + sessionId of the
     * launched run. Caller uses this to attach a synchronous response to an
     * otherwise-async admin trigger.
     */
    public record FireOutcome(long taskId, long runId, String sessionId, boolean manual) {
    }

    /**
     * Open a session for the given task per its session_mode (INV-5/INV-6).
     * For session_mode=reuse, the first fire creates a session and persists its
     * id to {@code reused_session_id}; subsequent fires reuse the same session.
     */
    private String openSessionForTask(ScheduledTaskEntity task) {
        if (ScheduledTaskEntity.SESSION_MODE_REUSE.equals(task.getSessionMode())) {
            // Critical section: prevents two concurrent fires from both creating fresh sessions.
            Object lock = taskCriticalSections.computeIfAbsent(task.getId(), k -> new Object());
            synchronized (lock) {
                // Re-read the row inside the lock — another fire on the same task may have
                // created a session and committed it while we were waiting.
                ScheduledTaskEntity refreshed = repository.findById(task.getId()).orElse(task);
                String existing = refreshed.getReusedSessionId();
                if (existing != null && !existing.isBlank()) {
                    return existing;
                }
                SessionEntity created = sessionService.createSession(
                        refreshed.getCreatorUserId(), refreshed.getAgentId());
                refreshed.setReusedSessionId(created.getId());
                repository.save(refreshed);
                return created.getId();
            }
        }
        // INV-5: session_mode=new — every fire opens a fresh session.
        SessionEntity created = sessionService.createSession(
                task.getCreatorUserId(), task.getAgentId());
        return created.getId();
    }

    /**
     * Listen for session loop terminal events. Sync listener — fast path is a
     * map lookup + DB writes. The slowest call is the synchronous channel push
     * (~10s worst case), which only fires when the task is configured with
     * channel_target. For MVP we accept that loop teardown blocks for that long;
     * V2 can move the channel push to an async executor.
     */
    @EventListener
    public void onSessionFinished(SessionLoopFinishedEvent event) {
        RunContext ctx = inFlightSessions.remove(event.sessionId());
        if (ctx == null) {
            // Not one of our scheduled sessions — ignore.
            return;
        }
        try {
            ScheduledTaskEntity task = repository.findById(ctx.taskId).orElse(null);
            if (task == null) {
                log.warn("Task {} disappeared while session {} was running", ctx.taskId, event.sessionId());
                return;
            }
            String runStatus = mapFinalStatus(event.finalStatus());
            String channelText = composeChannelMessage(task, event.finalStatus(), event.finalMessage(), dashboardUrl);
            String channelError = channelDispatcher.pushResult(
                    task.getChannelTarget(), event.sessionId(), ctx.runId, channelText);
            String errorMessage = composeErrorMessage(event.finalStatus(), event.finalMessage(), channelError);
            scheduledTaskService.markRunFinish(
                    ctx.runId, runStatus, errorMessage, Instant.now(), event.sessionId());
            // INV-2: one-shot completion — disable + status=completed once it has fired
            // (regardless of success/failure; running again would defeat one-shot semantics).
            handleOneShotCompletion(task);

            // Update top-level task status reflection (only for cron tasks; one-shot
            // already wrote status=completed via handleOneShotCompletion above).
            if (task.getOneShotAt() == null
                    && ScheduledTaskEntity.STATUS_RUNNING.equals(task.getStatus())) {
                task.setStatus(mapTaskStatus(event.finalStatus()));
                repository.save(task);
            }
        } finally {
            UserTaskScheduler scheduler = userTaskSchedulerProvider.getIfAvailable();
            if (scheduler != null) {
                scheduler.clearRunning(ctx.taskId);
            }
        }
    }

    /**
     * r2 W1: handle a one-shot task whose fire time was already in the past at
     * registration (e.g. server was down when the trigger should have fired).
     * Without this, the row sits enabled=true status=idle forever — the next
     * server restart re-evaluates the same past trigger, never schedules it,
     * and the FE shows a phantom never-firing schedule.
     *
     * <p>Resolution:
     * <ol>
     *   <li>Write a {@code skipped} run row with a clear error_message so the
     *       user can see "this fire was missed during downtime"</li>
     *   <li>Auto-disable + status=completed via {@link #handleOneShotCompletion}
     *       — same terminal state a normally-fired one-shot reaches</li>
     * </ol>
     */
    public void handleOneShotMissed(ScheduledTaskEntity task) {
        if (task == null || task.getOneShotAt() == null) {
            return;
        }
        try {
            ScheduledTaskRunEntity run = scheduledTaskService.markRunStart(task.getId(), false);
            scheduledTaskService.markRunFinish(
                    run.getId(),
                    ScheduledTaskRunEntity.STATUS_SKIPPED,
                    "missed at startup; fire time " + task.getOneShotAt() + " was in the past",
                    Instant.now(),
                    null);
        } catch (Exception e) {
            log.warn("Failed to write missed-fire run row for one-shot task {}: {}",
                    task.getId(), e.getMessage());
        }
        handleOneShotCompletion(task);
    }

    private void handleOneShotCompletion(ScheduledTaskEntity task) {
        if (task.getOneShotAt() == null) {
            return; // cron task: leave enabled / status alone
        }
        task.setEnabled(false);
        task.setStatus(ScheduledTaskEntity.STATUS_COMPLETED);
        task.setNextFireAt(null);
        repository.save(task);
        UserTaskScheduler scheduler = userTaskSchedulerProvider.getIfAvailable();
        if (scheduler != null) {
            scheduler.unschedule(task.getId());
        }
    }

    /**
     * Convenience used inside {@link #fire} when session-open fails before a
     * SessionLoopFinishedEvent will ever arrive. Writes a terminal run row,
     * does NOT push channel (no message to push), and does NOT clear running —
     * the {@code !handedOff} branch in {@link #fire} handles that.
     */
    private void finishRun(Long runId, ScheduledTaskEntity task, String sessionId,
                           String runStatus, String errorMessage) {
        try {
            scheduledTaskService.markRunFinish(runId, runStatus, errorMessage, Instant.now(), sessionId);
        } catch (Exception e) {
            log.error("Failed to mark run {} finished for task {}: {}",
                    runId, task != null ? task.getId() : null, e.getMessage(), e);
        }
    }

    // ---------- pure helpers (status mapping / message composition) ----------

    static String mapFinalStatus(String finalStatus) {
        if (finalStatus == null) return ScheduledTaskRunEntity.STATUS_FAILURE;
        return switch (finalStatus) {
            case "completed" -> ScheduledTaskRunEntity.STATUS_SUCCESS;
            case "waiting_user" -> ScheduledTaskRunEntity.STATUS_PAUSED;
            case "cancelled" -> ScheduledTaskRunEntity.STATUS_FAILURE;
            case "aborted_by_hook" -> ScheduledTaskRunEntity.STATUS_FAILURE;
            case "error" -> ScheduledTaskRunEntity.STATUS_FAILURE;
            default -> ScheduledTaskRunEntity.STATUS_FAILURE;
        };
    }

    static String mapTaskStatus(String finalStatus) {
        if (finalStatus == null) return ScheduledTaskEntity.STATUS_ERROR;
        return switch (finalStatus) {
            case "completed", "waiting_user" -> ScheduledTaskEntity.STATUS_IDLE;
            default -> ScheduledTaskEntity.STATUS_ERROR;
        };
    }

    /**
     * r2 W4: builds the channel push body. {@code dashboardUrl} is the
     * operator-facing FE origin (no trailing slash) — used to make the paused-run
     * link clickable in feishu / telegram messages.
     */
    static String composeChannelMessage(ScheduledTaskEntity task, String finalStatus,
                                        String finalMessage, String dashboardUrl) {
        if (finalStatus == null) return null;
        String safeDashboard = dashboardUrl == null ? "" : dashboardUrl;
        return switch (finalStatus) {
            case "completed" -> finalMessage; // brief §6: normal final assistant message verbatim
            case "waiting_user" -> "⚠️ 定时任务【" + task.getName()
                    + "】暂停，需要人工输入。查看：" + safeDashboard + "/schedules/" + task.getId();
            default -> "⚠️ 定时任务【" + task.getName() + "】失败：" + truncate(finalMessage, ERROR_TRUNCATE_MAX);
        };
    }

    static String composeErrorMessage(String finalStatus, String finalMessage, String channelError) {
        StringBuilder sb = new StringBuilder();
        if ("completed".equals(finalStatus)) {
            // success path — only record channel error if any
        } else if ("waiting_user".equals(finalStatus)) {
            sb.append("paused: agent requested user input");
        } else {
            sb.append("status=").append(finalStatus);
            if (finalMessage != null && !finalMessage.isBlank()) {
                sb.append("; ").append(truncate(finalMessage, ERROR_TRUNCATE_MAX));
            }
        }
        if (channelError != null && !channelError.isBlank()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(channelError);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    // ---------- test hooks ----------

    /** Test hook: returns size of in-flight session map. */
    int inFlightCount() {
        return inFlightSessions.size();
    }

    /**
     * Test hook: peek at the run context for a given session id. Returns
     * {@link Optional#empty()} when the session isn't tracked.
     */
    Optional<RunContext> peekContext(String sessionId) {
        return Optional.ofNullable(inFlightSessions.get(sessionId));
    }

    /**
     * Per-fire run context. Package-private record so tests can introspect.
     */
    record RunContext(long taskId, long runId, boolean manual) {
    }
}
