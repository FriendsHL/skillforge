package com.skillforge.server.mcp.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.mcp.entity.McpServerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Response DTO for {@code /api/mcp-servers} list / detail endpoints.
 *
 * <p>{@code status}: live runtime state from {@code McpServerSessionRegistry}
 * ({@code "connected"} / {@code "disconnected"} / {@code "disabled"}).
 *
 * <p>{@code tools}: from the live session's cached {@code tools/list} response
 * if connected; empty otherwise. Caller (controller) populates these via
 * {@link #from} variants.
 */
public class McpServerResponse {

    private static final Logger log = LoggerFactory.getLogger(McpServerResponse.class);

    /**
     * r1 W3 (security): API responses must never echo literal env values back —
     * a real GitHub PAT / OpenAI key would otherwise leak in every list page load
     * (browser cache, network panel, server logs). Only intact placeholders
     * matching this exact regex (whole-string match) are passed through; anything
     * else (literal values, mixed strings, empty) collapses to {@link #MASKED_VALUE}.
     *
     * <p>Round-trip note: when the operator edits a server in the UI they see
     * {@code "***"} in the env input; if they save without retyping, the PUT
     * payload contains {@code "***"} which would clobber the real secret. The
     * controller layer must surface this in the UI (placeholder text "leave blank
     * to keep current") OR the FE should omit the env field on PUT when not
     * touched. MVP: documented; future hardening can add server-side
     * "***" → preserve-existing semantics.
     */
    static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("^\\$\\{[A-Za-z_][A-Za-z0-9_]*}$");

    /**
     * Sentinel returned in place of literal env values. Public + canonical so the
     * service layer can recognize FE round-trips ({@code McpServerService.update}
     * preserves the original secret when an inbound env value equals this sentinel —
     * see r2-W3 follow-up).
     */
    public static final String MASKED_VALUE = "***";

    private Long id;
    private String name;
    private String command;
    private List<String> args;
    private Map<String, String> env;
    private String description;
    private boolean enabled;
    private String status;
    private List<Map<String, Object>> tools;
    private Instant createdAt;
    private Instant updatedAt;

    public McpServerResponse() {}

    public static McpServerResponse from(McpServerEntity entity, ObjectMapper objectMapper) {
        return from(entity, objectMapper, "disabled", Collections.emptyList());
    }

    @SuppressWarnings({"unchecked"})
    public static McpServerResponse from(McpServerEntity entity,
                                         ObjectMapper objectMapper,
                                         String runtimeStatus,
                                         List<Map<String, Object>> tools) {
        McpServerResponse r = new McpServerResponse();
        r.id = entity.getId();
        r.name = entity.getName();
        r.command = entity.getCommand();
        try {
            r.args = objectMapper.readValue(entity.getArgs(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse args JSON for server {} ({}): {}",
                    entity.getId(), entity.getName(), e.getMessage());
            r.args = Collections.emptyList();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(entity.getEnv(),
                    new TypeReference<Map<String, String>>() {});
            r.env = maskEnv(parsed);
        } catch (Exception e) {
            log.warn("Failed to parse env JSON for server {} ({}): {}",
                    entity.getId(), entity.getName(), e.getMessage());
            r.env = Collections.emptyMap();
        }
        r.description = entity.getDescription();
        r.enabled = entity.isEnabled();
        r.status = runtimeStatus;
        r.tools = tools != null ? tools : Collections.emptyList();
        r.createdAt = entity.getCreatedAt();
        r.updatedAt = entity.getUpdatedAt();
        return r;
    }

    /**
     * r1 W3 helper: mask every literal env value, pass through {@code ${VAR}} placeholders.
     * Preserves insertion order (LinkedHashMap) so the FE shows env vars in a stable order.
     * Null map → empty map; null/empty value → masked (defensive — admin shouldn't store
     * empty values but we don't leak them either).
     */
    static Map<String, String> maskEnv(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            masked.put(e.getKey(), maskEnvValue(e.getValue()));
        }
        return masked;
    }

    /** Pure helper exposed package-private for unit tests. */
    static String maskEnvValue(String value) {
        if (value == null) return null;
        if (PLACEHOLDER_PATTERN.matcher(value).matches()) {
            return value;
        }
        return MASKED_VALUE;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCommand() { return command; }
    public List<String> getArgs() { return args; }
    public Map<String, String> getEnv() { return env; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public String getStatus() { return status; }
    public List<Map<String, Object>> getTools() { return tools; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
