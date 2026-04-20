package com.skillforge.server.channel.router;

public record SessionRouteResult(
        String sessionId,
        /** true = session created in this call; false = existing active session reused. */
        boolean created
) {}
