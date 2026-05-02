package com.skillforge.server.tool;

import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.TraceCollector;
import com.skillforge.core.engine.TraceSpan;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Send a message to another team member by handle, to 'parent', or to 'broadcast' (leader-only).
 * Messages arrive as [AgentMessage] in the recipient's next turn.
 */
public class TeamSendTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TeamSendTool.class);

    private final SessionService sessionService;
    private final AgentRoster agentRoster;
    private final SubAgentRegistry subAgentRegistry;
    private final ChatEventBroadcaster broadcaster;
    private final TraceCollector traceCollector;

    public TeamSendTool(SessionService sessionService,
                         AgentRoster agentRoster,
                         SubAgentRegistry subAgentRegistry,
                         ChatEventBroadcaster broadcaster,
                         TraceCollector traceCollector) {
        this.sessionService = sessionService;
        this.agentRoster = agentRoster;
        this.subAgentRegistry = subAgentRegistry;
        this.broadcaster = broadcaster;
        this.traceCollector = traceCollector;
    }

    @Override
    public String getName() {
        return "TeamSend";
    }

    @Override
    public String getDescription() {
        return "Send a message to another team member by handle, to 'parent', or to 'broadcast' (leader-only). "
                + "Messages arrive as [AgentMessage] in the recipient's next turn.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("to", Map.of(
                "type", "string",
                "description", "Handle name of the target agent, 'parent', or 'broadcast'"
        ));
        properties.put("message", Map.of(
                "type", "string",
                "description", "Message text to send"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("to", "message"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String to = (String) input.get("to");
            if (to == null || to.isBlank()) {
                return SkillResult.error("'to' parameter is required");
            }

            String message = (String) input.get("message");
            if (message == null || message.isBlank()) {
                return SkillResult.error("'message' parameter is required");
            }

            String sessionId = context.getSessionId();
            if (sessionId == null) {
                return SkillResult.error("No session in context");
            }

            SessionEntity currentSession = sessionService.getSession(sessionId);
            String collabRunId = currentSession.getCollabRunId();
            if (collabRunId == null) {
                return SkillResult.error("No collaboration run active — TeamSend requires an active collab run");
            }

            // Resolve sender handle
            String senderHandle = agentRoster.resolveHandle(collabRunId, sessionId);
            if (senderHandle == null) {
                // Fallback: extract from session title
                if (currentSession.getTitle() != null && currentSession.getTitle().startsWith("Team: ")) {
                    senderHandle = currentSession.getTitle().substring(6);
                } else {
                    senderHandle = "session-" + sessionId.substring(0, 8);
                }
            }

            SkillResult result;
            if ("broadcast".equals(to)) {
                result = handleBroadcast(currentSession, collabRunId, senderHandle, message);
            } else if ("parent".equals(to)) {
                result = handleParent(currentSession, collabRunId, senderHandle, message);
            } else {
                result = handleDirect(currentSession, collabRunId, senderHandle, to, message);
            }

            // Record PEER_MESSAGE trace span(s)
            if (result.isSuccess()) {
                if ("broadcast".equals(to)) {
                    // For broadcast, record one span per recipient
                    Map<String, String> members = agentRoster.listMembers(collabRunId);
                    for (String targetHandle : members.keySet()) {
                        String targetSessionId = members.get(targetHandle);
                        if (!targetSessionId.equals(sessionId)) {
                            recordPeerMessageSpan(sessionId, "TeamSend → " + targetHandle,
                                    message, "Delivered to " + targetHandle, startTime);
                        }
                    }
                } else {
                    String targetLabel = "parent".equals(to) ? "parent" : to;
                    recordPeerMessageSpan(sessionId, "TeamSend → " + targetLabel,
                            message, "Delivered to " + targetLabel, startTime);
                }
            }

            return result;

        } catch (IllegalStateException e) {
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("TeamSendTool execute failed", e);
            return SkillResult.error("TeamSend error: " + e.getMessage());
        }
    }

    private void recordPeerMessageSpan(String sessionId, String name,
                                        String message, String output, long startTime) {
        try {
            TraceSpan span = new TraceSpan();
            span.setId(UUID.randomUUID().toString());
            span.setSessionId(sessionId);
            span.setSpanType("PEER_MESSAGE");
            span.setName(name);
            span.setInput(message.substring(0, Math.min(500, message.length())));
            span.setOutput(output);
            span.setStartTimeMs(startTime);
            span.setEndTimeMs(System.currentTimeMillis());
            span.setDurationMs(span.getEndTimeMs() - span.getStartTimeMs());
            span.setSuccess(true);
            // OBS-2 M4: legacy PEER_MESSAGE trace_span write path closed.
            // Span object construction kept for now; M5/M6 cleanup will drop the
            // whole recordPeerMessageSpan(...) helper. No replacement on the new
            // t_llm_span path yet — peer messages are not modeled as trace spans
            // in OBS-2 (OBS-3 unified-trace-tree may revisit cross-agent peer
            // observability when sub-agent timeline rendering is built).
            // traceCollector.record(span);
        } catch (Exception e) {
            log.warn("Failed to record PEER_MESSAGE trace span: {}", e.getMessage());
        }
    }

    private SkillResult handleBroadcast(SessionEntity currentSession, String collabRunId,
                                         String senderHandle, String message) {
        // Only leader (depth == 0) can broadcast
        if (currentSession.getDepth() != 0) {
            return SkillResult.error("Only the leader (depth=0) can use broadcast");
        }

        Map<String, String> members = agentRoster.listMembers(collabRunId);
        if (members.isEmpty()) {
            return SkillResult.error("No team members to broadcast to");
        }

        List<String> sentTo = new ArrayList<>();
        String currentSessionId = currentSession.getId();

        for (Map.Entry<String, String> entry : members.entrySet()) {
            String targetSessionId = entry.getValue();
            if (targetSessionId.equals(currentSessionId)) {
                continue; // skip self
            }
            String targetHandle = entry.getKey();

            String messageId = UUID.randomUUID().toString();
            long seqNo = subAgentRegistry.nextSeqNo(targetSessionId);
            String envelope = buildEnvelope(senderHandle, collabRunId, message);

            subAgentRegistry.enqueueForSession(targetSessionId, envelope, messageId, seqNo);
            subAgentRegistry.maybeResumeSession(targetSessionId);
            broadcaster.collabMessageRouted(collabRunId, senderHandle, targetHandle, messageId);
            sentTo.add(targetHandle);
        }

        if (sentTo.isEmpty()) {
            return SkillResult.error("No other team members to broadcast to (only self in roster)");
        }
        return SkillResult.success("Broadcast sent to " + sentTo.size() + " member(s): " + String.join(", ", sentTo));
    }

    private SkillResult handleParent(SessionEntity currentSession, String collabRunId,
                                      String senderHandle, String message) {
        String parentSessionId = currentSession.getParentSessionId();
        if (parentSessionId == null) {
            return SkillResult.error("No parent session — this session is the leader/root");
        }

        String messageId = UUID.randomUUID().toString();
        long seqNo = subAgentRegistry.nextSeqNo(parentSessionId);
        String envelope = buildEnvelope(senderHandle, collabRunId, message);

        subAgentRegistry.enqueueForSession(parentSessionId, envelope, messageId, seqNo);
        subAgentRegistry.maybeResumeSession(parentSessionId);
        broadcaster.collabMessageRouted(collabRunId, senderHandle, "parent", messageId);

        return SkillResult.success("Message sent to parent session");
    }

    private SkillResult handleDirect(SessionEntity currentSession, String collabRunId,
                                      String senderHandle, String toHandle, String message) {
        String targetSessionId = agentRoster.resolve(collabRunId, toHandle);
        if (targetSessionId == null) {
            return SkillResult.error("Handle '" + toHandle + "' not found or already completed");
        }

        // Adjacency policy check
        String currentSessionId = currentSession.getId();
        SessionEntity targetSession;
        try {
            targetSession = sessionService.getSession(targetSessionId);
        } catch (Exception e) {
            return SkillResult.error("Target session not found for handle '" + toHandle + "'");
        }

        boolean isParent = targetSessionId.equals(currentSession.getParentSessionId());
        boolean isChild = currentSessionId.equals(targetSession.getParentSessionId());
        boolean isSibling = currentSession.getParentSessionId() != null
                && currentSession.getParentSessionId().equals(targetSession.getParentSessionId());

        if (!isParent && !isChild && !isSibling) {
            return SkillResult.error("Cannot message agent '" + toHandle
                    + "' outside your adjacency scope (must be parent, child, or sibling)");
        }

        String messageId = UUID.randomUUID().toString();
        long seqNo = subAgentRegistry.nextSeqNo(targetSessionId);
        String envelope = buildEnvelope(senderHandle, collabRunId, message);

        subAgentRegistry.enqueueForSession(targetSessionId, envelope, messageId, seqNo);
        subAgentRegistry.maybeResumeSession(targetSessionId);
        broadcaster.collabMessageRouted(collabRunId, senderHandle, toHandle, messageId);

        return SkillResult.success("Message sent to '" + toHandle + "'");
    }

    private String buildEnvelope(String senderHandle, String collabRunId, String message) {
        return "[AgentMessage type=PEER_MESSAGE from=" + senderHandle
                + " collabRunId=" + collabRunId + "]\n"
                + message + "\n"
                + "[/AgentMessage]";
    }
}
