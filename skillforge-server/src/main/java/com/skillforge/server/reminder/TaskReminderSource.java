package com.skillforge.server.reminder;

import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.context.ContextLifecycle;
import com.skillforge.core.context.PromptCompactPolicy;
import com.skillforge.core.context.PromptPlacement;
import com.skillforge.core.reminder.ReminderBuilder;
import com.skillforge.core.reminder.ReminderContext;
import com.skillforge.core.reminder.ReminderEntry;
import com.skillforge.core.reminder.ReminderReasonCode;
import com.skillforge.core.reminder.ReminderSeverity;
import com.skillforge.core.reminder.ReminderSource;
import com.skillforge.core.reminder.ReminderSourceType;
import com.skillforge.server.dto.SessionTaskResponse;
import com.skillforge.server.service.SessionTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class TaskReminderSource implements ReminderSource {
    public static final String NAME = "task-state";
    private static final Logger log = LoggerFactory.getLogger(TaskReminderSource.class);
    private final SessionTaskService taskService;
    private final boolean enabled;
    private final int intervalTurns;
    private final int maxTasks;

    public TaskReminderSource(SessionTaskService taskService, boolean enabled, int intervalTurns, int maxTasks) {
        this.taskService = taskService;
        this.enabled = enabled;
        this.intervalTurns = Math.max(1, intervalTurns);
        this.maxTasks = Math.max(1, maxTasks);
    }
    @Override public String getName() { return NAME; }
    @Override public boolean shouldEmit(ReminderContext ctx) {
        if (!enabled || ctx == null || ctx.getSessionId() == null || ctx.getSessionId().isBlank()) return false;
        ReminderBuilder builder = ctx.getReminderBuilder();
        Integer last = builder == null ? null : builder.getLastEmitted(ctx.getSessionId(), NAME);
        if (last != null && ctx.getCurrentTurnIndex() - last < intervalTurns) return false;
        try { return !open(ctx.getSessionId()).isEmpty(); }
        catch (Exception e) { log.debug("Task reminder lookup failed; skipping: sessionId={}", ctx.getSessionId(), e); return false; }
    }
    @Override public ReminderEntry emit(ReminderContext ctx) {
        if (ctx == null || ctx.getSessionId() == null || ctx.getSessionId().isBlank()) return null;
        try {
            List<SessionTaskResponse> tasks = open(ctx.getSessionId());
            if (tasks.isEmpty()) return null;
            String text = render(tasks);
            ReminderBuilder builder = ctx.getReminderBuilder();
            if (builder != null) builder.setLastEmitted(ctx.getSessionId(), NAME, ctx.getCurrentTurnIndex());
            return new ReminderEntry(NAME, ReminderSourceType.TASK_STATE, ReminderSeverity.INFO,
                    ReminderReasonCode.PENDING_TASKS, text, TokenEstimator.estimateString(text), intervalTurns,
                    PromptPlacement.BEFORE_NEXT_MODEL_CALL, ContextLifecycle.UNTIL_STATE_CHANGE,
                    PromptCompactPolicy.RELOAD_BY_ID, null);
        } catch (Exception e) {
            log.debug("Task reminder render failed; skipping: sessionId={}", ctx.getSessionId(), e);
            return null;
        }
    }
    public String renderForRecovery(String sessionId) {
        try {
            List<SessionTaskResponse> tasks = open(sessionId);
            return tasks.isEmpty() ? null : render(tasks);
        } catch (Exception e) {
            log.debug("Task compact recovery failed; skipping: sessionId={}", sessionId, e);
            return null;
        }
    }
    private List<SessionTaskResponse> open(String sessionId) {
        return taskService.snapshotForSystem(sessionId, false, SessionTaskService.MAX_TASKS_PER_SESSION).tasks().stream()
                .filter(t -> "pending".equals(t.status()) || "in_progress".equals(t.status()))
                .toList();
    }
    private String render(List<SessionTaskResponse> tasks) {
        StringBuilder out = new StringBuilder(256);
        out.append("当前持久化任务状态（仅在与用户最新消息相关时使用；最新用户指令优先，可忽略无关任务）：\n");
        int emitted = 0;
        for (SessionTaskResponse task : tasks) {
            if (emitted >= maxTasks) break;
            out.append("- ").append("in_progress".equals(task.status()) ? "[>] " : "[ ] ")
                    .append(task.taskId()).append(": ").append(task.subject())
                    .append(" | status=").append(task.status());
            if (task.owner() != null) out.append(" | owner=").append(task.owner());
            if (task.blocked()) out.append(" | blockedBy=").append(task.blockedBy());
            out.append(" | activeForm=").append(task.activeForm()).append('\n');
            emitted++;
        }
        if (tasks.size() > emitted) out.append("... ").append(tasks.size() - emitted).append(" more tasks not shown\n");
        return out.toString().stripTrailing();
    }
}
