package com.skillforge.tools.mcp.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpToolDescriptor;
import com.skillforge.tools.mcp.session.McpServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts one MCP-server tool to the SkillForge {@link Tool} interface so the agent
 * loop can dispatch it identically to a built-in Java tool.
 *
 * <p>Key design choices:
 * <ul>
 *   <li><strong>Name namespacing (INV-3)</strong>: {@code mcp_<serverName>_<originalToolName>}.
 *       The {@code McpToolRegistrar} validates server name format ({@code [a-z0-9_]+})
 *       and rejects collisions with already-registered tools.</li>
 *   <li><strong>Schema passthrough (INV-11)</strong>: {@link McpToolDescriptor#inputSchema()}
 *       is JSON Schema; we pass it to the LLM unchanged.</li>
 *   <li><strong>Output normalization</strong>: MCP {@code tools/call} returns
 *       {@code {"content": [{type:"text", text:"..."}, ...], "isError": bool}}.
 *       We concatenate the text segments and route {@code isError=true} to
 *       {@link SkillResult#error}. Non-text content (image/resource) is rendered
 *       as a placeholder marker so the LLM at least sees the type.</li>
 *   <li><strong>Failure isolation (INV-1, INV-10)</strong>: any {@link McpClientException}
 *       from the session layer (server crashed, transport closed, timeout) is wrapped
 *       into a {@link SkillResult#error}; the agent loop continues.</li>
 * </ul>
 */
public class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    /** Tool name prefix shared by every MCP-sourced tool. */
    public static final String NAME_PREFIX = "mcp_";

    private final String registeredName;
    private final String serverName;
    private final McpServerSession session;
    private final McpToolDescriptor descriptor;
    private final ObjectMapper objectMapper;

    public McpToolAdapter(String serverName,
                          McpServerSession session,
                          McpToolDescriptor descriptor,
                          ObjectMapper objectMapper) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName is required");
        }
        if (session == null) throw new IllegalArgumentException("session is required");
        if (descriptor == null) throw new IllegalArgumentException("descriptor is required");
        if (descriptor.name() == null || descriptor.name().isBlank()) {
            throw new IllegalArgumentException("descriptor.name is required");
        }
        if (objectMapper == null) throw new IllegalArgumentException("objectMapper is required");
        this.serverName = serverName;
        this.session = session;
        this.descriptor = descriptor;
        this.objectMapper = objectMapper;
        this.registeredName = buildName(serverName, descriptor.name());
    }

    /** {@code mcp_<server>_<originalToolName>} — public so the registrar can dedup before construct. */
    public static String buildName(String serverName, String toolName) {
        return NAME_PREFIX + serverName + "_" + toolName;
    }

    public String getServerName() { return serverName; }

    public String getOriginalToolName() { return descriptor.name(); }

    @Override
    public String getName() {
        return registeredName;
    }

    @Override
    public String getDescription() {
        // Prefix with the server name so the LLM can disambiguate (e.g. "[mcp:time] ...").
        String desc = descriptor.description() != null ? descriptor.description() : "";
        return "[mcp:" + serverName + "] " + desc;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> schema = descriptor.inputSchema();
        if (schema == null || schema.isEmpty()) {
            // Fallback: empty object schema so the model can still call it with no params.
            schema = Map.of("type", "object", "properties", Map.of());
        }
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            Object raw = session.callTool(descriptor.name(), input);
            return parseToolCallResult(raw);
        } catch (McpClientException ce) {
            log.warn("MCP tool '{}' execution failed: {}", registeredName, ce.getMessage());
            return SkillResult.error("MCP tool error: " + ce.getMessage());
        } catch (Exception e) {
            log.error("MCP tool '{}' execution unexpected error", registeredName, e);
            return SkillResult.error("Unexpected MCP error: " + e.getMessage());
        }
    }

    /** Read-only flag is unknown for arbitrary MCP tools — assume mutating (default false). */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * MCP {@code tools/call} result shape:
     * <pre>{@code
     * {
     *   "content": [{"type":"text","text":"..."}, {"type":"image","data":"...","mimeType":"..."}],
     *   "isError": false
     * }
     * }</pre>
     */
    private SkillResult parseToolCallResult(Object raw) {
        if (raw == null) {
            return SkillResult.success("");
        }
        JsonNode root = objectMapper.valueToTree(raw);
        boolean isError = root.path("isError").asBoolean(false);
        JsonNode content = root.path("content");
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText("text");
                if ("text".equals(type)) {
                    sb.append(item.path("text").asText(""));
                } else if ("image".equals(type)) {
                    String mime = item.path("mimeType").asText("application/octet-stream");
                    sb.append("[image: ").append(mime).append(", base64 data omitted]");
                } else if ("resource".equals(type)) {
                    String uri = item.path("resource").path("uri").asText("");
                    sb.append("[resource: ").append(uri).append("]");
                } else {
                    // Unknown content type — surface raw JSON so the LLM has *something*.
                    sb.append("[content type=").append(type).append(": ").append(item).append("]");
                }
            }
        } else if (!content.isMissingNode()) {
            // Non-array content — older / non-standard server. Render as JSON.
            sb.append(content.toString());
        } else {
            // No content key at all — render the whole result.
            sb.append(root.toString());
        }
        String text = sb.toString();
        return isError ? SkillResult.error(text.isEmpty() ? "MCP tool reported error (no content)" : text)
                       : SkillResult.success(text);
    }

    /** Map shape used by the registrar to publish tool metadata to dashboard list endpoints. */
    public Map<String, Object> toMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registeredName", registeredName);
        m.put("serverName", serverName);
        m.put("originalName", descriptor.name());
        m.put("description", descriptor.description());
        m.put("inputSchema", descriptor.inputSchema());
        return m;
    }
}
