package com.skillforge.server.skill;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.CollabRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spawn a new agent as a team member in the current collaboration run.
 * If no collab run exists yet, auto-creates one with this session as leader.
 */
public class TeamCreateSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(TeamCreateSkill.class);

    private final SessionService sessionService;
    private final CollabRunService collabRunService;

    public TeamCreateSkill(SessionService sessionService,
                           CollabRunService collabRunService) {
        this.sessionService = sessionService;
        this.collabRunService = collabRunService;
    }

    @Override
    public String getName() {
        return "TeamCreate";
    }

    @Override
    public String getDescription() {
        return "Spawn a new agent as a team member in the current collaboration run. "
                + "Use this (not SubAgent) when orchestrating multiple agents in parallel as a team. "
                + "All members share a collabRunId and results arrive as [TeamResult] messages automatically. "
                + "IMPORTANT: After spawning all needed team members, you MUST stop calling tools and "
                + "end your turn with a text response explaining what you delegated and what you are waiting for. "
                + "Do NOT do the work yourself — let the team members handle it. "
                + "Do NOT poll or call TeamList repeatedly. Results arrive automatically as user messages.";
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
                "description", "Logical name for this agent, e.g. 'reviewer-a', 'dev-agent'"
        ));
        properties.put("agentId", Map.of(
                "type", "integer",
                "description", "The numeric ID of the agent to spawn (check system context or use TeamList for available agents)"
        ));
        properties.put("task", Map.of(
                "type", "string",
                "description", "Instructions for the agent"
        ));
        properties.put("briefing", Map.of(
                "type", "string",
                "description", "Optional curated context to prepend to the task"
        ));
        properties.put("lightContext", Map.of(
                "type", "boolean",
                "description", "If true, the child agent gets a stripped-down system prompt without SOUL.md, TOOLS.md, and Memory injection, saving ~30-50% input tokens."
        ));
        properties.put("maxLoops", Map.of(
                "type", "integer",
                "description", "Override max loop iterations for this agent (default: agent's configured value or 25)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("handle", "agentId", "task"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String handle = (String) input.get("handle");
            if (handle == null || handle.isBlank()) {
                return SkillResult.error("handle is required");
            }

            Object agentIdObj = input.get("agentId");
            if (agentIdObj == null) {
                return SkillResult.error("agentId is required");
            }
            long agentId = ((Number) agentIdObj).longValue();

            String task = (String) input.get("task");
            if (task == null || task.isBlank()) {
                return SkillResult.error("task is required");
            }

            String briefing = (String) input.get("briefing");

            Object lightCtxObj = input.get("lightContext");
            boolean lightContext = lightCtxObj instanceof Boolean && (Boolean) lightCtxObj;

            Object maxLoopsObj = input.get("maxLoops");
            Integer maxLoops = maxLoopsObj instanceof Number ? ((Number) maxLoopsObj).intValue() : null;

            String sessionId = context.getSessionId();
            if (sessionId == null) {
                return SkillResult.error("No session in context — cannot create team member");
            }

            SessionEntity session = sessionService.getSession(sessionId);

            // Auto-create collab run if session doesn't have one
            String collabRunId = session.getCollabRunId();
            if (collabRunId == null) {
                CollabRunEntity collabRun = collabRunService.createRun(sessionId, 2, 20);
                collabRunId = collabRun.getCollabRunId();
                // Refresh session to get the updated collabRunId
                session = sessionService.getSession(sessionId);
            }

            // Spawn the member
            SessionEntity child = collabRunService.spawnMember(
                    collabRunId, handle, agentId, task, briefing, session, lightContext, maxLoops);

            String msg = "Team member '" + handle + "' spawned successfully.\n"
                    + "  collabRunId: " + collabRunId + "\n"
                    + "  childSessionId: " + child.getId() + "\n"
                    + "  runId: " + child.getSubAgentRunId() + "\n"
                    + "  depth: " + child.getDepth() + "\n"
                    + "The agent is running asynchronously. Its result will arrive automatically.\n"
                    + "REMINDER: After spawning all needed members, STOP calling tools and end your turn. "
                    + "Do NOT do the work yourself — wait for [TeamResult] messages.";
            return SkillResult.success(msg);

        } catch (IllegalStateException e) {
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("TeamCreateSkill execute failed", e);
            return SkillResult.error("TeamCreate error: " + e.getMessage());
        }
    }
}
