package com.skillforge.server.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.mcp.entity.McpServerEntity;
import com.skillforge.server.mcp.event.McpServerDeletedEvent;
import com.skillforge.server.mcp.event.McpServerUpsertedEvent;
import com.skillforge.server.mcp.exception.McpServerNotFoundException;
import com.skillforge.server.mcp.repository.McpServerRepository;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpToolDescriptor;
import com.skillforge.tools.mcp.session.McpServerSession;
import com.skillforge.tools.mcp.session.McpServerSessionRegistry;
import com.skillforge.tools.mcp.transport.McpHttpTransport;
import com.skillforge.tools.mcp.transport.McpStdioTransport;
import com.skillforge.tools.mcp.transport.McpTransport;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the runtime lifecycle of MCP server subprocesses:
 * <ul>
 *   <li>{@link #onApplicationReady} — best-effort start every {@code enabled=true}
 *       row at boot (INV-2; one bad server doesn't halt the others).</li>
 *   <li>{@link #onUpserted} / {@link #onDeleted} — react to CRUD AFTER_COMMIT
 *       (INV-6; same pattern as {@code UserTaskScheduler}).</li>
 *   <li>{@link #shutdown} — gracefully disconnect every active session at JVM exit
 *       (INV-7).</li>
 * </ul>
 *
 * <p>Connection resilience (INV-10): we don't poll for liveness; instead a tool
 * call into a dead session bubbles a {@link McpClientException} that the adapter
 * surfaces as a tool error. The next operator-triggered upsert (toggle enabled
 * off → on, or update) re-spawns. A periodic reconnect is intentionally deferred
 * to V2 (Q-A scope).
 *
 * <p>Env var resolution (INV-5): values containing {@code ${VAR_NAME}} are
 * substituted from {@link System#getenv(String)} at spawn time. Unresolved
 * placeholders pass through unchanged so the subprocess sees the literal
 * (operator gets a clear failure mode rather than silent empty string).
 *
 * <p>Disabled by setting {@code skillforge.mcp.enabled=false} in application.yml.
 */
@Component
public class McpServerLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpServerLifecycle.class);

    /** ${VAR_NAME} env-var placeholder pattern (INV-5). */
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final McpServerRepository repository;
    private final McpServerService service;
    private final McpServerSessionRegistry sessionRegistry;
    private final McpToolRegistrar toolRegistrar;
    private final ObjectMapper objectMapper;
    private final boolean mcpEnabled;

    /**
     * Shared HTTP client for all http-transport MCP servers. Thread-safe; reused
     * across sessions (connection pooling). Per-request call timeouts are applied by
     * {@link McpHttpTransport} via {@code newBuilder().callTimeout(...)}, so the base
     * read/connect timeouts here are just generous outer bounds.
     */
    private final OkHttpClient httpClient;

    public McpServerLifecycle(McpServerRepository repository,
                              McpServerService service,
                              McpServerSessionRegistry sessionRegistry,
                              McpToolRegistrar toolRegistrar,
                              ObjectMapper objectMapper,
                              @Value("${skillforge.mcp.enabled:true}") boolean mcpEnabled) {
        this.repository = repository;
        this.service = service;
        this.sessionRegistry = sessionRegistry;
        this.toolRegistrar = toolRegistrar;
        this.objectMapper = objectMapper;
        this.mcpEnabled = mcpEnabled;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    public boolean isMcpEnabled() {
        return mcpEnabled;
    }

    // -----------------------------------------------------------------------
    // boot / shutdown
    // -----------------------------------------------------------------------

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (!mcpEnabled) {
            log.info("MCP feature disabled (skillforge.mcp.enabled=false) — skipping startup");
            return;
        }
        List<McpServerEntity> enabled = repository.findByEnabledTrue();
        log.info("McpServerLifecycle startup: connecting {} enabled MCP server(s)", enabled.size());
        for (McpServerEntity entity : enabled) {
            try {
                connectAndRegister(entity);
            } catch (Exception e) {
                // INV-2: best-effort — log and continue, don't fail-fast.
                log.warn("MCP server '{}' failed to start: {} (continuing)",
                        entity.getName(), e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("McpServerLifecycle shutdown: closing {} session(s)", sessionRegistry.size());
        for (McpServerSession session : new ArrayList<>(sessionRegistry.all())) {
            try {
                toolRegistrar.unregisterAllForServer(session.getServerName());
                session.disconnect();
                sessionRegistry.remove(session.getServerName());
            } catch (Exception e) {
                log.warn("Error disconnecting MCP server '{}': {}",
                        session.getServerName(), e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // CRUD reload listeners (INV-6)
    // -----------------------------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUpserted(McpServerUpsertedEvent event) {
        if (!mcpEnabled) return;
        // re-read post-commit state (don't trust the event payload alone — DB is truth)
        Optional<McpServerEntity> entityOpt = repository.findById(event.serverId());
        if (entityOpt.isEmpty()) {
            // raced with a delete that committed in between → leave nothing running.
            disconnectIfPresent(event.serverName());
            return;
        }
        McpServerEntity entity = entityOpt.get();
        // tear down the prior session (if any) before reconnecting with new config
        disconnectIfPresent(entity.getName());
        if (!entity.isEnabled()) {
            log.info("MCP server '{}' is disabled — left disconnected after upsert", entity.getName());
            return;
        }
        try {
            connectAndRegister(entity);
        } catch (Exception e) {
            log.warn("MCP server '{}' reconnect after upsert failed: {}",
                    entity.getName(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleted(McpServerDeletedEvent event) {
        if (!mcpEnabled) return;
        disconnectIfPresent(event.serverName());
    }

    // -----------------------------------------------------------------------
    // dry-run "test connection"
    // -----------------------------------------------------------------------

    /**
     * Spawn the server, run initialize + tools/list, and immediately close. Used by
     * the dashboard "Test Connection" button. Does NOT touch the registry — purely
     * a probe. Returns the advertised tool descriptors.
     */
    public List<Map<String, Object>> testConnection(Long id) {
        McpServerEntity entity = repository.findById(id)
                .orElseThrow(() -> new McpServerNotFoundException(id));
        McpTransport transport = buildTransport(entity);
        McpServerSession session = new McpServerSession(entity.getName(), transport, objectMapper);
        try {
            session.connect();
            List<Map<String, Object>> out = new ArrayList<>();
            for (McpToolDescriptor desc : session.cachedTools()) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", desc.name());
                m.put("description", desc.description());
                m.put("inputSchema", desc.inputSchema());
                m.put("registeredName", "mcp_" + entity.getName() + "_" + desc.name());
                out.add(m);
            }
            return out;
        } finally {
            try { session.disconnect(); } catch (Exception ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // status / metadata helpers (called by controller for response shaping)
    // -----------------------------------------------------------------------

    public String runtimeStatus(McpServerEntity entity) {
        if (!entity.isEnabled()) return "disabled";
        Optional<McpServerSession> sess = sessionRegistry.get(entity.getName());
        if (sess.isEmpty()) return "disconnected";
        return sess.get().isConnected() ? "connected" : "disconnected";
    }

    public List<Map<String, Object>> liveTools(McpServerEntity entity) {
        Optional<McpServerSession> sess = sessionRegistry.get(entity.getName());
        if (sess.isEmpty() || !sess.get().isConnected()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpToolDescriptor desc : sess.get().cachedTools()) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", desc.name());
            m.put("registeredName", "mcp_" + entity.getName() + "_" + desc.name());
            m.put("description", desc.description());
            m.put("inputSchema", desc.inputSchema());
            out.add(m);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    private void connectAndRegister(McpServerEntity entity) {
        McpTransport transport = buildTransport(entity);
        McpServerSession session = new McpServerSession(entity.getName(), transport, objectMapper);
        // connect() will throw on failure; sessionRegistry stays clean
        session.connect();
        sessionRegistry.put(session);
        toolRegistrar.registerAllForServer(session);
    }

    private void disconnectIfPresent(String serverName) {
        Optional<McpServerSession> sess = sessionRegistry.remove(serverName);
        if (sess.isEmpty()) return;
        try {
            toolRegistrar.unregisterAllForServer(serverName);
            sess.get().disconnect();
        } catch (Exception e) {
            log.warn("Error disconnecting MCP server '{}': {}", serverName, e.getMessage());
        }
    }

    private McpTransport buildTransport(McpServerEntity entity) {
        if (McpServerService.TRANSPORT_HTTP.equals(entity.getTransport())) {
            Map<String, String> resolvedHeaders = resolveHeaders(service.parseHeaders(entity));
            return new McpHttpTransport(entity.getName(), entity.getUrl(),
                    resolvedHeaders, objectMapper, httpClient);
        }
        // stdio (default)
        List<String> command = new ArrayList<>();
        command.add(entity.getCommand());
        command.addAll(service.parseArgs(entity));
        Map<String, String> env = resolveEnv(service.parseEnv(entity));
        return new McpStdioTransport(entity.getName(), command, env, objectMapper);
    }

    /**
     * INV-5: substitute {@code ${VAR_NAME}} placeholders in env values from
     * {@link System#getenv}. Unresolved placeholders are left as-is so the operator
     * can spot the problem in the spawned-process logs (rather than silently
     * passing an empty string and getting a confusing 401 from the upstream).
     */
    static Map<String, String> resolveEnv(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            out.put(e.getKey(), substitute(e.getValue()));
        }
        return out;
    }

    /**
     * INV-5 for http transport headers: substitute {@code ${VAR_NAME}} placeholders in
     * header values from {@link System#getenv} (e.g. {@code Authorization: Bearer ${KEY}}).
     * Same semantics as {@link #resolveEnv} — kept as a named helper for call-site clarity.
     */
    static Map<String, String> resolveHeaders(Map<String, String> raw) {
        return resolveEnv(raw);
    }

    private static String substitute(String value) {
        if (value == null || value.indexOf("${") < 0) return value;
        Matcher m = ENV_PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String resolved = System.getenv(varName);
            // Quote the replacement so $ and \ in the env value are not treated
            // as backreferences/escapes by appendReplacement.
            String replacement;
            if (resolved == null) {
                log.warn("MCP env placeholder ${{}} unresolved (System.getenv returned null) — passing through literal", varName);
                replacement = Matcher.quoteReplacement(m.group(0));
            } else {
                replacement = Matcher.quoteReplacement(resolved);
            }
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
