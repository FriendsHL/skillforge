package com.skillforge.server.dto;

import java.util.List;

public record ChatRequest(String message, Long userId, List<String> attachmentIds) {}
