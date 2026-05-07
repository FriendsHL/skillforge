package com.skillforge.server.mcp.event;

/**
 * Published by {@code McpServerService} after a row delete commits.
 * {@code McpServerLifecycle} listens with {@code AFTER_COMMIT} (INV-6/INV-7) and
 * disconnects + unregisters the matching session.
 */
public record McpServerDeletedEvent(Long serverId, String serverName) {
}
