package com.skillforge.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.acp.AcpAgentRunner;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.runtime.RuntimeFailureState;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.AgentTargetResolver;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 父 Agent 用来异步派发任务给子 Agent 的工具。
 *
 * action:
 *  - dispatch:  新建子 session + 立即返回 runId,子 Agent 异步跑,
 *               结果完成后会作为 user message 自动投递到父 session 的下一轮,父 loop 自动 resume。
 *  - continue:  对一个之前 dispatch 过、当前 idle 的子 session 追问后续。复用同一 child session,
 *               全部上下文保留;结果同样异步回投。
 *  - terminate: 强制结束某个子 session,释放并发名额。
 *  - list:      列出当前 session 派发过的所有 SubAgent run
 *
 * 重要:dispatch / continue 之后不要轮询 —— 结果会自动到达。
 */
public class SubAgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTool.class);

    /**
     * ACP-EXTERNAL-AGENT P1c-1: prefix on an agent's {@code modelId} marking it as
     * an external ACP runtime (e.g. {@code acp:claude-code}). When the dispatch
     * target carries this prefix, the loop runs cc via {@link AcpAgentRunner}
     * instead of the SkillForge engine ({@link ChatService#chatAsync}). Mirrors the
     * existing provider-prefix convention (e.g. {@code ark:glm-5.2}).
     */
    private static final String ACP_RUNTIME_PREFIX = "acp:";

    private final AgentTargetResolver targetResolver;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final SubAgentRegistry registry;
    private final CancellationRegistry cancellationRegistry;
    private final AgentService agentService;
    /**
     * P1c-1: SubAgent-mode runner for {@code acp:}-prefixed agents. Nullable so
     * deployments / tests without the ACP subsystem still construct the tool — a
     * dispatch to an {@code acp:} agent without it returns a clear error rather
     * than NPE.
     */
    private final AcpAgentRunner acpAgentRunner;
    /**
     * SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18): used to JSON-serialise
     * the {@code skillIdsOverride} input list into the child session's
     * {@code skill_overrides_json} column when the dispatching parent passes
     * one. Cheap to share — Jackson's mapper is thread-safe post-config.
     */
    private final ObjectMapper objectMapper;

    public SubAgentTool(AgentTargetResolver targetResolver,
                         SessionService sessionService,
                         ChatService chatService,
                         SubAgentRegistry registry,
                         CancellationRegistry cancellationRegistry,
                         AgentService agentService,
                         ObjectMapper objectMapper) {
        this(targetResolver, sessionService, chatService, registry, cancellationRegistry,
                agentService, objectMapper, null);
    }

    /**
     * P1c-1 constructor — adds the ACP SubAgent-mode runner so a dispatch to an
     * {@code acp:}-prefixed agent (e.g. {@code claude-code}) routes to cc instead
     * of the SkillForge engine.
     */
    public SubAgentTool(AgentTargetResolver targetResolver,
                         SessionService sessionService,
                         ChatService chatService,
                         SubAgentRegistry registry,
                         CancellationRegistry cancellationRegistry,
                         AgentService agentService,
                         ObjectMapper objectMapper,
                         AcpAgentRunner acpAgentRunner) {
        this.targetResolver = targetResolver;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.registry = registry;
        this.cancellationRegistry = cancellationRegistry;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.acpAgentRunner = acpAgentRunner;
    }

    @Override
    public String getName() {
        return "SubAgent";
    }

    @Override
    public String getDescription() {
        return "Manage one-off async delegations to another agent. The child agent runs in its own session; "
                + "results are delivered automatically as user messages — do NOT poll.\n"
                + "Actions:\n"
                + "  - dispatch: Spawn a fresh child agent session and ask it to do a task. Returns runId. "
                + "Use for parallel work or when no prior context is needed.\n"
                + "  - continue: Send a follow-up task to a previously-dispatched subagent (must currently be idle). "
                + "Reuses the same child session — full prior context retained. Use 'list' to find existing runIds. "
                + "If the subagent is still running, you can wait for completion, call 'terminate' to discard its current "
                + "work, or 'dispatch' a new agent for parallel work.\n"
                + "  - terminate: Force-end a child session and release its concurrency quota. Use to abort a running "
                + "subagent or clean up before re-dispatching.\n"
                + "  - list: Show all subagent runs spawned from the current parent session, with their runIds and statuses.\n"
                + "For parallel multi-agent orchestration with team coordination, use TeamCreate instead.";
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
                "description", "The action to perform: dispatch | continue | terminate | list",
                "enum", List.of("dispatch", "continue", "terminate", "list")
        ));
        properties.put("agentId", Map.of(
                "type", "integer",
                "description", "Target child agent ID (provide either agentId or agentName for dispatch)"
        ));
        properties.put("agentName", Map.of(
                "type", "string",
                "description", "Target child agent name (for dispatch). Exact match is preferred; a unique fuzzy match is accepted."
        ));
        properties.put("task", Map.of(
                "type", "string",
                "description", "Task description to send to the child agent. Required for dispatch and continue."
        ));
        properties.put("runId", Map.of(
                "type", "string",
                "description", "SubAgent run handle returned from a prior dispatch. Required for continue and terminate."
        ));
        properties.put("maxLoops", Map.of(
                "type", "integer",
                "description", "Override max loop iterations for the child agent (dispatch only; default: agent's configured value or 25)"
        ));
        // SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18): per-dispatch skill
        // override list, in the same JSON shape as t_agent.skill_ids (array of
        // skill REGISTRY NAMES). dispatch only; null = use the child agent's
        // own skillIds (legacy). Empty array = "no skills" baseline. Used by
        // SkillCreatorService.dispatchEvaluation to spin with_skill /
        // without_skill child sessions without mutating t_agent.
        properties.put("skillIdsOverride", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Override the child agent's skill list with the given registry-name "
                        + "array (dispatch only; null = use child agent's own skillIds). Used by "
                        + "the SKILL-CREATOR-WITH-EVAL evaluation gate to run a scenario both with "
                        + "a candidate skill and without any skill (clean baseline) against the same agent."
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
                case "continue" -> handleContinue(input, context);
                case "terminate" -> handleTerminate(input, context);
                case "list" -> handleList(context);
                default -> SkillResult.error("Unknown action: " + action
                        + ". Supported: dispatch, continue, terminate, list");
            };
        } catch (IllegalStateException e) {
            // 深度/并发超限
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("SubAgentTool execute failed", e);
            return SkillResult.error("SubAgent error: " + e.getMessage());
        }
    }

    private SkillResult handleDispatch(Map<String, Object> input, SkillContext context) {
        Object agentIdObj = input.get("agentId");
        Object agentNameObj = input.get("agentName");
        if (agentIdObj == null && (agentNameObj == null || agentNameObj.toString().isBlank())) {
            return SkillResult.error("agentId or agentName is required for dispatch");
        }

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
            targetAgent = targetResolver.resolveVisibleTarget(parentSessionId, agentIdObj, agentNameObj);
        } catch (Exception e) {
            return SkillResult.error("Target agent cannot be resolved: " + e.getMessage());
        }
        long agentId = targetAgent.getId();
        String recursion = detectRecursiveDispatch(parent, agentId);
        if (recursion != null) {
            return SkillResult.error(recursion);
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
            }
        }
        // SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18): stamp per-dispatch
        // skillIdsOverride into child.skill_overrides_json so ChatService.runLoop
        // can swap the child agent's skill list at loop start without mutating
        // the persistent t_agent row. Empty array (explicit no-skill baseline)
        // is also serialized as "[]" — distinguishes "user wanted no skills"
        // from "user didn't pass override" (null). Malformed list values are
        // logged and ignored (loop will fall back to agent's own skillIds).
        Object skillOverrideObj = input.get("skillIdsOverride");
        if (skillOverrideObj instanceof List<?> rawList) {
            try {
                List<String> overrideNames = new ArrayList<>();
                for (Object el : rawList) {
                    if (el != null) overrideNames.add(el.toString());
                }
                child.setSkillOverridesJson(objectMapper.writeValueAsString(overrideNames));
            } catch (JsonProcessingException e) {
                log.warn("SubAgent dispatch: failed to serialise skillIdsOverride for child {} — "
                        + "child will fall back to agent.skillIds: {}", child.getId(), e.getMessage());
            }
        }
        // OBS-4 §2.5 INV-4: 复制父 session 当前 active_root 给 child，让 child 内部 trace 继承同一 root
        // （递归 child of child 同样路径，决策 Q6）。必须在 chatAsync 之前 set 让 chatAsync 能读到。
        child.setActiveRootTraceId(parent.getActiveRootTraceId());
        sessionService.saveSession(child);
        registry.attachChildSession(run.runId, child.getId());

        // 3. 异步启动子 agent loop。
        // P1c-1: branch on the target agent's runtime. An `acp:`-prefixed modelId
        // (e.g. claude-code) is an external ACP runtime → run cc via AcpAgentRunner
        // on the SAME child session (parent linkage + sub-agent run id already set
        // above). The runner publishes SessionLoopFinishedEvent + calls the registry
        // pump on completion, so the result is delivered back to the parent + origin
        // channel exactly like an engine child (AC-5) — no new delivery code.
        // Everything BEFORE this branch (registry guard, createSubSession, recursion
        // guard, active_root copy, attachChildSession) is shared/reused.
        // OBS-4 §2.1: preserveActiveRoot=true — child 已被设好 active_root，不要清空（INV-4）。
        String modelId = targetAgent.getModelId();
        boolean acpRuntime = modelId != null && modelId.startsWith(ACP_RUNTIME_PREFIX);
        if (acpRuntime) {
            if (acpAgentRunner == null) {
                return SkillResult.error("Agent '" + targetAgent.getName()
                        + "' is an ACP runtime (modelId=" + modelId
                        + ") but the ACP subsystem is not available");
            }
            acpAgentRunner.runAsSubAgent(child, task, parent.getUserId());
        } else {
            chatService.chatAsync(child.getId(), task, parent.getUserId(), true);
        }

        log.info("SubAgent dispatched: runId={}, parent={}, child={}, agent={}, runtime={}",
                run.runId, parentSessionId, child.getId(), targetAgent.getName(),
                acpRuntime ? "acp" : "engine");

        String msg = "Dispatched to agent '" + targetAgent.getName() + "' (id=" + agentId + ").\n"
                + "  runId: " + run.runId + "\n"
                + "  childSessionId: " + child.getId() + "\n"
                + "  depth: " + child.getDepth() + "\n"
                + "The child is running asynchronously. Its result will arrive automatically as a user message "
                + "in a subsequent turn — do NOT poll or wait.";
        return SkillResult.success(msg);
    }

    private String detectRecursiveDispatch(SessionEntity parent, Long targetAgentId) {
        if (targetAgentId == null) {
            return null;
        }

        // D22 fan-out exemption: SYSTEM agents (ownerId == null, seeded by migration)
        // are trusted to use SubAgent for horizontal scale-out — e.g. memory-curator
        // dispatching itself once per active user to consolidate per-user memory in
        // parallel. The exemption is bounded to depth-0 → depth-1 only (master →
        // sub layer); a sub trying to self-dispatch (depth ≥ 1) still trips the
        // lineage guard below. SubAgentRegistry MAX_DEPTH + MAX_ACTIVE_CHILDREN_PER_PARENT
        // bound any runaway recursion as the outer safety net.
        try {
            AgentEntity targetAgent = agentService.getAgent(targetAgentId);
            if (targetAgent != null && targetAgent.getOwnerId() == null) {
                int parentDepth = parent != null ? parent.getDepth() : 0;
                if (parentDepth == 0) {
                    return null; // master fan-out only
                }
                // depth ≥ 1 falls through to the strict lineage check below.
            }
        } catch (Exception e) {
            // Agent lookup failure should not bypass the guard — fall through to the
            // strict lineage check below.
            log.debug("detectRecursiveDispatch: agent lookup failed for id={}: {}",
                    targetAgentId, e.getMessage());
        }

        SessionEntity cursor = parent;
        while (cursor != null) {
            if (targetAgentId.equals(cursor.getAgentId())) {
                return "SubAgent spawn rejected: recursive agent dispatch would call agentId="
                        + targetAgentId + " again in the current session lineage";
            }
            String parentId = cursor.getParentSessionId();
            if (parentId == null || parentId.isBlank()) {
                break;
            }
            try {
                cursor = sessionService.getSession(parentId);
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }

    private SkillResult handleContinue(Map<String, Object> input, SkillContext context) {
        String runId = asString(input.get("runId"));
        if (runId == null || runId.isBlank()) {
            return SkillResult.error("runId is required for continue");
        }
        String task = asString(input.get("task"));
        if (task == null || task.isBlank()) {
            return SkillResult.error("task is required for continue");
        }
        String parentSessionId = context.getSessionId();
        if (parentSessionId == null) {
            return SkillResult.error("No parent session in context — cannot continue");
        }

        SubAgentRegistry.SubAgentRun run = registry.getRun(runId);
        if (run == null) {
            return SkillResult.error("Unknown subagent runId: " + runId);
        }
        // Security: prevent cross-parent reuse
        if (!parentSessionId.equals(run.parentSessionId)) {
            return SkillResult.error("runId belongs to a different parent session");
        }
        // Refuse continue on an explicitly terminated run. Necessary because the loop teardown
        // still reverts child.runtime_status to "idle" after terminate (the guard in
        // onSessionLoopFinished keeps run.status=TERMINATED sticky, but doesn't touch the
        // child entity). Without this check, a continue on a TERMINATED runId would slip
        // through the child runtime_status switch below.
        if ("TERMINATED".equals(run.status) || "CANCELLED".equals(run.status)) {
            return SkillResult.error("Subagent run is no longer reusable (status=" + run.status
                    + "). Use 'dispatch' to start a fresh agent.");
        }
        if (run.childSessionId == null || run.childSessionId.isBlank()) {
            return SkillResult.error("Subagent run has no attached child session yet");
        }

        SessionEntity child;
        try {
            child = sessionService.getSession(run.childSessionId);
        } catch (Exception e) {
            return SkillResult.error("Child session not found: " + run.childSessionId);
        }

        String status = child.getRuntimeStatus();
        switch (status == null ? "unknown" : status) {
            case "idle":
                // proceed
                break;
            case "running":
                return SkillResult.error("Child still running. Use 'terminate' first to discard, "
                        + "or 'dispatch' to start a new parallel session, or wait for completion.");
            case "waiting_user":
                return SkillResult.error("Child is waiting for user approval. "
                        + "Resolve the pending confirmation before sending follow-up.");
            case "error":
                return SkillResult.error("Child is in error state. Inspect the failure or 'terminate' "
                        + "before sending follow-up.");
            case "terminated":
                return SkillResult.error("Child session is no longer reusable (status=terminated).");
            default:
                return SkillResult.error("Child session is no longer reusable (status=" + status + ").");
        }

        // Load parent only now (after all early-return checks) — we only need it for userId
        // to pass to chatAsync, matching handleDispatch:194's call-site for symmetry.
        SessionEntity parent;
        try {
            parent = sessionService.getSession(parentSessionId);
        } catch (Exception e) {
            return SkillResult.error("Parent session not found: " + parentSessionId);
        }

        // Mark run RUNNING again + clear stale finalMessage/completedAt before kicking off the loop,
        // so 'list' immediately reflects the resume.
        registry.markRunResumed(runId);

        // OBS-4 §M1 INV-3/INV-4: preserveActiveRoot=true so the continued loop keeps the same
        // active_root_trace_id as prior dispatches on this child session.
        chatService.chatAsync(child.getId(), task, parent.getUserId(), true);

        log.info("SubAgent continued: runId={}, parent={}, child={}, agent={}",
                runId, parentSessionId, child.getId(), run.childAgentName);

        String taskPreview = task.length() > 200 ? task.substring(0, 200) + "…" : task;
        String shortRunId = runId.length() > 8 ? runId.substring(0, 8) : runId;
        String msg = "Continued subagent run " + shortRunId + ".\n"
                + "  childSessionId: " + child.getId() + "\n"
                + "  agent: " + (run.childAgentName == null ? "(unknown)" : run.childAgentName) + "\n"
                + "  task: " + taskPreview + "\n"
                + "  status: RUNNING (will deliver result asynchronously, same as dispatch)";
        return SkillResult.success(msg);
    }

    private SkillResult handleTerminate(Map<String, Object> input, SkillContext context) {
        String runId = asString(input.get("runId"));
        if (runId == null || runId.isBlank()) {
            return SkillResult.error("runId is required for terminate");
        }
        String parentSessionId = context.getSessionId();
        if (parentSessionId == null) {
            return SkillResult.error("No parent session in context — cannot terminate");
        }

        SubAgentRegistry.SubAgentRun run = registry.getRun(runId);
        if (run == null) {
            return SkillResult.error("Unknown subagent runId: " + runId);
        }
        // Security: prevent cross-parent terminate
        if (!parentSessionId.equals(run.parentSessionId)) {
            return SkillResult.error("runId belongs to a different parent session");
        }

        // Idempotent: if already in a terminal state, return success with note
        String runStatus = run.status;
        if (runStatus != null
                && ("TERMINATED".equals(runStatus)
                    || "COMPLETED".equals(runStatus)
                    || "FAILED".equals(runStatus)
                    || "CANCELLED".equals(runStatus))) {
            String shortRunId = runId.length() > 8 ? runId.substring(0, 8) : runId;
            return SkillResult.success("Subagent run " + shortRunId
                    + " was already in terminal state (" + runStatus + ").");
        }

        // Cancel any active loop on the child (no-op / returns false if not running).
        boolean cancelled = false;
        if (run.childSessionId != null && !run.childSessionId.isBlank()) {
            try {
                cancelled = cancellationRegistry.cancel(run.childSessionId);
            } catch (Exception e) {
                log.warn("Failed to cancel child session {} during terminate", run.childSessionId, e);
            }
            // Mark child runtime_status=terminated. ChatService.runLoop's finally has a
            // symmetric guard that preserves "terminated" (does not downgrade to "idle") when
            // the cancelled loop unwinds. Combined with the TERMINATED guard in
            // SubAgentRegistry.onSessionLoopFinished, both run-level status and child
            // runtime_status are sticky after terminate.
            try {
                SessionEntity child = sessionService.getSession(run.childSessionId);
                child.setRuntimeStatus("terminated");
                RuntimeFailureState.clear(child);
                sessionService.saveSession(child);
            } catch (Exception e) {
                log.warn("Failed to set child runtime_status=terminated for {}", run.childSessionId, e);
            }
        }

        registry.markRunTerminated(runId);

        log.info("SubAgent terminated: runId={}, parent={}, child={}, cancelledLoop={}",
                runId, parentSessionId, run.childSessionId, cancelled);

        String shortRunId = runId.length() > 8 ? runId.substring(0, 8) : runId;
        StringBuilder sb = new StringBuilder("Terminated subagent run ").append(shortRunId).append(".\n");
        if (run.childSessionId != null) {
            sb.append("  childSessionId: ").append(run.childSessionId).append("\n");
        }
        sb.append("  cancelledRunningLoop: ").append(cancelled).append("\n");
        sb.append("  status: TERMINATED");
        return SkillResult.success(sb.toString());
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
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
