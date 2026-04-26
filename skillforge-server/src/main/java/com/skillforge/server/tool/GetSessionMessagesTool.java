package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.dto.SessionMessageDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only self-inspection tool for persisted session messages.
 */
public class GetSessionMessagesTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GetSessionMessagesTool.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int HARD_LIMIT = 100;
    private static final int DEFAULT_MAX_CONTENT_CHARS = 1_000;
    private static final int HARD_MAX_CONTENT_CHARS = 5_000;

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public GetSessionMessagesTool(SessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "GetSessionMessages";
    }

    @Override
    public String getDescription() {
        return "Read recent persisted messages for the current session. "
                + "Returns role, content, msgType, metadata, and assistant tool_use blocks. "
                + "Use limit to cap the number of returned messages.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", Map.of(
                "type", "string",
                "description", "Optional session ID. Defaults to the current session. Must belong to the same user."
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "Number of most recent messages to return. Default 20, hard cap 100."
        ));
        properties.put("maxContentChars", Map.of(
                "type", "integer",
                "description", "Maximum characters for any string field inside message content. Default 1000, hard cap 5000."
        ));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                input = Map.of();
            }
            String sessionId = resolveSessionId(input, context);
            assertSessionAccessible(sessionId, context);
            int limit = intValue(input.get("limit"), DEFAULT_LIMIT, 1, HARD_LIMIT);
            int maxContentChars = intValue(
                    input.get("maxContentChars"), DEFAULT_MAX_CONTENT_CHARS, 100, HARD_MAX_CONTENT_CHARS);

            List<SessionMessageDto> all = sessionService.getFullHistoryDtos(sessionId);
            int from = Math.max(0, all.size() - limit);
            List<Map<String, Object>> messages = new ArrayList<>();
            for (SessionMessageDto dto : all.subList(from, all.size())) {
                messages.add(toMap(dto, maxContentChars));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sessionId", sessionId);
            out.put("totalMessages", all.size());
            out.put("returnedMessages", messages.size());
            out.put("limit", limit);
            out.put("messages", messages);
            return SkillResult.success(objectMapper.writeValueAsString(out));
        } catch (IllegalArgumentException e) {
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("GetSessionMessagesTool execute failed", e);
            return SkillResult.error("GetSessionMessages error: " + e.getMessage());
        }
    }

    private Map<String, Object> toMap(SessionMessageDto dto, int maxContentChars) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seqNo", dto.seqNo());
        m.put("role", dto.role());
        m.put("msgType", dto.msgType());
        m.put("metadata", dto.metadata());
        Object content = truncateObject(dto.content(), maxContentChars);
        m.put("content", content);
        m.put("toolCalls", extractToolCalls(content, maxContentChars));
        return m;
    }

    private List<Map<String, Object>> extractToolCalls(Object content, int maxContentChars) {
        if (!(content instanceof List<?> blocks)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> map) || !"tool_use".equals(String.valueOf(map.get("type")))) {
                continue;
            }
            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", stringValue(map.get("id")));
            toolCall.put("name", stringValue(map.get("name")));
            toolCall.put("input", truncateObject(map.get("input"), maxContentChars));
            out.add(toolCall);
        }
        return out;
    }

    private Object truncateObject(Object value, int maxContentChars) {
        if (value instanceof String s) {
            return truncate(s, maxContentChars);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(truncateObject(item, maxContentChars));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), truncateObject(entry.getValue(), maxContentChars));
            }
            return out;
        }
        return value;
    }

    private String resolveSessionId(Map<String, Object> input, SkillContext context) {
        String explicit = input != null ? stringValue(input.get("sessionId")) : null;
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String current = context.getSessionId();
        if (current == null || current.isBlank()) {
            throw new IllegalArgumentException("sessionId is required when no current session is available");
        }
        return current;
    }

    private void assertSessionAccessible(String sessionId, SkillContext context) {
        SessionEntity session = sessionService.getSession(sessionId);
        Long callerUserId = context.getUserId();
        if (callerUserId != null && session.getUserId() != null && !callerUserId.equals(session.getUserId())) {
            throw new IllegalArgumentException("session is not accessible: " + sessionId);
        }
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private static int intValue(Object value, int defaultValue, int min, int max) {
        int parsed = defaultValue;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }
}
