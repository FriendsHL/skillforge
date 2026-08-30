package com.skillforge.server.service.event;

public record TeamTaskAvailableEvent(
        Long eventId,
        String collabRunId,
        String actorSessionId,
        String taskId,
        String subject
) {}
