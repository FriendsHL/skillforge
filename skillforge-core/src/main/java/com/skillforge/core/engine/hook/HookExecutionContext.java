package com.skillforge.core.engine.hook;

import java.util.Map;

/**
 * Execution-time context passed to a {@link HandlerRunner}. Immutable.
 *
 * @param sessionId current session id (never null during dispatch)
 * @param userId   session owner id (may be null for system-triggered sessions)
 * @param event    the lifecycle event firing
 * @param metadata extra keys the dispatcher wants runners / underlying handlers to see;
 *                 standard keys include {@code _hook_origin: "lifecycle:<event_name>"}
 */
public record HookExecutionContext(
        String sessionId,
        Long userId,
        HookEvent event,
        Map<String, Object> metadata) {
}
