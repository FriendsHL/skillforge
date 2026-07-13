package com.skillforge.server.mobile;

import com.skillforge.server.entity.ScheduledTaskEntity;

import java.time.Instant;

public record MobileScheduledTaskResponse(
        Long id,
        String name,
        Long agentId,
        String cronExpr,
        Instant oneShotAt,
        String timezone,
        String promptPreview,
        String sessionMode,
        boolean enabled,
        Instant nextFireAt,
        Instant lastFireAt,
        String status,
        boolean system
) {
    private static final int PROMPT_PREVIEW_CODE_POINTS = 180;

    public static MobileScheduledTaskResponse from(ScheduledTaskEntity task) {
        return new MobileScheduledTaskResponse(
                task.getId(),
                task.getName(),
                task.getAgentId(),
                task.getCronExpr(),
                task.getOneShotAt(),
                task.getTimezone(),
                truncate(task.getPromptTemplate(), PROMPT_PREVIEW_CODE_POINTS),
                task.getSessionMode(),
                task.isEnabled(),
                task.getNextFireAt(),
                task.getLastFireAt(),
                task.getStatus(),
                Long.valueOf(0L).equals(task.getCreatorUserId()));
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end) + "…";
    }
}
