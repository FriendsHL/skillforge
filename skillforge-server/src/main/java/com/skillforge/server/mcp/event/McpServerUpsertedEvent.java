package com.skillforge.server.mcp.event;

/**
 * Published by {@code McpServerService} after a row insert / update commits.
 * Listened by {@code McpServerLifecycle} via {@code @TransactionalEventListener
 * (AFTER_COMMIT)} (INV-6) — only after the DB write is durable do we tear down
 * the running session and re-spawn with the new config.
 */
public record McpServerUpsertedEvent(Long serverId, String serverName) {
}
