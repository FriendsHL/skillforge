package com.skillforge.server.mcp.exception;

import java.util.List;

/**
 * INV-12: thrown by {@code McpServerService.delete} when one or more agents still
 * reference the server in their {@code mcp_server_ids}. Caller (controller) maps
 * to HTTP 409 Conflict and surfaces {@link #referencingAgentNames} so the operator
 * can unbind first.
 */
public class McpServerInUseException extends RuntimeException {

    private final List<String> referencingAgentNames;

    public McpServerInUseException(String serverName, List<String> referencingAgentNames) {
        super("MCP server '" + serverName + "' is referenced by agent(s): "
                + String.join(", ", referencingAgentNames));
        this.referencingAgentNames = List.copyOf(referencingAgentNames);
    }

    public List<String> getReferencingAgentNames() {
        return referencingAgentNames;
    }
}
