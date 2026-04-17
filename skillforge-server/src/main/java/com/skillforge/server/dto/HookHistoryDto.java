package com.skillforge.server.dto;

import com.skillforge.server.entity.TraceSpanEntity;

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
) {
    public static HookHistoryDto from(TraceSpanEntity e) {
        return new HookHistoryDto(
                e.getId(),
                e.getSessionId(),
                e.getSpanType(),
                e.getName(),
                e.getInput(),
                e.getOutput(),
                e.getStartTime(),
                e.getEndTime(),
                e.getDurationMs(),
                e.isSuccess(),
                e.getError()
        );
    }
}
