package com.skillforge.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SessionTaskSnapshotResponse(
        String sessionId,
        Map<String, Long> summary,
        List<SessionTaskResponse> tasks,
        Instant generatedAt) {}
