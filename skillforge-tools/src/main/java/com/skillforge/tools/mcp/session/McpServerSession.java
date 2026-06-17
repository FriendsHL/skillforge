package com.skillforge.tools.mcp.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpRequest;
import com.skillforge.tools.mcp.protocol.McpResponse;
import com.skillforge.tools.mcp.protocol.McpToolDescriptor;
import com.skillforge.tools.mcp.transport.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One MCP server = one Session. Owns the {@link McpTransport} and the protocol state
 * machine: {@code initialize → notifications/initialized → tools/list (cached) →
 * tools/call (per request)}.
 *
 * <p>State invariants:
 * <ul>
 *   <li>{@link #connect()} must run before any {@link #callTool}; it spawns the
 *       transport, sends {@code initialize}, and caches {@code tools/list}.</li>
 *   <li>{@link #cachedTools()} is invalidated on {@link #disconnect} so a subsequent
 *       reconnect re-queries (INV-8 — stale cache after server restart was the
 *       footgun this guards).</li>
 *   <li>Connection is single-threaded by design (one server, one stdio process).
 *       Concurrent {@link #callTool} dispatch is allowed and serialized at the
 *       transport layer (per-id mailbox).</li>
 * </ul>
 */
public class McpServerSession {

    private static final Logger log = LoggerFactory.getLogger(McpServerSession.class);

    /** MCP protocol version we speak (server can downgrade in initialize result). */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    /** Default per-request timeout. tools/call may be longer-lived than tools/list. */
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000L;
    private static final long INITIALIZE_TIMEOUT_MS = 30_000L;
    private static final long LIST_TOOLS_TIMEOUT_MS = 15_000L;

    private final String serverName;
    private final McpTransport transport;
    private final ObjectMapper objectMapper;
    private final AtomicLong idGen = new AtomicLong(1);

    private volatile boolean connected = false;
    private volatile List<McpToolDescriptor> cachedTools = Collections.emptyList();

    public McpServerSession(String serverName, McpTransport transport, ObjectMapper objectMapper) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName is required");
        }
        if (transport == null) throw new IllegalArgumentException("transport is required");
        if (objectMapper == null) throw new IllegalArgumentException("objectMapper is required");
        this.serverName = serverName;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    public String getServerName() { return serverName; }

    public boolean isConnected() { return connected; }

    /**
     * Open the transport, run the {@code initialize} handshake, and load
     * {@code tools/list} into {@link #cachedTools}.
     *
     * @throws McpClientException if any phase fails. Caller is responsible for
     *         logging + marking server status=error in the lifecycle layer (INV-2).
     */
    public synchronized void connect() {
        if (connected) return;
        transport.start();
        try {
            // -------- initialize --------
            Map<String, Object> initParams = new LinkedHashMap<>();
            initParams.put("protocolVersion", PROTOCOL_VERSION);
            initParams.put("capabilities", Map.of());
            initParams.put("clientInfo", Map.of(
                    "name", "skillforge-mcp-client",
                    "version", "1.0.0"
            ));
            McpResponse initResp = transport.sendRequest(
                    McpRequest.of(idGen.getAndIncrement(), "initialize", initParams),
                    INITIALIZE_TIMEOUT_MS);
            if (initResp.isError()) {
                throw new McpClientException("MCP server '" + serverName + "' initialize failed: "
                        + initResp.error().message());
            }
            // Per spec, send notifications/initialized after a successful initialize.
            transport.sendNotification(McpRequest.of(null, "notifications/initialized", Map.of()));

            // -------- tools/list --------
            McpResponse listResp = transport.sendRequest(
                    McpRequest.of(idGen.getAndIncrement(), "tools/list", Map.of()),
                    LIST_TOOLS_TIMEOUT_MS);
            if (listResp.isError()) {
                throw new McpClientException("MCP server '" + serverName + "' tools/list failed: "
                        + listResp.error().message());
            }
            this.cachedTools = parseToolsList(listResp.result());
            this.connected = true;
            log.info("MCP server '{}' connected: {} tool(s) advertised", serverName, cachedTools.size());
        } catch (RuntimeException re) {
            // best-effort cleanup (don't leak the subprocess on failed handshake)
            try { transport.close(); } catch (Exception ignored) {}
            throw re;
        }
    }

    /** Disconnect: close the transport AND invalidate the cache (INV-8). */
    public synchronized void disconnect() {
        connected = false;
        cachedTools = Collections.emptyList();
        try { transport.close(); } catch (Exception e) {
            log.warn("Error closing transport for '{}': {}", serverName, e.getMessage());
        }
    }

    /** Cached {@code tools/list} (immutable snapshot). Empty if not connected. */
    public List<McpToolDescriptor> cachedTools() {
        return cachedTools;
    }

    /**
     * Invoke a tool on the server. Returns the raw {@code result} object from the
     * JSON-RPC response (typically {@code {"content": [...], "isError": bool}}).
     *
     * @throws McpClientException on transport failure, JSON-RPC error response, or
     *         server-reported {@code isError: true} (caller may downgrade to
     *         SkillResult.error rather than re-throw).
     */
    public Object callTool(String toolName, Map<String, Object> arguments) {
        if (!connected) {
            throw new McpClientException("MCP server '" + serverName + "' is not connected");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());
        McpResponse resp = transport.sendRequest(
                McpRequest.of(idGen.getAndIncrement(), "tools/call", params),
                DEFAULT_REQUEST_TIMEOUT_MS);
        if (resp.isError()) {
            throw new McpClientException("MCP server '" + serverName + "' tools/call '" + toolName
                    + "' returned JSON-RPC error: " + resp.error().message());
        }
        return resp.result();
    }

    @SuppressWarnings("unchecked")
    private List<McpToolDescriptor> parseToolsList(Object result) {
        if (result == null) return Collections.emptyList();
        // result shape: {"tools": [{name, description, inputSchema}, ...]}
        List<McpToolDescriptor> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.valueToTree(result);
            JsonNode tools = root.path("tools");
            if (!tools.isArray()) {
                log.warn("MCP server '{}' tools/list returned non-array tools: {}",
                        serverName, root.toString());
                return Collections.emptyList();
            }
            for (JsonNode t : tools) {
                McpToolDescriptor desc = objectMapper.treeToValue(t, McpToolDescriptor.class);
                if (desc.name() == null || desc.name().isBlank()) {
                    log.warn("MCP server '{}' tools/list entry missing name: {}", serverName, t);
                    continue;
                }
                out.add(desc);
            }
        } catch (Exception e) {
            throw new McpClientException("Failed to parse tools/list from '" + serverName + "': "
                    + e.getMessage(), e);
        }
        return Collections.unmodifiableList(out);
    }
}
