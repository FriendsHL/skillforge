package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.AgentRoster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * List all team members in the current collaboration run with their status.
 */
public class TeamListTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TeamListTool.class);

    private final SessionService sessionService;
    private final AgentRoster agentRoster;

    public TeamListTool(SessionService sessionService, AgentRoster agentRoster) {
        this.sessionService = sessionService;
        this.agentRoster = agentRoster;
    }

    @Override
    public String getName() {
        return "TeamList";
    }

    @Override
    public String getDescription() {
        return "List all team members in the current collaboration run with their status.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String sessionId = context.getSessionId();
            if (sessionId == null) {
                return SkillResult.error("No session in context");
            }

            SessionEntity session = sessionService.getSession(sessionId);
            String collabRunId = session.getCollabRunId();
            if (collabRunId == null) {
                return SkillResult.success("No collaboration run active in this session.");
            }

            Map<String, String> members = agentRoster.listMembers(collabRunId);
            if (members.isEmpty()) {
                return SkillResult.success("Collaboration run " + collabRunId.substring(0, 8)
                        + " is active but has no registered team members.");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Collaboration run: ").append(collabRunId.substring(0, 8)).append("\n");
            sb.append("Team members (").append(members.size()).append("):\n");

            for (Map.Entry<String, String> entry : members.entrySet()) {
                String handle = entry.getKey();
                String memberSessionId = entry.getValue();

                String runtimeStatus = "unknown";
                String title = "";
                try {
                    SessionEntity memberSession = sessionService.getSession(memberSessionId);
                    runtimeStatus = memberSession.getRuntimeStatus();
                    title = memberSession.getTitle() != null ? memberSession.getTitle() : "";
                } catch (Exception ignored) {
                    runtimeStatus = "not_found";
                }

                sb.append("- ").append(handle)
                        .append(" | session=").append(memberSessionId.substring(0, 8))
                        .append(" | status=").append(runtimeStatus)
                        .append(" | ").append(title)
                        .append("\n");
            }

            return SkillResult.success(sb.toString());
        } catch (Exception e) {
            log.error("TeamListTool execute failed", e);
            return SkillResult.error("TeamList error: " + e.getMessage());
        }
    }
}
