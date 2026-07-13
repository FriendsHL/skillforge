package com.skillforge.server.mobile;

import com.skillforge.server.entity.ScheduledTaskRunEntity;

import java.time.Instant;

public record MobileScheduledTaskRunResponse(
        Long id,
        Long taskId,
        Instant triggeredAt,
        Instant finishedAt,
        String status,
        String errorMessage,
        String sessionId,
        boolean manual
) {
    private static final int ERROR_PREVIEW_CODE_POINTS = 240;

    public static MobileScheduledTaskRunResponse from(ScheduledTaskRunEntity run) {
        return new MobileScheduledTaskRunResponse(
                run.getId(),
                run.getTaskId(),
                run.getTriggeredAt(),
                run.getFinishedAt(),
                run.getStatus(),
                truncate(run.getErrorMessage()),
                run.getTriggeredSessionId(),
                run.isManual());
    }

    private static String truncate(String value) {
        if (value == null || value.codePointCount(0, value.length()) <= ERROR_PREVIEW_CODE_POINTS) {
            return value;
        }
        int end = value.offsetByCodePoints(0, ERROR_PREVIEW_CODE_POINTS - 1);
        return value.substring(0, end) + "…";
    }
}
