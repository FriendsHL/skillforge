package com.skillforge.server.acp.otlp;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an OTLP-JSON {@code POST /v1/logs} payload into normalized
 * {@link ParsedCcEvent}s (ACP-EXTERNAL-AGENT P2-1).
 *
 * <p>Spike-verified shape (2026-06-19, /tmp/acp-spike/otel-spike.mjs):
 * <pre>{@code
 * { "resourceLogs": [ {
 *     "resource": { "attributes": [ {"key":"sf.session_id","value":{"stringValue":"..."}}, ... ] },
 *     "scopeLogs": [ {
 *       "logRecords": [ {
 *         "timeUnixNano": "...",
 *         "body": { "stringValue": "claude_code.api_request" },
 *         "attributes": [ {"key":"model","value":{"stringValue":"..."}}, ... ]
 *       } ]
 *     } ]
 * } ] }
 * }</pre>
 *
 * <p>The parser is PURE (no Spring, no DB) so the OTLP shape handling is unit
 * testable in isolation. It is defensive: malformed sub-trees are skipped rather
 * than throwing, so one bad record can not fail the whole batch. Resource-level
 * attributes (which carry {@code sf.session_id}, {@code sf.agent_id},
 * {@code session.id}, {@code user.*}, ...) are merged into each event's attribute
 * map; logRecord attributes win on key collision.
 *
 * <p>PII is NOT filtered here — that is the ingest layer's job (single auditable
 * place). This parser faithfully reflects the wire shape.
 */
public final class OtlpLogsParser {

    /** OTLP attribute key holding the SkillForge cc sub-session id we injected. */
    public static final String SF_SESSION_ID = "sf.session_id";
    /** cc's own session id attribute key. */
    public static final String SESSION_ID = "session.id";
    private static final String EVENT_NAME = "event.name";
    private static final String EVENT_SEQUENCE = "event.sequence";
    private static final String EVENT_TIMESTAMP = "event.timestamp";

    /**
     * Parse a full OTLP-JSON logs payload. Returns one {@link ParsedCcEvent} per
     * logRecord that has a resolvable event name; everything malformed is skipped.
     *
     * @param root the parsed JSON body (a Jackson tree); null ⇒ empty list
     */
    public List<ParsedCcEvent> parse(JsonNode root) {
        List<ParsedCcEvent> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode resourceLogs = root.get("resourceLogs");
        if (resourceLogs == null || !resourceLogs.isArray()) {
            return out;
        }
        for (JsonNode rl : resourceLogs) {
            Map<String, Object> resourceAttrs = readAttributes(
                    rl.path("resource").path("attributes"));
            JsonNode scopeLogs = rl.get("scopeLogs");
            if (scopeLogs == null || !scopeLogs.isArray()) {
                continue;
            }
            for (JsonNode sl : scopeLogs) {
                JsonNode logRecords = sl.get("logRecords");
                if (logRecords == null || !logRecords.isArray()) {
                    continue;
                }
                for (JsonNode lr : logRecords) {
                    ParsedCcEvent ev = parseRecord(lr, resourceAttrs);
                    if (ev != null) {
                        out.add(ev);
                    }
                }
            }
        }
        return out;
    }

    private ParsedCcEvent parseRecord(JsonNode lr, Map<String, Object> resourceAttrs) {
        if (lr == null || lr.isNull()) {
            return null;
        }
        Map<String, Object> recordAttrs = readAttributes(lr.path("attributes"));
        // Merge: resource attrs first, record attrs win on collision.
        Map<String, Object> merged = new LinkedHashMap<>(resourceAttrs);
        merged.putAll(recordAttrs);

        String eventName = resolveEventName(lr, merged);
        if (eventName == null || eventName.isBlank()) {
            return null; // not an event we can classify — skip
        }

        String sfSessionId = asString(merged.get(SF_SESSION_ID));
        String ccSessionId = asString(merged.get(SESSION_ID));
        Long eventSeq = asLong(merged.get(EVENT_SEQUENCE));
        Instant ts = resolveTimestamp(lr, merged);

        return new ParsedCcEvent(eventName, sfSessionId, ccSessionId, eventSeq, ts, merged);
    }

    /**
     * Event name comes from the logRecord {@code body.stringValue}
     * ({@code claude_code.<event>}) per spike; fall back to the {@code event.name}
     * attribute when the body is absent.
     */
    private String resolveEventName(JsonNode lr, Map<String, Object> merged) {
        JsonNode body = lr.get("body");
        if (body != null) {
            JsonNode sv = body.get("stringValue");
            if (sv != null && sv.isTextual() && !sv.asText().isBlank()) {
                return sv.asText();
            }
        }
        return asString(merged.get(EVENT_NAME));
    }

    /**
     * Prefer {@code logRecord.timeUnixNano}; fall back to the {@code event.timestamp}
     * attribute (ISO-8601 string). Returns null if neither is usable.
     */
    private Instant resolveTimestamp(JsonNode lr, Map<String, Object> merged) {
        JsonNode tun = lr.get("timeUnixNano");
        if (tun != null && (tun.isTextual() || tun.isNumber())) {
            try {
                long nanos = Long.parseLong(tun.asText());
                if (nanos > 0) {
                    return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
                }
            } catch (NumberFormatException ignore) {
                // fall through to event.timestamp
            }
        }
        Object evTs = merged.get(EVENT_TIMESTAMP);
        if (evTs instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s);
            } catch (RuntimeException ignore) {
                return null;
            }
        }
        return null;
    }

    /**
     * Read an OTLP attribute array ({@code [{key, value:{stringValue|intValue|
     * doubleValue|boolValue}}]}) into a flat key→value map. Unknown / nested value
     * shapes (arrayValue / kvlistValue) are rendered to their JSON text so nothing
     * is silently lost.
     */
    private Map<String, Object> readAttributes(JsonNode attrs) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (attrs == null || !attrs.isArray()) {
            return map;
        }
        for (JsonNode a : attrs) {
            JsonNode keyNode = a.get("key");
            if (keyNode == null || !keyNode.isTextual()) {
                continue;
            }
            map.put(keyNode.asText(), readAnyValue(a.get("value")));
        }
        return map;
    }

    private Object readAnyValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.has("stringValue")) {
            return value.get("stringValue").asText();
        }
        if (value.has("intValue")) {
            JsonNode iv = value.get("intValue");
            // OTLP encodes intValue as a string-or-number; normalize to long.
            try {
                return Long.parseLong(iv.asText());
            } catch (NumberFormatException e) {
                return iv.asText();
            }
        }
        if (value.has("doubleValue")) {
            return value.get("doubleValue").asDouble();
        }
        if (value.has("boolValue")) {
            return value.get("boolValue").asBoolean();
        }
        // arrayValue / kvlistValue / unknown — keep the raw JSON text.
        return value.toString();
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
