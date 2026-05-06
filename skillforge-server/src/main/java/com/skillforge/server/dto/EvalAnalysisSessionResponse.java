package com.skillforge.server.dto;

public record EvalAnalysisSessionResponse(
        String sessionId,
        String analysisType,
        String scenarioId,
        String taskId,
        Long itemId
) {
}
