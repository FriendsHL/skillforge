package com.skillforge.workflow.ws;

import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps {@link UserWebSocketHandler#broadcastAll} for workflow {@code phase()} /
 * {@code log()} events (plan §6). Best-effort: a dropped connection never masks
 * the run's progress (callers can still read DB rows).
 */
@Component
public class WorkflowWsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWsBroadcaster.class);

    private final UserWebSocketHandler userWebSocketHandler;

    public WorkflowWsBroadcaster(UserWebSocketHandler userWebSocketHandler) {
        this.userWebSocketHandler = userWebSocketHandler;
    }

    public void phaseStarted(String runId, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "workflow_phase");
        payload.put("runId", runId);
        payload.put("title", title);
        broadcast(payload);
    }

    public void logged(String runId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "workflow_log");
        payload.put("runId", runId);
        payload.put("message", message);
        broadcast(payload);
    }

    /**
     * AUTOEVOLVING V1 Sprint 2: a workflow run has parked on a
     * {@code humanApprove()} gate and needs an operator decision. {@code payload}
     * is the (already JS→Java converted) approval payload the workflow passed to
     * {@code humanApprove(...)}. The dashboard shows it and POSTs an approve
     * decision (chunk 2 REST) to resume the run.
     */
    public void humanApproveRequired(String runId, String stepRunId, int stepIndex, Object payload) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "workflow_human_approve_required");
        msg.put("runId", runId);
        msg.put("stepRunId", stepRunId);
        msg.put("stepIndex", stepIndex);
        msg.put("status", "paused_for_human_approve");
        msg.put("payload", payload);
        broadcast(msg);
    }

    private void broadcast(Map<String, Object> payload) {
        try {
            userWebSocketHandler.broadcastAll(payload);
        } catch (RuntimeException e) {
            log.warn("WorkflowWsBroadcaster: WS broadcast failed ({}): {}",
                    payload.get("type"), e.getMessage());
        }
    }
}
