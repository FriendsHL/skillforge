package com.skillforge.tools.mcp.exception;

/**
 * Generic MCP client error: protocol violation, transport failure, server crash,
 * timeout, or JSON-RPC error response. Wrapped at the {@code McpToolAdapter} boundary
 * into a {@code SkillResult.error} so the agent loop never sees a checked exception.
 */
public class McpClientException extends RuntimeException {

    public McpClientException(String message) {
        super(message);
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
