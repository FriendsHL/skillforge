package com.skillforge.server.skill;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 父 Agent 用来异步派发任务给子 Agent 的工具。
 *
 * action:
 *  - dispatch: 新建子 session + 立即返回 runId,子 Agent 异步跑,
 *              结果完成后会作为 user message 自动投递到父 session 的下一轮,父 loop 自动 resume。
 *  - list:     列出当前 session 派发过的所有 SubAgent run
 *
 * 重要:dispatch 之后不要轮询 —— 结果会自动到达。
 */
public class SubAgentSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSkill.class);

    private final AgentService agentService;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final SubAgentRegistry registry;

    public SubAgentSkill(AgentService agentService,
                         SessionService sessionService,
                         ChatService chatService,
                         SubAgentRegistry registry) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "SubAgent";
    }

    @Override
    public String getDescription() {
        return "Dispatch a single one-off task to another agent asynchronously. "
                + "Use this for a simple parent→child delegation. For parallel multi-agent orchestration with team coordination, use TeamCreate instead. "
                + "The child agent runs in its own session; its result is automatically delivered as a user message. "
                + "Do NOT poll — the result arrives on its own. Use 'list' to see dispatched runs.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "The action to perform: dispatch or list",
                "enum", List.of("dispatch", "list")
        ));
        properties.put("agentId", Map.of(
                "type", "integer",
                "description", "Target child agent ID (required for dispatch)"
        ));
        properties.put("task", Map.of(
                "type", "string",
                "description", "Task description to send to the child agent (required for dispatch)"
        ));
        properties.put("maxLoops", Map.of(
                "type", "integer",
                "description", "Override max loop iterations for the child agent (default: agent's configured value or 25)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String action = (String) input.get("action");
            if (action == null || action.isBlank()) {
                return SkillResult.error("action is required");
            }
            return switch (action) {
                case "dispatch" -> handleDispatch(input, context);
                case "list" -> handleList(context);
                default -> SkillResult.error("Unknown action: " + action + ". Supported: dispatch, list");
            };
        } catch (IllegalStateException e) {
            // 深度/并发超限
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("SubAgentSkill execute failed", e);
            return SkillResult.error("SubAgent error: " + e.getMessage());
        }
    }

    private SkillResult handleDispatch(Map<String, Object> input, SkillContext context) {
        Object agentIdObj = input.get("agentId");
        if (agentIdObj == null) {
            return SkillResult.error("agentId is required for dispatch");
        }
        long agentId = ((Number) agentIdObj).longValue();

        String task = (String) input.get("task");
        if (task == null || task.isBlank()) {
            return SkillResult.error("task is required for dispatch");
        }

        String parentSessionId = context.getSessionId();
        if (parentSessionId == null) {
            return SkillResult.error("No parent session in context — cannot dispatch");
        }
        SessionEntity parent = sessionService.getSession(parentSessionId);

        AgentEntity targetAgent;
        try {
            targetAgent = agentService.getAgent(agentId);
        } catch (Exception e) {
            return SkillResult.error("Target agent not found: id=" + agentId);
        }

        // 1. 注册 run(深度/并发限制检查)
        SubAgentRegistry.SubAgentRun run = registry.registerRun(parent, agentId, targetAgent.getName(), task);

        // 2. 创建子 session
        SessionEntity child = sessionService.createSubSession(parent, agentId, run.runId);
        // Set maxLoops override on child session if provided
        Object maxLoopsObj = input.get("maxLoops");
        if (maxLoopsObj instanceof Number) {
            int maxLoops = ((Number) maxLoopsObj).intValue();
            if (maxLoops > 0) {
                child.setMaxLoops(maxLoops);
                sessionService.saveSession(child);
            }
        }
        registry.attachChildSession(run.runId, child.getId());

        // 3. 异步启动子 agent loop
        chatService.chatAsync(child.getId(), task, parent.getUserId());

        log.info("SubAgent dispatched: runId={}, parent={}, child={}, agent={}",
                run.runId, parentSessionId, child.getId(), targetAgent.getName());

        String msg = "Dispatched to agent '" + targetAgent.getName() + "' (id=" + agentId + ").\n"
                + "  runId: " + run.runId + "\n"
                + "  childSessionId: " + child.getId() + "\n"
                + "  depth: " + child.getDepth() + "\n"
                + "The child is running asynchronously. Its result will arrive automatically as a user message "
                + "in a subsequent turn — do NOT poll or wait.";
        return SkillResult.success(msg);
    }

    private SkillResult handleList(SkillContext context) {
        String parentSessionId = context.getSessionId();
        if (parentSessionId == null) {
            return SkillResult.error("No parent session in context");
        }
        List<SubAgentRegistry.SubAgentRun> runs = registry.listRunsForParent(parentSessionId);
        if (runs.isEmpty()) {
            return SkillResult.success("No subagent runs dispatched from this session.");
        }
        StringBuilder sb = new StringBuilder("SubAgent runs (").append(runs.size()).append("):\n");
        for (SubAgentRegistry.SubAgentRun r : runs) {
            sb.append("- ").append(r.runId.substring(0, 8))
                    .append(" | ").append(r.childAgentName)
                    .append(" | ").append(r.status);
            if (r.childSessionId != null) sb.append(" | child=").append(r.childSessionId);
            sb.append("\n");
        }
        return SkillResult.success(sb.toString());
    }
}
