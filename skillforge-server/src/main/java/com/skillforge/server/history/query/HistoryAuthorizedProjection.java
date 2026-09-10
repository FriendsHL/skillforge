package com.skillforge.server.history.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic allowlist projection and secret redaction for History evidence. */
@Component
public final class HistoryAuthorizedProjection {

    public static final int VERSION = 2;
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "accesstoken",
            "refreshtoken", "apikey", "authorization", "credential", "credentials",
            "privatekey", "clientsecret");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bbearer\\s+)[A-Za-z0-9._~+\\-/]{8,}");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|credential)\\b\\s*[:=]\\s*)([^\\s,;]+)");

    private final ObjectMapper objectMapper;

    public HistoryAuthorizedProjection(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ProjectedContent projectPlainText(String raw) {
        return finish(redactText(Objects.requireNonNullElse(raw, "")));
    }

    public ProjectedContent projectStructured(Object raw) {
        JsonNode tree = objectMapper.valueToTree(raw);
        return finish(writeCanonical(redact(sort(tree))));
    }

    public ProjectedContent projectToolResult(Object raw) {
        if (raw instanceof JsonNode node && node.isTextual()) raw = node.textValue();
        if (raw instanceof String text) {
            JsonNode parsed = parseContainer(text);
            return parsed == null ? projectPlainText(text) : finish(writeCanonical(redact(sort(parsed))));
        }
        return projectStructured(raw);
    }

    public ProjectedContent projectToolResult(
            Object raw,
            boolean error,
            String errorType) {
        JsonNode content;
        if (raw instanceof JsonNode node && node.isTextual()) raw = node.textValue();
        if (raw instanceof String text) {
            JsonNode parsed = parseContainer(text);
            content = parsed == null
                    ? objectMapper.getNodeFactory().textNode(redactText(text))
                    : redact(sort(parsed));
        } else {
            content = redact(sort(objectMapper.valueToTree(raw)));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("content", content);
        if (errorType != null) result.put("errorType", errorType);
        result.put("isError", error);
        return finish(writeCanonical(sort(result)));
    }

    private ProjectedContent finish(String authorized) {
        return new ProjectedContent(authorized, sha256(authorized));
    }

    private JsonNode parseContainer(String value) {
        String trimmed = value.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null;
        try {
            JsonNode parsed = objectMapper.readTree(trimmed);
            return parsed != null && (parsed.isObject() || parsed.isArray()) ? parsed : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            for (JsonNode item : node) out.add(sort(item));
            return out;
        }
        ObjectNode out = objectMapper.createObjectNode();
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        fields.forEachRemaining(entries::add);
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, JsonNode> entry : entries) {
            out.set(entry.getKey(), sort(entry.getValue()));
        }
        return out;
    }

    private JsonNode redact(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> out.set(
                    entry.getKey(),
                    isSecretKey(entry.getKey())
                            ? objectMapper.getNodeFactory().textNode(REDACTED)
                            : redact(entry.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            for (JsonNode child : node) out.add(redact(child));
            return out;
        }
        if (node.isTextual()) {
            return objectMapper.getNodeFactory().textNode(redactText(node.textValue()));
        }
        return node;
    }

    private String writeCanonical(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to project History content", exception);
        }
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.chars()
                .filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new,
                        (builder, value) -> builder.append((char) value),
                        StringBuilder::append)
                .toString()
                .toLowerCase(Locale.ROOT);
        return SECRET_KEYS.contains(normalized);
    }

    private static String redactText(String raw) {
        String bearerRedacted = BEARER.matcher(raw).replaceAll("$1" + REDACTED);
        return ASSIGNMENT.matcher(bearerRedacted).replaceAll("$1" + REDACTED);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record ProjectedContent(String content, String authorizedContentHash) { }
}
