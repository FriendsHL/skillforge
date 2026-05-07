package com.skillforge.server.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.mcp.dto.McpServerRequest;
import com.skillforge.server.mcp.dto.McpServerResponse;
import com.skillforge.server.mcp.entity.McpServerEntity;
import com.skillforge.server.mcp.event.McpServerDeletedEvent;
import com.skillforge.server.mcp.event.McpServerUpsertedEvent;
import com.skillforge.server.mcp.exception.McpServerInUseException;
import com.skillforge.server.mcp.exception.McpServerNotFoundException;
import com.skillforge.server.mcp.repository.McpServerRepository;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * P11 MCP-CLIENT service layer. Owns:
 * <ul>
 *   <li>CRUD on {@code t_mcp_server}</li>
 *   <li>Name format / shape validation (mirrors DB CHECK so 400 happens before INSERT)</li>
 *   <li>Args / env JSON serialization to canonical TEXT</li>
 *   <li>{@link McpServerInUseException} for INV-12 — DELETE blocked while any
 *       agent still lists the server in {@code mcp_server_ids}</li>
 *   <li>Publishing {@link McpServerUpsertedEvent} / {@link McpServerDeletedEvent}
 *       so {@code McpServerLifecycle} can reload sessions in AFTER_COMMIT phase
 *       (INV-6).</li>
 * </ul>
 *
 * <p>Ownership note: MCP servers are <em>global</em> resources (Q3 ratify — admin
 * configures the pool, agents pick a subset). The controller layer enforces the
 * "admin-only mutation" gate; this service trusts its caller. We still make
 * {@code currentUserId} a required argument so any future per-user / multi-tenant
 * upgrade has a clean inflection point.
 */
