package com.skillforge.server.mobile;

import java.time.Instant;
import java.util.List;

public record MobilePersonalAppItemResponse(
        String artifactId,
        String sessionId,
        long sourceMessageSeq,
        String title,
        String caption,
        int schemaVersion,
        List<String> permissions,
        List<String> network,
        long agentId,
        String agentName,
        String sessionTitle,
        Instant createdAt,
        Instant lastOpenedAt,
        boolean favorite,
        String availability) { }
