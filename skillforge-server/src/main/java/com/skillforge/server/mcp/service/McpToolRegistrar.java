package com.skillforge.server.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.tools.mcp.adapter.McpToolAdapter;
import com.skillforge.tools.mcp.protocol.McpToolDescriptor;
import com.skillforge.tools.mcp.session.McpServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Adapts {@link McpServerSession} tool descriptors to {@link McpToolAdapter}s and
 * registers them in the global {@link SkillRegistry} under the namespaced
 * name {@code mcp_<server>_<tool>}.
 *
 * <p>Invariants:
 * <ul>
 *   <li>INV-3: server name must match {@code [a-z0-9_]+}; otherwise registration is
 *       refused (reachable defensively even though DB CHECK already enforces it).</li>
 *   <li>Conflict policy: if an existing tool name in the registry collides with a
 *       new MCP tool's namespaced name, the new registration is refused (logged ERROR)
 *       — a server admin can rename the MCP server to resolve.</li>
 *   <li>{@link #unregisterAllForServer} is idempotent and removes only the names this
 *       registrar previously installed; built-in tool registrations are never touched.</li>
 * </ul>
 */
public class McpToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    /** server name → set of tool names this registrar installed (for clean unregister). */
    private final ConcurrentHashMap<String, Set<String>> installedByServer = new ConcurrentHashMap<>();

    public McpToolRegistrar(SkillRegistry skillRegistry, ObjectMapper objectMapper) {
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Register every tool advertised by {@code session.cachedTools()} into the
     * SkillRegistry. Returns the list of registered descriptors (subset that was
     * accepted). Skipped entries (collision, malformed) are logged.
     */
    public List<Map<String, Object>> registerAllForServer(McpServerSession session) {
        String serverName = session.getServerName();
        if (!NAME_PATTERN.matcher(serverName).matches()) {
            log.error("INV-3 violation: refusing to register tools for invalid server name '{}' "
                    + "(must match [a-z0-9_]+)", serverName);
            return List.of();
        }

        // Defensive cleanup of any prior installation for this server (re-registrations).
        unregisterAllForServer(serverName);

        Set<String> installed = new HashSet<>();
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (McpToolDescriptor desc : session.cachedTools()) {
            String registeredName = McpToolAdapter.buildName(serverName, desc.name());
            if (skillRegistry.getTool(registeredName).isPresent()) {
                log.error("MCP tool name collision: '{}' (server={}, tool={}) — refusing registration",
                        registeredName, serverName, desc.name());
                continue;
            }
            McpToolAdapter adapter = new McpToolAdapter(serverName, session, desc, objectMapper);
            skillRegistry.registerTool(adapter);
            installed.add(registeredName);
            metadata.add(adapter.toMetadata());
            log.info("Registered MCP tool '{}' (from server '{}')", registeredName, serverName);
        }
        installedByServer.put(serverName, installed);
        return metadata;
    }

    /** Remove every tool this registrar previously installed for the given server. */
    public void unregisterAllForServer(String serverName) {
        Set<String> installed = installedByServer.remove(serverName);
        if (installed == null || installed.isEmpty()) return;
        for (String toolName : installed) {
            skillRegistry.unregisterTool(toolName);
            log.info("Unregistered MCP tool '{}' (from server '{}')", toolName, serverName);
        }
    }

    /** Returns the names installed for a given server. Empty if none. */
    public Set<String> installedNames(String serverName) {
        Set<String> set = installedByServer.get(serverName);
        return set == null ? Set.of() : Set.copyOf(set);
    }
}
