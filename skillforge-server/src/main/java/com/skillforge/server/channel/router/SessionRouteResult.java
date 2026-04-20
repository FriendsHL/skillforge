package com.skillforge.server.channel.router;

public record SessionRouteResult(
        String sessionId,
        /** true = session created in this call; false = existing active session reused. */
        boolean created,
        /** SkillForge user id used for this routed turn (never null). */
        Long skillforgeUserId
) {}
