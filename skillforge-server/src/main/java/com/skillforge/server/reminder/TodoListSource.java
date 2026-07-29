package com.skillforge.server.reminder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.skillforge.server.skill.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Emits the current session TodoWrite state back into the reminder stream.
 *
 * <p>Kept in the server module because TodoWrite state currently lives in the server-side
 * {@link TodoStore}. The source still implements the core {@link ReminderSource} contract so
 * it can be ordered and budgeted by {@link ReminderBuilder} with the existing sources.
 */
public class TodoListSource implements ReminderSource {

    private static final Logger log = LoggerFactory.getLogger(TodoListSource.class);

    public static final String NAME = "todo-list";

    private static final TypeReference<List<TodoItem>> TODO_LIST_TYPE = new TypeReference<>() {};

    private final TodoStore todoStore;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int intervalTurns;
    private final int maxTodos;

    public TodoListSource(TodoStore todoStore,
                          ObjectMapper objectMapper,
                          boolean enabled,
                          int intervalTurns,
                          int maxTodos) {
        this.todoStore = todoStore;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.intervalTurns = Math.max(1, intervalTurns);
        this.maxTodos = Math.max(1, maxTodos);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean shouldEmit(ReminderContext ctx) {
        if (!enabled || todoStore == null || objectMapper == null || ctx == null) return false;
        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return false;
        if (!debounceElapsed(ctx)) return false;
        return !readTodos(sessionId).isEmpty();
    }

    @Override
    public ReminderEntry emit(ReminderContext ctx) {
        if (ctx == null || ctx.getSessionId() == null || ctx.getSessionId().isBlank()) return null;
        List<TodoItem> todos = readTodos(ctx.getSessionId());
        if (todos.isEmpty()) return null;

        String text = render(todos);
        ReminderBuilder builder = ctx.getReminderBuilder();
        if (builder != null) {
            builder.setLastEmitted(ctx.getSessionId(), NAME, ctx.getCurrentTurnIndex());
        }
        return new ReminderEntry(
                NAME,
                ReminderSourceType.TODO_LIST,
                ReminderSeverity.INFO,
                ReminderReasonCode.PENDING_TODOS,
                text,
                TokenEstimator.estimateString(text),
                intervalTurns,
                PromptPlacement.BEFORE_NEXT_MODEL_CALL,
                ContextLifecycle.UNTIL_STATE_CHANGE,
                PromptCompactPolicy.RELOAD_BY_ID,
                null);
    }

    private List<TodoItem> readTodos(String sessionId) {
        try {
            String json = todoStore.getTasks(sessionId);
            if (json == null || json.isBlank()) return List.of();
            List<TodoItem> todos = objectMapper.readValue(json, TODO_LIST_TYPE);
            if (todos == null || todos.isEmpty()) return List.of();
            List<TodoItem> valid = new ArrayList<>(todos.size());
            for (TodoItem todo : todos) {
                if (todo == null || todo.id() == null || todo.id().isBlank()
                        || todo.title() == null || todo.title().isBlank()
                        || !isKnownStatus(todo.status())) {
                    continue;
                }
                valid.add(todo);
            }
            return valid;
        } catch (Exception e) {
            log.debug("TodoListSource readTodos failed (skip): {}", e.toString());
            return List.of();
        }
    }

    private String render(List<TodoItem> todos) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Todos:\n");
        int emitted = 0;
        emitted = appendGroup(sb, "In progress:", todos, "in_progress", emitted);
        emitted = appendGroup(sb, "Pending:", todos, "pending", emitted);
        emitted = appendGroup(sb, "Completed:", todos, "completed", emitted);
        int hidden = todos.size() - emitted;
        if (hidden > 0) {
            sb.append("... ").append(hidden).append(hidden == 1
                    ? " more todo not shown"
                    : " more todos not shown");
        }
        return sb.toString().stripTrailing();
    }

    private int appendGroup(StringBuilder sb,
                            String heading,
                            List<TodoItem> todos,
                            String status,
                            int emitted) {
        boolean wroteHeading = false;
        for (TodoItem todo : todos) {
            if (emitted >= maxTodos) return emitted;
            if (!status.equals(normalizeStatus(todo.status()))) continue;
            if (!wroteHeading) {
                sb.append(heading).append('\n');
                wroteHeading = true;
            }
            sb.append("- ").append(marker(status)).append(' ')
                    .append(todo.id().trim())
                    .append(": ")
                    .append(todo.title().trim())
                    .append('\n');
            emitted++;
        }
        return emitted;
    }

    private boolean debounceElapsed(ReminderContext ctx) {
        ReminderBuilder builder = ctx.getReminderBuilder();
        if (builder == null || ctx.getSessionId() == null) return true;
        Integer last = builder.getLastEmitted(ctx.getSessionId(), NAME);
        if (last == null) return true;
        return ctx.getCurrentTurnIndex() - last >= intervalTurns;
    }

    private static String marker(String status) {
        return switch (status) {
            case "completed" -> "[x]";
            case "in_progress" -> "[>]";
            default -> "[ ]";
        };
    }

    private static boolean isKnownStatus(String status) {
        return "pending".equals(normalizeStatus(status))
                || "in_progress".equals(normalizeStatus(status))
                || "completed".equals(normalizeStatus(status));
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    private record TodoItem(String id, String title, String status) {}
}
