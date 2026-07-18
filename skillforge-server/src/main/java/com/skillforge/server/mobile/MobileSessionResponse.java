package com.skillforge.server.mobile;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.runtime.RuntimeFailureState;

import java.time.LocalDateTime;

public record MobileSessionResponse(
        String id,
        Long userId,
        Long agentId,
        String title,
        String status,
        String runtimeStatus,
        String runtimeStep,
        String runtimeError,
        String failureSource,
        String failureCode,
        boolean retryable,
        String sideEffects,
        int messageCount,
        LocalDateTime updatedAt) {

    public static MobileSessionResponse from(SessionEntity session) {
        return new MobileSessionResponse(
                session.getId(),
                session.getUserId(),
                session.getAgentId(),
                session.getTitle(),
                session.getStatus(),
                session.getRuntimeStatus(),
                session.getRuntimeStep(),
                session.getRuntimeError(),
                session.getRuntimeFailureSource(),
                session.getRuntimeFailureCode(),
                RuntimeFailureState.isRetryAllowed(session),
                session.getRuntimeSideEffects(),
                session.getMessageCount(),
                session.getUpdatedAt());
    }
}
