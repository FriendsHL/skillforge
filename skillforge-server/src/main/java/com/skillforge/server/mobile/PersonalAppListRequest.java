package com.skillforge.server.mobile;

public record PersonalAppListRequest(
        String cursor,
        String limit,
        String sort,
        String q,
        String agentId,
        String sessionId,
        String favorite,
        String createdAfter) { }
