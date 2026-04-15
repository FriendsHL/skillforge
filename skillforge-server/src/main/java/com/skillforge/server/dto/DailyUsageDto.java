package com.skillforge.server.dto;

public record DailyUsageDto(String date, long inputTokens, long outputTokens) {}
