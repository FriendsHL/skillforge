package com.skillforge.server.dto;

import java.util.List;
import java.util.UUID;

public record ChatRequest(
        String message,
        Long userId,
        List<String> attachmentIds,
        UUID requestId) {}
