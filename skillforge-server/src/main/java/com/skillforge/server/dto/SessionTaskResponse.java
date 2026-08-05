package com.skillforge.server.dto;

import java.time.Instant;
import java.util.List;

public record SessionTaskResponse(
        String taskId,
        String subject,
        String description,
        String activeForm,
        String status,
        String owner,
        boolean blocked,
        List<String> blockedBy,
        List<String> blocks,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
