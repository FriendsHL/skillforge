package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.4 — fans out
 * {@code attribution_event_updated} payloads to all connected dashboard
 * sessions whenever an {@link OptimizationEventEntity}'s {@code stage} changes.
 *
 * <p>Single injection point used by:
 * <ul>
 *   <li>{@link AttributionDispatcherService} — dispatch_initiated sentinel write</li>
 *   <li>{@code ProposeOptimizationTool} — dispatch_initiated → proposal_pending</li>
 *   <li>{@link AttributionApprovalService} — approve / reject /
 *       retryCandidateGeneration stage transitions</li>
 * </ul>
 *
 * <p>Wire shape (matches V1 SkillDraftService / V2 metrics-collector convention,
 * see "Phase 1.4 WS protocol deviation" SendMessage):
 * <pre>
 *   { "type": "attribution_event_updated",
 *     "eventId": Long,
 *     "patternId": Long,
 *     "stage": String,
 *     "previousStage": String?,
 *     "updatedAt": ISO-8601 string }
 * </pre>
 *
 * <p>Broadcast strategy: iterates {@link UserWebSocketHandler#connectedUserIds()}
 * and pushes to every connected user. Attribution events are admin-system-wide
 * (every operator with the dashboard open should see the timeline tick) so per-user
 * scoping doesn't fit. Per-pattern client-side routing is the dashboard's job
 * (filter on {@code patternId}).
 *
 * <p>Failure semantics: {@link UserWebSocketHandler#broadcast(Long, Map)}
 * already swallows per-session send errors. This wrapper additionally guards
 * against null userWebSocketHandler (unit tests without Spring context) so
 * service callers can ignore the WS dimension entirely.
 */
@Component
public class AttributionEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(AttributionEventBroadcaster.class);
    public static final String EVENT_TYPE = "attribution_event_updated";

    private final UserWebSocketHandler userWebSocketHandler;

    public AttributionEventBroadcaster(UserWebSocketHandler userWebSocketHandler) {
        this.userWebSocketHandler = userWebSocketHandler;
    }

    /** Convenience: capture both id + stage from the entity. */
    public void broadcastStageTransition(OptimizationEventEntity event, String previousStage) {
        if (event == null) return;
        broadcastStageTransition(event.getId(), event.getPatternId(),
                event.getStage(), previousStage, event.getUpdatedAt());
    }

    public void broadcastStageTransition(Long eventId,
                                         Long patternId,
                                         String stage,
                                         String previousStage,
                                         Instant updatedAt) {
        if (userWebSocketHandler == null) {
            // Test path with no WS bean wired — silently no-op.
            return;
        }
        Set<Long> userIds = userWebSocketHandler.connectedUserIds();
        if (userIds.isEmpty()) {
            // No dashboard sessions open — log at debug so production doesn't
            // spam INFO when nobody's watching.
            log.debug("[AttributionBroadcaster] no connected users; eventId={} stage={} ({})",
                    eventId, stage, previousStage);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", EVENT_TYPE);
        payload.put("eventId", eventId);
        payload.put("patternId", patternId);
        payload.put("stage", stage);
        if (previousStage != null) {
            payload.put("previousStage", previousStage);
        }
        if (updatedAt != null) {
            payload.put("updatedAt", updatedAt.toString());
        }
        for (Long userId : userIds) {
            userWebSocketHandler.broadcast(userId, payload);
        }
        log.info("[AttributionBroadcaster] eventId={} {} → {} fanned out to {} user(s)",
                eventId, previousStage == null ? "<dispatch>" : previousStage,
                stage, userIds.size());
    }
}
