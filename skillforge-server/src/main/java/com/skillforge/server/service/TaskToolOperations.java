package com.skillforge.server.service;

import com.skillforge.server.dto.SessionTaskResponse;
import com.skillforge.server.dto.SessionTaskSnapshotResponse;

import java.util.Optional;

/** Actor-aware operations consumed by the four Task tools. */
public interface TaskToolOperations {
    SessionTaskService.CreateResult create(String actorSessionId, Long actorUserId,
                                           SessionTaskService.CreateCommand command);

    SessionTaskSnapshotResponse update(String actorSessionId, Long actorUserId, String taskId,
                                       Long expectedRevision, SessionTaskService.UpdateCommand command);

    SessionTaskResponse get(String actorSessionId, Long actorUserId, String taskId);

    SessionTaskSnapshotResponse snapshot(String actorSessionId, Long actorUserId,
                                         boolean includeDeleted, boolean availableOnly);

    SessionTaskSnapshotResponse snapshotForSystem(String actorSessionId, boolean includeDeleted, int limit);

    default Optional<TeamTaskRuntimeView> runtime(String actorSessionId, Long actorUserId, String taskId) {
        return Optional.empty();
    }

    record TeamTaskRuntimeView(
            String collabRunId,
            String graphSessionId,
            String attemptId,
            Integer attemptNo,
            String workerSessionId,
            String status,
            java.time.Instant heartbeatAt,
            java.time.Instant expiresAt) {}
}