@Service
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    /** Mirror of the DB CHECK constraint (chk_mcp_server_name_shape, INV-3). */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]+$");
    private static final int NAME_MAX_LEN = 32;

    private final McpServerRepository repository;
    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public McpServerService(McpServerRepository repository,
                            AgentRepository agentRepository,
                            ApplicationEventPublisher eventPublisher,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<McpServerEntity> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public McpServerEntity get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new McpServerNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public McpServerEntity getByName(String name) {
        return repository.findByName(name)
                .orElseThrow(() -> new McpServerNotFoundException(name));
    }

    @Transactional
    public McpServerEntity create(Long currentUserId, McpServerRequest req) {
        validateRequired(currentUserId);
        if (req == null) throw new IllegalArgumentException("request body is required");

        validateName(req.getName());
        if (req.getCommand() == null || req.getCommand().isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        if (repository.existsByName(req.getName())) {
            throw new IllegalArgumentException("MCP server name already exists: " + req.getName());
        }

        McpServerEntity entity = new McpServerEntity();
        entity.setName(req.getName().trim());
        entity.setCommand(req.getCommand().trim());
        entity.setArgs(serializeArgs(req.getArgs()));
        entity.setEnv(serializeEnv(req.getEnv()));
        entity.setDescription(req.getDescription());
        entity.setEnabled(req.getEnabled() == null ? true : req.getEnabled());

        McpServerEntity saved = repository.save(entity);
        eventPublisher.publishEvent(new McpServerUpsertedEvent(saved.getId(), saved.getName()));
        log.info("Created MCP server id={} name={} enabled={}", saved.getId(), saved.getName(), saved.isEnabled());
        return saved;
    }

    @Transactional
    public McpServerEntity update(Long id, Long currentUserId, McpServerRequest req) {
        validateRequired(currentUserId);
        if (req == null) throw new IllegalArgumentException("request body is required");

        McpServerEntity entity = get(id);
        // Name is immutable post-create — too many downstream invariants pinned to it
        // (registered tool prefix, agent.mcp_server_ids comma-list reference). Reject
        // any rename attempt cleanly at the boundary.
        if (req.getName() != null && !req.getName().equals(entity.getName())) {
            throw new IllegalArgumentException(
                    "MCP server name is immutable after creation; current=" + entity.getName()
                            + ", requested=" + req.getName());
        }
        if (req.getCommand() != null) {
            if (req.getCommand().isBlank()) throw new IllegalArgumentException("command must not be blank");
            entity.setCommand(req.getCommand().trim());
        }
        if (req.getArgs() != null) entity.setArgs(serializeArgs(req.getArgs()));
        if (req.getEnv() != null) {
            // r2-W3 follow-up (server-side preserve-on-mask):
            // McpServerResponse.from(...) replaces literal env values with MASKED_VALUE
            // ("***") so an admin viewing the server in the UI never sees real secrets.
            // If the FE round-trips that masked value back on a PUT (e.g. operator only
            // edited a different field but the env form posted everything), we'd silently
            // overwrite the real secret with the literal "***" string. Defend against that
            // server-side: any inbound value equal to MASKED_VALUE for a key that already
            // exists in the persisted env reuses the existing value. New keys with "***"
            // pass through (operator error — visible in DB).
            entity.setEnv(serializeEnv(mergeEnvPreservingMasked(parseEnv(entity), req.getEnv())));
        }
        if (req.getDescription() != null) entity.setDescription(req.getDescription());
        if (req.getEnabled() != null) entity.setEnabled(req.getEnabled());

        McpServerEntity saved = repository.save(entity);
        eventPublisher.publishEvent(new McpServerUpsertedEvent(saved.getId(), saved.getName()));
        log.info("Updated MCP server id={} name={} enabled={}", saved.getId(), saved.getName(), saved.isEnabled());
        return saved;
    }

    /**
     * INV-12: refuse delete if any agent still lists this server in {@code mcp_server_ids}.
     * Returns 409 via {@link McpServerInUseException} carrying the agent names so the
     * operator can unbind first.
     */
    @Transactional
    public void delete(Long id, Long currentUserId) {
        validateRequired(currentUserId);
        McpServerEntity entity = get(id);
        List<String> referencingAgents = findAgentsReferencing(entity.getName());
        if (!referencingAgents.isEmpty()) {
            throw new McpServerInUseException(entity.getName(), referencingAgents);
        }
        repository.delete(entity);
        eventPublisher.publishEvent(new McpServerDeletedEvent(entity.getId(), entity.getName()));
        log.info("Deleted MCP server id={} name={}", entity.getId(), entity.getName());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void validateRequired(Long currentUserId) {
        if (currentUserId == null) throw new IllegalArgumentException("currentUserId is required");
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX_LEN) {
            throw new IllegalArgumentException(
                    "name exceeds " + NAME_MAX_LEN + " chars: " + trimmed);
        }
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "name must match [a-z0-9_]+ (got: '" + trimmed + "')");
        }
    }

    private String serializeArgs(List<String> args) {
        try {
            return objectMapper.writeValueAsString(args == null ? List.of() : args);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid args: " + e.getMessage(), e);
        }
    }

    private String serializeEnv(Map<String, String> env) {
        try {
            return objectMapper.writeValueAsString(env == null ? Map.of() : env);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid env: " + e.getMessage(), e);
        }
    }

    /**
     * r2-W3 follow-up: merge an inbound env map (from PUT) with the existing persisted
     * env so {@link McpServerResponse#MASKED_VALUE} sentinels round-tripped from the
     * UI don't clobber the real secret.
     *
     * <p>Semantics (preserves existing PUT-replaces shape):
     * <ul>
     *   <li>Iterate over the request keys only — keys absent from the request are dropped
     *       (full replace, matching pre-W3 behavior).</li>
     *   <li>For each request entry, if the value equals MASKED_VALUE <strong>and</strong>
     *       the key already has a persisted value → keep the persisted value.</li>
     *   <li>Otherwise → use the request value as-is (handles new key creation, real
     *       updates, and placeholder pass-through).</li>
     *   <li>Edge case: PUT {"NEW_KEY":"***"} where NEW_KEY isn't in existing → store
     *       literal "***" so the operator can see their mistake (don't silently swallow).</li>
     * </ul>
     *
     * <p>Package-private for unit tests.
     */
    static Map<String, String> mergeEnvPreservingMasked(Map<String, String> existing,
                                                         Map<String, String> incoming) {
        Map<String, String> safeExisting = existing != null ? existing : Map.of();
        Map<String, String> merged = new LinkedHashMap<>();
        if (incoming == null) return merged;
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (McpServerResponse.MASKED_VALUE.equals(value) && safeExisting.containsKey(key)) {
                merged.put(key, safeExisting.get(key));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }

    /**
     * Find agents whose {@code mcp_server_ids} comma-list contains the given server name.
     *
     * <p>We deliberately do <strong>not</strong> use a SQL {@code LIKE %name%} query because
     * a server named {@code "time"} would match {@code "time2"} (both substrings).
     * Pull all agents and split the comma-list in Java so {@code "time"} only matches
     * the literal entry. With ~10s-100s agents this is fine; if it grows, switch to
     * a dedicated join table.
     */
    private List<String> findAgentsReferencing(String serverName) {
        List<String> hits = new ArrayList<>();
        for (AgentEntity agent : agentRepository.findAll()) {
            String mcpIds = agent.getMcpServerIds();
            if (mcpIds == null || mcpIds.isBlank()) continue;
            for (String token : mcpIds.split(",")) {
                if (token.trim().equals(serverName)) {
                    hits.add(agent.getName() != null ? agent.getName() : ("agent#" + agent.getId()));
                    break;
                }
            }
        }
        return hits;
    }

    /** Helper for callers (lifecycle, ChatService) to parse a comma-list into a clean set. */
    public static List<String> parseServerIds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /** Parse args TEXT JSON back to List for runtime use. */
    public List<String> parseArgs(McpServerEntity entity) {
        try {
            return objectMapper.readValue(entity.getArgs(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse args for server {}: {}", entity.getName(), e.getMessage());
            return List.of();
        }
    }

    /** Parse env TEXT JSON back to Map for runtime use. */
    public Map<String, String> parseEnv(McpServerEntity entity) {
        try {
            return objectMapper.readValue(entity.getEnv(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse env for server {}: {}", entity.getName(), e.getMessage());
            return Map.of();
        }
    }
}
