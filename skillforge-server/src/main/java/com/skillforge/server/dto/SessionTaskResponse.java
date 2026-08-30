package com.skillforge.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    public SessionTaskResponse(String taskId, String subject, String description, String activeForm,
                               String status, String owner, boolean blocked,
                               List<String> blockedBy, List<String> blocks,
                               Instant createdAt, Instant updatedAt, long version) {
        this(taskId, subject, description, activeForm, status, owner, blocked,
                blockedBy, blocks, Map.of(), createdAt, updatedAt, version);
    }
}
