package com.skillforge.server.dto;

import java.time.Instant;

public record HookHistoryDto(
        String id,
        String sessionId,
        String spanType,
        String name,
        String input,
        String output,
        Instant startTime,
        Instant endTime,
        long durationMs,
        boolean success,
        String error
) {}
