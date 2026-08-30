package com.skillforge.server.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.service.SessionTaskException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TaskToolSupport {
    private TaskToolSupport() {
    }

    static String sessionId(SkillContext context) {
        return context == null ? null : trim(context.getSessionId());
    }

    static Long userId(SkillContext context) {
        return context == null ? null : context.getUserId();
    }

    static String string(Map<String, Object> input, String canonical, String... aliases) {
        Object value = value(input, canonical, aliases);
        return value instanceof String text ? trim(text) : null;
    }

    static Object value(Map<String, Object> input, String canonical, String... aliases) {
        if (input == null) {
            return null;
        }
        if (input.containsKey(canonical)) {
            return input.get(canonical);
        }
        for (String alias : aliases) {
            if (input.containsKey(alias)) {
                return input.get(alias);
            }
        }
        return null;
    }

    static boolean contains(Map<String, Object> input, String canonical, String... aliases) {
        if (input == null) {
            return false;
        }
        if (input.containsKey(canonical)) {
            return true;
        }
        for (String alias : aliases) {
            if (input.containsKey(alias)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> input, String canonical, String... aliases) {
        Object value = value(input, canonical, aliases);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(canonical + " must be an object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    static List<String> strings(Map<String, Object> input, String canonical, String... aliases) {
        Object value = value(input, canonical, aliases);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(canonical + " must be an array");
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String text) || trim(text) == null) {
                throw new IllegalArgumentException(canonical + " must contain non-empty task IDs");
            }
            out.add(text.trim());
        }
        return out;
    }

    static int integer(Map<String, Object> input, String field, int defaultValue, int max) {
        Object value = value(input, field);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return Math.max(1, Math.min(parsed, max));
    }

    static Long longValue(Map<String, Object> input, String canonical, String... aliases) {
        Object value = value(input, canonical, aliases);
        if (value == null) return null;
        try {
            if (value instanceof Number number) return number.longValue();
            return Long.parseLong(value.toString());
        } catch (Exception exception) {
            throw new IllegalArgumentException(canonical + " must be an integer");
        }
    }

    static SkillResult contextError(ObjectMapper objectMapper) {
        return structuredError(new SessionTaskException("TASK_CONTEXT_REQUIRED",
                "Task tools require an authenticated session context", false, "context",
                "Run the tool from an active SkillForge session"), objectMapper);
    }

    static SkillResult error(Exception exception, ObjectMapper objectMapper) {
        if (exception instanceof SessionTaskException taskError) {
            return structuredError(taskError, objectMapper);
        }
        if (exception instanceof IllegalArgumentException validation) {
            return structuredError(new SessionTaskException("TASK_INPUT_INVALID",
                    validation.getMessage(), false, "input", "Correct the tool arguments and retry"), objectMapper);
        }
        return structuredError(new SessionTaskException("TASK_EXECUTION_FAILED",
                "Task operation failed", true,
                null, "Call TaskGet or TaskList to refresh state before retrying"), objectMapper);
    }

    private static SkillResult structuredError(SessionTaskException e, ObjectMapper objectMapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("errorCode", e.getCode());
        payload.put("errorType", e.getCode().contains("REQUIRED") || e.getCode().contains("INVALID")
                ? "validation" : "execution");
        payload.put("retryable", e.isRetryable());
        if (e.getFailedField() != null) {
            payload.put("failedField", e.getFailedField());
        }
        if (e.getSuggestedAction() != null) {
            payload.put("suggestedAction", e.getSuggestedAction());
        }
        payload.put("message", e.getMessage());
        try {
            String json = objectMapper.writeValueAsString(payload);
            return "validation".equals(payload.get("errorType"))
                    ? SkillResult.validationError(json) : SkillResult.error(json);
        } catch (Exception ignored) {
            return SkillResult.error(e.getCode() + ": " + e.getMessage());
        }
    }

    static String json(Object value, ObjectMapper mapper) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
