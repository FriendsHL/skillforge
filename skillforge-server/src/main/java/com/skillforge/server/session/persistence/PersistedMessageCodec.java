package com.skillforge.server.session.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single Jackson boundary for durable conversation messages.
 *
 * <p>The codec deliberately uses the Spring-managed {@link ObjectMapper}; this keeps
 * content blocks, DeepSeek reasoning content, Java time values and future compatible
 * message fields on the same wire configuration as the rest of the application.
 * Decode failures are fail-closed and never echo persisted transcript bytes.
 */
@Component
public class PersistedMessageCodec {

    private final ObjectMapper objectMapper;

    public PersistedMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Encodes an exact full message for ordered-user inbox persistence. */
    public String writeMessage(Message message) {
        Objects.requireNonNull(message, "message");
        if (message.getRole() == null) {
            throw new CodecException("Persisted message role is required");
        }
        return writeJson(message, "Failed to encode persisted message JSON");
    }

    /** Decodes an exact full message previously produced by {@link #writeMessage(Message)}. */
    public Message readMessage(String messageJson) {
        if (messageJson == null || messageJson.isBlank()) {
            throw new CodecException("Failed to decode persisted message JSON");
        }
        try {
            Message message = objectMapper.readValue(messageJson, Message.class);
            if (message == null || message.getRole() == null) {
                throw new CodecException("Persisted message JSON must contain a role");
            }
            return message;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new CodecException("Failed to decode persisted message JSON", exception);
        }
    }

    /** Encodes the column shape used by {@code t_session_message}. */
    public EncodedRow encodeRow(PersistedMessage persisted) {
        Objects.requireNonNull(persisted, "persisted");
        Message message = Objects.requireNonNull(persisted.message(), "persisted.message");
        if (message.getRole() == null) {
            throw new CodecException("Persisted message role is required");
        }
        requireNonBlank(persisted.msgType(), "Persisted msgType is required");
        requireNonBlank(persisted.messageType(), "Persisted messageType is required");

        Map<String, Object> metadata = persisted.metadata() == null
                ? Collections.emptyMap()
                : persisted.metadata();
        return new EncodedRow(
                roleValue(message.getRole()),
                writeJson(message.getContent(), "Failed to encode persisted content JSON"),
                message.getReasoningContent(),
                persisted.msgType(),
                persisted.messageType(),
                persisted.controlId(),
                persisted.answeredAt(),
                writeJson(metadata, "Failed to encode persisted metadata JSON"),
                persisted.traceId());
    }

    /** Decodes the column shape used by {@code t_session_message}. */
    public PersistedMessage decodeRow(EncodedRow row) {
        Objects.requireNonNull(row, "row");
        Message message = new Message();
        message.setRole(parseRole(row.role()));
        message.setContent(readJsonValue(row.contentJson(), "Failed to decode persisted content JSON"));
        message.setReasoningContent(row.reasoningContent());

        Map<String, Object> metadata = readMetadata(row.metadataJson());
        return new PersistedMessage(
                message,
                requireNonBlank(row.msgType(), "Persisted msgType is required"),
                requireNonBlank(row.messageType(), "Persisted messageType is required"),
                row.controlId(),
                row.answeredAt(),
                metadata,
                row.traceId());
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }
        Object decoded = readJsonValue(metadataJson, "Failed to decode persisted metadata JSON");
        if (!(decoded instanceof Map<?, ?> map)) {
            throw new CodecException("Persisted metadata JSON must be an object");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new CodecException("Persisted metadata JSON contains a non-string key");
            }
            metadata.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(metadata);
    }

    private Object readJsonValue(String json, String failureMessage) {
        if (json == null || json.isBlank()) {
            throw new CodecException(failureMessage);
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new CodecException(failureMessage, exception);
        }
    }

    private String writeJson(Object value, String failureMessage) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new CodecException(failureMessage, exception);
        }
    }

    private static String roleValue(Message.Role role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
        };
    }

    private static Message.Role parseRole(String role) {
        if (role == null) {
            throw new CodecException("Persisted message role is required");
        }
        return switch (role) {
            case "user" -> Message.Role.USER;
            case "assistant" -> Message.Role.ASSISTANT;
            case "system" -> Message.Role.SYSTEM;
            default -> throw new CodecException("Persisted message role is invalid");
        };
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new CodecException(message);
        }
        return value;
    }

    public record PersistedMessage(
            Message message,
            String msgType,
            String messageType,
            String controlId,
            Instant answeredAt,
            Map<String, Object> metadata,
            String traceId) {

        /** Compatibility constructor for callers written before durable scalar preservation. */
        public PersistedMessage(
                Message message,
                String msgType,
                String messageType,
                String controlId,
                Map<String, Object> metadata) {
            this(message, msgType, messageType, controlId, null, metadata, null);
        }
    }

    public record EncodedRow(
            String role,
            String contentJson,
            String reasoningContent,
            String msgType,
            String messageType,
            String controlId,
            Instant answeredAt,
            String metadataJson,
            String traceId) {

        /** Compatibility constructor for callers written before durable scalar preservation. */
        public EncodedRow(
                String role,
                String contentJson,
                String reasoningContent,
                String msgType,
                String messageType,
                String controlId,
                String metadataJson) {
            this(role, contentJson, reasoningContent, msgType, messageType, controlId,
                    null, metadataJson, null);
        }
    }

    public static final class CodecException extends RuntimeException {
        public CodecException(String message) {
            super(message);
        }

        public CodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
