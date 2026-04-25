package com.skillforge.server.tool;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.CollabRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cancel a running team member or the entire collaboration run.
 */
public class TeamKillTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TeamKillTool.class);

    private final SessionService sessionService;
    private final AgentRoster agentRoster;
    private final CollabRunService collabRunService;
    private final CancellationRegistry cancellationRegistry;

    public TeamKillTool(SessionService sessionService,
                         AgentRoster agentRoster,
                         CollabRunService collabRunService,
                         CancellationRegistry cancellationRegistry) {
        this.sessionService = sessionService;
        this.agentRoster = agentRoster;
        this.collabRunService = collabRunService;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Override
    public String getName() {
        return "TeamKill";
    }

    @Override
    public String getDescription() {
        return "Cancel a running team member by handle, or pass handle='all' to cancel the entire collaboration run.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("handle", Map.of(
                "type", "string",
                "description", "Agent handle to kill, or 'all' to cancel the entire collaboration run"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("handle"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String handle = (String) input.get("handle");
            if (handle == null || handle.isBlank()) {
                return SkillResult.error("handle is required");
            }

            String sessionId = context.getSessionId();
            if (sessionId == null) {
                return SkillResult.error("No session in context");
            }

            SessionEntity session = sessionService.getSession(sessionId);
            String collabRunId = session.getCollabRunId();
            if (collabRunId == null) {
                return SkillResult.error("No collaboration run active in this session.");
            }

            if ("all".equalsIgnoreCase(handle)) {
                collabRunService.cancelRun(collabRunId);
                return SkillResult.success("Cancelled entire collaboration run: " + collabRunId.substring(0, 8));
            }

            // Cancel specific handle
            String targetSessionId = agentRoster.resolve(collabRunId, handle);
            if (targetSessionId == null) {
                return SkillResult.error("Handle '" + handle + "' not found or already completed in collab run "
                        + collabRunId.substring(0, 8));
            }

            boolean cancelled = cancellationRegistry.cancel(targetSessionId);
            if (cancelled) {
                return SkillResult.success("Cancel requested for team member '" + handle
                        + "' (session " + targetSessionId.substring(0, 8) + ")");
            } else {
                return SkillResult.success("Team member '" + handle + "' is not currently running (may have already finished).");
            }
        } catch (IllegalStateException e) {
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("TeamKillTool execute failed", e);
            return SkillResult.error("TeamKill error: " + e.getMessage());
        }
    }
}
