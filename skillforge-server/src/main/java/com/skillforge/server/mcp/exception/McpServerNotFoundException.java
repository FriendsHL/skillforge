package com.skillforge.server.mcp.exception;

public class McpServerNotFoundException extends RuntimeException {
    public McpServerNotFoundException(Long id) {
        super("MCP server not found: id=" + id);
    }
    public McpServerNotFoundException(String name) {
        super("MCP server not found: name=" + name);
    }
}
