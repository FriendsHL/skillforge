package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.InteractiveControlRequest;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.RootSessionLookup;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceStore.TraceFinalizeRequest;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.memory.SessionDigestExtractor;
import com.skillforge.server.subagent.CollabRunService;
import com.skillforge.server.subagent.SubAgentRegistry;
import com.skillforge.server.util.StackTraceFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;


import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AgentService agentService;
    private final SessionService sessionService;
    private final SkillRegistry skillRegistry;
    private final AgentLoopEngine agentLoopEngine;
    private final ModelUsageRepository modelUsageRepository;
    private final ChatEventBroadcaster broadcaster;
    private final ThreadPoolExecutor chatLoopExecutor;
    private final SessionTitleService sessionTitleService;
    private final SubAgentRegistry subAgentRegistry;
    private final CancellationRegistry cancellationRegistry;
    private final CompactionService compactionService;
    private final CollabRunRepository collabRunRepository;
    private final CollabRunService collabRunService;
    private final ObjectMapper objectMapper;
    private final SessionDigestExtractor sessionDigestExtractor;
    private final LifecycleHookDispatcher lifecycleHookDispatcher;
    private final SessionConfirmCache sessionConfirmCache;
    private final PendingConfirmationRegistry pendingConfirmationRegistry;
    private final RootSessionLookup rootSessionLookup;
    /**
     * OBS-2 M1 §D.8.1 (r2 review r2): exception path 保底 finalize 需要直接访问 traceStore。
     * 注入后 Spring 启动失败会先报错（traceStore 是 OBS-1 既有 @Service Bean），不再用 null guard。
     */
    private final LlmTraceStore traceStore;

    public ChatService(AgentService agentService,
                       SessionService sessionService,
                       SkillRegistry skillRegistry,
                       AgentLoopEngine agentLoopEngine,
                       ModelUsageRepository modelUsageRepository,
                       ChatEventBroadcaster broadcaster,
                       @Qualifier("chatLoopExecutor") ThreadPoolExecutor chatLoopExecutor,
                       SessionTitleService sessionTitleService,
                       SubAgentRegistry subAgentRegistry,
                       CancellationRegistry cancellationRegistry,
                       CompactionService compactionService,
                       CollabRunRepository collabRunRepository,
                       CollabRunService collabRunService,
                       ObjectMapper objectMapper,
                       SessionDigestExtractor sessionDigestExtractor,
                       LifecycleHookDispatcher lifecycleHookDispatcher,
                       SessionConfirmCache sessionConfirmCache,
                       PendingConfirmationRegistry pendingConfirmationRegistry,
                       RootSessionLookup rootSessionLookup,
                       LlmTraceStore traceStore) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.skillRegistry = skillRegistry;
        this.agentLoopEngine = agentLoopEngine;
        this.modelUsageRepository = modelUsageRepository;
        this.broadcaster = broadcaster;
        this.chatLoopExecutor = chatLoopExecutor;
        this.sessionTitleService = sessionTitleService;
        this.subAgentRegistry = subAgentRegistry;
        this.cancellationRegistry = cancellationRegistry;
        this.compactionService = compactionService;
        this.collabRunRepository = collabRunRepository;
        this.collabRunService = collabRunService;
        this.objectMapper = objectMapper;
        this.sessionDigestExtractor = sessionDigestExtractor;
        this.lifecycleHookDispatcher = lifecycleHookDispatcher;
        this.sessionConfirmCache = sessionConfirmCache;
        this.pendingConfirmationRegistry = pendingConfirmationRegistry;
        this.rootSessionLookup = rootSessionLookup;
        this.traceStore = traceStore;
    }

    /**
     * 异步启动 chat loop。
     * 同步完成:持久化 user message、广播 running、提交线程池。
     * 异步执行:engine.run() + 持久化结果 + 广播 idle/error。
     *
     * <p>3-arg overload — 默认 {@code preserveActiveRoot=false}：当作真正的 user message
     * 边界处理（清空 active_root_trace_id 后开新 root）。控制器入口 / 用户输入路径用此版本。
     *
     * @throws RejectedExecutionException 线程池满(controller 层捕获返 429)
     */
    public void chatAsync(String sessionId, String userMessage, Long userId) {
        chatAsync(sessionId, userMessage, userId, false);
    }

    /**
     * 异步启动 chat loop（OBS-4 4-arg overload）。
     *
     * <p>{@code preserveActiveRoot=true}：合成续接路径（subagent 结果回投、peer 消息、
     * spawn child 后投递任务 brief、startup recovery 续跑），不要清空 session.active_root_trace_id，
     * 让本次 trace 继承同一 root（OBS-4 INV-3/INV-4/INV-6）。
     *
     * <p>{@code preserveActiveRoot=false}：真正的 user message 边界（控制器入口），
     * 清空 active_root → 开新 root（OBS-4 INV-5）。
     *
     * @throws RejectedExecutionException 线程池满(controller 层捕获返 429)
     */
    public void chatAsync(String sessionId, String userMessage, Long userId,
                          boolean preserveActiveRoot) {
        // 1. 读当前 session 和 agent
        SessionEntity session = sessionService.getSession(sessionId);
        AgentEntity agentEntity = agentService.getAgent(session.getAgentId());

        // CRITICAL: 整段 state transition (B3 check + messages 落盘 + runtime=running)
        // 必须在 CompactionService 的同一把 stripe lock 下进行, 否则 C1 compact 请求可能
        // 在我们检查 runtimeStatus == "running" 之前抢进来 (TOCTOU).
        synchronized (compactionService.lockFor(sessionId)) {
            // Re-read session inside lock to avoid TOCTOU on runtimeStatus
            session = sessionService.getSession(sessionId);

            if ("waiting_user".equals(session.getRuntimeStatus())) {
                if (sessionService.findPendingConfirmation(sessionId).isPresent()) {
                    throw new IllegalStateException("pending confirmation must be resolved first");
                }
                sessionService.findPendingAsk(sessionId).ifPresent(ask ->
                        sessionService.markControlAnswered(
                                sessionId,
                                SessionService.MESSAGE_TYPE_ASK_USER,
                                ask.getControlId(),
                                "superseded",
                                userMessage,
                                "direct_input"));
            }

            // If session is already running, enqueue the message instead of starting a new loop
            if ("running".equals(session.getRuntimeStatus())) {
                LoopContext ctx = cancellationRegistry.getContext(sessionId);
                if (ctx != null) {
                    ctx.enqueueUserMessage(userMessage);
                    try {
                        sessionService.appendNormalMessages(sessionId, List.of(Message.user(userMessage)));
                        session.setLastUserMessageAt(java.time.Instant.now());
                        sessionService.saveSession(session);
                    } catch (Exception e) {
                        log.warn("Failed to append queued message to session_message, message is queued in-memory only: sessionId={}", sessionId, e);
                        // Don't save inconsistent state — message is still in the in-memory queue
                        // and will be persisted when the engine drains it
                        return;
                    }
                    // Broadcast so frontend shows the message immediately.
                    // OBS-2 M1 §A.1 row 3: enqueue path → traceId=null (corresponding trace not yet created;
                    // 队列消息归并到当前 running loop 的下一轮 LLM call，本身不开新 trace)。
                    if (broadcaster != null) {
                        broadcaster.messageAppended(sessionId, null, Message.user(userMessage));
                    }
                    log.info("Enqueued user message for running session {}", sessionId);
                    return;
                }
                // ctx is null = race condition (loop just finished). Fall through to normal path.
                log.warn("Session {} is running but no LoopContext found, falling through to normal chatAsync", sessionId);
            }

            // B3 idle-gap light compact: 会话空置 > 12h 且消息 > 10 条时, 先跑一次 light 压缩
            // 再追加 user message。只对 parent session 触发 (子 session 不走 user 交互).
            // 用 lastUserMessageAt (非 updatedAt) 以避免被 runtime_status 写入/smart title 污染.
            try {
                java.time.Instant lastUserMsgAt = session.getLastUserMessageAt();
                if (lastUserMsgAt != null
                        && session.getMessageCount() > 10
                        && session.getParentSessionId() == null) {
                    long gapHours = java.time.Duration.between(lastUserMsgAt, java.time.Instant.now()).toHours();
                    if (gapHours >= 12) {
                        log.info("B3 engine-gap light compact: sessionId={} gap={}h", sessionId, gapHours);
                        compactionService.compact(sessionId, "light", "engine-gap",
                                "gap=" + gapHours + "h since " + lastUserMsgAt);
                        // 重载 session 因为 compact 可能已修改 messageCount / lastCompactedAt
                        session = sessionService.getSession(sessionId);
                    }
                }
            } catch (Exception e) {
                log.warn("B3 engine-gap compact failed, continuing: sessionId={}", sessionId, e);
            }

            // 2. 把 user message 立即持久化到行存储，这样前端刷新也能看到。
            //    B3 必须在这一步之前完成, 否则旧 gap 会被新 user message 打掉。
            // OBS-2 M1 §D.1 §D.5: 在持久化前生成 traceId，让 user message + 后续 engine output 共享同一 trace。
            String traceId = UUID.randomUUID().toString();

            // OBS-4 §2.1 §C.2: user message 边界处理 + active_root_trace_id 决策。
            // 两条路径分支：
            //   - preserveActiveRoot=false (真实用户输入)：单事务原子重置 active_root = traceId
            //     (INV-5: 新 user message 必开新 root)。W2/W3 r1 fix：合并 clear+get+set 三事务为
            //     单 allocateNewRootTraceId，消除窗口期 + 冗余 DB read。
            //   - preserveActiveRoot=true (合成续接：subagent 结果回投 / peer 消息 / spawn child 投递
            //     task brief / startup recovery)：直接 read 已有 active_root 继承 (INV-3/INV-4/INV-6)；
            //     spawn 路径在 spawnMember 时已设好 child.active_root，正常情况 read 必非 null；
            //     null 时 defensive 自己当 root 并回填（兜底，不应在正常流程触发）。
            // 失败抛 → outer try-catch 不存在，但 chatLoopExecutor 还没提交，所以 user message 行也没落
            // → session 保持 idle 状态，下次重试时按"新 root"语义走。
            String rootTraceId;
            if (!preserveActiveRoot) {
                // 真实 user message 边界：单事务 set active_root = traceId
                rootTraceId = traceId;
                sessionService.allocateNewRootTraceId(sessionId, rootTraceId);
            } else {
                // 合成续接：read 已有 active_root 继承
                String existingActiveRoot = sessionService.getActiveRootTraceId(sessionId);
                if (existingActiveRoot == null) {
                    // Defensive 兜底：spawn 链 / restart resume 异常导致 active_root 缺失时
                    // fallback 自己当 root（不破坏 trace 完整性，仅退化为单 trace 视图）。
                    rootTraceId = traceId;
                    sessionService.setActiveRootTraceId(sessionId, rootTraceId);
                } else {
                    rootTraceId = existingActiveRoot;
                }
            }

            List<Message> fullHistory = sessionService.getFullHistory(sessionId);
            List<Message> history = sessionService.getContextMessages(sessionId);
            Message userMsg = Message.user(userMessage);
            sessionService.appendNormalMessages(sessionId, List.of(userMsg), traceId);

            // 2.1 第一条 user message 时立即生成截断标题(同步,极快)
            // 同时触发 SessionStart lifecycle hook（仅在首条消息时，非每轮）
            if (fullHistory.isEmpty()) {
                sessionTitleService.applyImmediateTitle(sessionId, userMessage);
                try {
                    AgentDefinition sessionStartDef = agentService.toAgentDefinition(agentEntity);
                    boolean keepGoing = lifecycleHookDispatcher.fireSessionStart(
                            sessionStartDef, sessionId, userId);
                    if (!keepGoing) {
                        // ABORT: persist error state, broadcast, do not submit to executor.
                        log.warn("SessionStart hook aborted session {}; refusing to start loop", sessionId);
                        session = sessionService.getSession(sessionId);
                        session.setRuntimeStatus("error");
                        session.setRuntimeStep(null);
                        session.setRuntimeError("Aborted by SessionStart hook");
                        session.setCompletedAt(java.time.Instant.now());
                        sessionService.saveSession(session);
                        if (broadcaster != null) {
                            broadcaster.sessionStatus(sessionId, "error", null,
                                    "Aborted by SessionStart hook");
                            broadcaster.userEvent(session.getUserId(),
                                    sessionUpdatedPayload(session, fullHistory.size() + 1));
                        }
                        return;
                    }
                } catch (Exception e) {
                    log.warn("SessionStart hook dispatch threw (session={}): {}", sessionId, e.toString());
                }
            }

            // 3. 更新 runtime 状态 + 记录 lastUserMessageAt
            session = sessionService.getSession(sessionId);
            session.setRuntimeStatus("running");
            session.setRuntimeStep("Starting");
            session.setRuntimeError(null);
            session.setLastUserMessageAt(java.time.Instant.now());
            sessionService.saveSession(session);

            // 4. 广播 user message + running 状态
            // OBS-2 M1 §A.1 row 4 / §D.5: 广播 traceId，供前端 trace 关联。
            if (broadcaster != null) {
                broadcaster.messageAppended(sessionId, traceId, userMsg);
                broadcaster.sessionStatus(sessionId, "running", "Starting", null);
                broadcaster.userEvent(session.getUserId(), sessionUpdatedPayload(session, fullHistory.size() + 1));
            }

            // 5. 提交到线程池异步跑 loop
            final List<Message> historyForLoop = history;
            final String capturedTraceId = traceId;
            final String capturedRootTraceId = rootTraceId;
            chatLoopExecutor.execute(() -> runLoop(sessionId, userMessage, userId, agentEntity, historyForLoop, capturedTraceId, capturedRootTraceId));
        }
    }

    /**
     * 组装 per-user 通道的 session_updated 轻量载荷(只含列表卡片需要的字段)。
     */
    private Map<String, Object> sessionUpdatedPayload(SessionEntity s, int messageCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "session_updated");
        m.put("sessionId", s.getId());
        m.put("runtimeStatus", s.getRuntimeStatus());
        m.put("runtimeStep", s.getRuntimeStep());
        m.put("runtimeError", s.getRuntimeError());
        m.put("messageCount", messageCount);
        m.put("title", s.getTitle());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    /**
     * 实际的 loop 执行(在 chatLoopExecutor 线程里跑)。
     * <p>OBS-2 M1: 5-arg overload 委托给 7-arg 版本（externalTraceId=null → 委托内部生成）。
     * 保留以兼容尚未传 traceId 的调用方（生产路径全部已更新）。
     */
    private void runLoop(String sessionId, String userMessage, Long userId,
                         AgentEntity agentEntity, List<Message> history) {
        runLoop(sessionId, userMessage, userId, agentEntity, history, null, null);
    }

    /**
     * 6-arg overload 委托给 7-arg 版本（externalRootTraceId=null → engine 调用 store 时
     * 由 SQL COALESCE 兜底为 traceId 自身，自己当 root，跟历史 backfill 行为一致）。
     */
    private void runLoop(String sessionId, String userMessage, Long userId,
                         AgentEntity agentEntity, List<Message> history,
                         String externalTraceId) {
        runLoop(sessionId, userMessage, userId, agentEntity, history, externalTraceId, null);
    }

    /**
     * 实际的 loop 执行(在 chatLoopExecutor 线程里跑)。
     * <p>OBS-2 M1 §D.2: 加 externalTraceId 参数 — 由 chatAsync / answerAsk / answerConfirmation
     * 在 submit 前生成 UUID 并透传，让 user message 广播 + engine 内部 trace 共享同一 traceId。
     * null 时内部生成 fallback（兜底，正常路径不应触发）。
     * <p>OBS-4 §2.2: 加 externalRootTraceId 参数 — 由 chatAsync / answerAsk / answerConfirmation
     * 决策后透传到 engine（engine 写入 t_llm_trace.root_trace_id）。null 时存储层 SQL
     * COALESCE 兜底为 traceId 自身（自己当 root）。
     */
    private void runLoop(String sessionId, String userMessage, Long userId,
                         AgentEntity agentEntity, List<Message> history,
                         String externalTraceId, String externalRootTraceId) {
        // OBS-2 M1 §D.8.2: 显式 startedAt 给 §D.8.3 catch 块 finalize 使用。
        final long startedAt = System.currentTimeMillis();
        String finalMessage = null;
        int toolCallCount = 0;
        String finalStatus = "completed";
        // OBS-2 M1 §D.1: traceId 在 runLoop 入口必须存在 — 优先使用调用方传入的，否则 fallback 生成。
        final String traceId = externalTraceId != null ? externalTraceId : UUID.randomUUID().toString();
        LoopContext preCtx = null;
        try {
            // 解析 agent definition,并把 session 的 executionMode 注入 config
            AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
            SessionEntity freshSession = sessionService.getSession(sessionId);
            String mode = freshSession.getExecutionMode();
            if (mode == null || mode.isBlank()) {
                mode = agentEntity.getExecutionMode() != null ? agentEntity.getExecutionMode() : "ask";
            }
            agentDef.getConfig().put("execution_mode", mode);

            // 把当前 session 对应模型的 contextWindowTokens 注入 agentDef.config,
            // engine 的 B1/B2 安全网会用它做 ratio 计算。否则会 fallback 到硬编码 32000,
            // 对 200k context 的模型(Claude/Gemini)永远不会触发,对 16k 模型又会过早触发。
            int sessionContextWindow = compactionService.resolveContextWindowForSession(freshSession);
            agentDef.getConfig().put("context_window_tokens", sessionContextWindow);

            // lightContext: strip SOUL.md, TOOLS.md, and memory for lightweight child agents
            if (freshSession.isLightContext()) {
                agentDef.setSoulPrompt(null);
                agentDef.setToolsPrompt(null);
                agentDef.getConfig().put("skip_memory", true);
                log.info("lightContext enabled for session={}, stripping soul/tools prompts and memory", sessionId);
            }

            // Inject team leader coordination instructions into system prompt
            String collabRunIdForPrompt = freshSession.getCollabRunId();
            if (collabRunIdForPrompt != null) {
                CollabRunEntity collabRunForPrompt = collabRunRepository.findById(collabRunIdForPrompt).orElse(null);
                if (collabRunForPrompt != null && sessionId.equals(collabRunForPrompt.getLeaderSessionId())) {
                    // This is the leader session — inject coordination guidelines
                    String existing = agentDef.getSystemPrompt() != null ? agentDef.getSystemPrompt() : "";
                    agentDef.setSystemPrompt(existing + "\n\n## Team Leader Protocol\n\n"
                            + "You are the LEADER of a multi-agent team. Your role is to DELEGATE, not to do the work yourself.\n\n"
                            + "**Workflow:**\n"
                            + "1. Analyze the user's request and break it into sub-tasks\n"
                            + "2. Use TeamCreate to spawn team members for each sub-task (you can spawn multiple in one turn)\n"
                            + "3. After spawning all members, STOP calling tools and end your turn with a brief summary of what you delegated\n"
                            + "4. Wait for [TeamResult] messages to arrive automatically — do NOT poll or call TeamList\n"
                            + "5. Once all results arrive, synthesize them into a final response for the user\n\n"
                            + "**Rules:**\n"
                            + "- Do NOT use Bash, FileRead, Grep, or other tools yourself — delegate to team members\n"
                            + "- Do NOT do research or investigation yourself — that's what team members are for\n"
                            + "- You MAY use TeamSend to send additional context to a running member\n"
                            + "- You MAY use TeamKill to cancel a member that is no longer needed\n"
                            + "- If a member's result is insufficient, spawn a new member with a refined task\n");
                }
            }

            // 收集 zip 包 Skill 定义
            List<SkillDefinition> skills = new ArrayList<>();
            for (String skillId : agentDef.getSkillIds()) {
                skillRegistry.getSkillDefinition(skillId).ifPresent(skills::add);
            }

            log.info("Running agent loop (async): sessionId={}, agentId={}, mode={}", sessionId, agentEntity.getId(), mode);
            // 预建 LoopContext 并注册到 CancellationRegistry, 让 /cancel 端点可以找到它
            preCtx = new LoopContext();
            // OBS-2 M1 §D.1: 透传 traceId 到 engine，让 rootSpan id == traceId 形成单一锚点。
            preCtx.setTraceId(traceId);
            // OBS-4 §2.2: 透传 rootTraceId 到 engine，让 t_llm_trace.root_trace_id 写入对应 root。
            // null 时存储层 SQL 用 COALESCE 兜底为 trace_id 自身（自己当 root）。
            preCtx.setRootTraceId(externalRootTraceId);

            // Depth-aware tool filtering: if session is in a collab run and at max depth,
            // exclude TeamCreate and SubAgent skills to prevent leaf agents from spawning further agents
            String collabRunId = freshSession.getCollabRunId();
            if (collabRunId != null) {
                CollabRunEntity collabRun = collabRunRepository.findById(collabRunId).orElse(null);
                if (collabRun != null && freshSession.getDepth() >= collabRun.getMaxDepth()) {
                    Set<String> excluded = new HashSet<>();
                    excluded.add("TeamCreate");
                    excluded.add("SubAgent");
                    preCtx.setExcludedSkillNames(excluded);
                    log.info("Depth-aware filtering: excluding TeamCreate/SubAgent for session={} at depth={} (maxDepth={})",
                            sessionId, freshSession.getDepth(), collabRun.getMaxDepth());
                }
            }

            // Apply allowedToolNames from agent config (tool_ids)
            Object toolIdsObj = agentDef.getConfig().get("tool_ids");
            if (toolIdsObj instanceof List && !((List<?>) toolIdsObj).isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> toolIdList = (List<String>) toolIdsObj;
                preCtx.setAllowedToolNames(new HashSet<>(toolIdList));
                log.info("Tool filtering: only allowing {} tools for session={}", toolIdList.size(), sessionId);
            }

            // Apply maxLoops: session override > agent config > engine default (25)
            Integer sessionMaxLoops = freshSession.getMaxLoops();
            if (sessionMaxLoops != null && sessionMaxLoops > 0) {
                preCtx.setMaxLoops(sessionMaxLoops);
            } else {
                Object maxLoopsObj = agentDef.getConfig().get("max_loops");
                if (maxLoopsObj instanceof Number) {
                    preCtx.setMaxLoops(((Number) maxLoopsObj).intValue());
                }
            }
            // Safety cap: maxLoops cannot exceed 200
            if (preCtx.getMaxLoops() > 200) {
                preCtx.setMaxLoops(200);
                log.warn("maxLoops capped at 200 for session={}", sessionId);
            }
            if (preCtx.getMaxLoops() != 25) {
                log.info("maxLoops override: {} for session={}", preCtx.getMaxLoops(), sessionId);
            }

            // Compute session idle duration for time-based cold cleanup (P9-3)
            Instant lastActivity = freshSession.getLastUserMessageAt();
            if (lastActivity != null) {
                long idleSeconds = java.time.Duration.between(lastActivity, Instant.now()).getSeconds();
                preCtx.setSessionIdleSeconds(Math.max(0, idleSeconds));
            }

            cancellationRegistry.register(sessionId, preCtx);
            LoopResult result = agentLoopEngine.run(agentDef, userMessage, history, sessionId, userId, preCtx);
            finalMessage = result.getFinalResponse();
            toolCallCount = result.getToolCalls() != null ? result.getToolCalls().size() : 0;
            boolean waitingUser = "waiting_user".equals(result.getStatus());

            boolean wasCancelled = "cancelled".equals(result.getStatus());
            if (wasCancelled) {
                finalStatus = "cancelled";
            }
            boolean wasAbortedByHook = "aborted_by_hook".equals(result.getStatus());
            if (wasAbortedByHook) {
                finalStatus = "aborted_by_hook";
            }

            // Drain any remaining queued messages that arrived after the loop ended,
            // then unregister from CancellationRegistry BEFORE saving messages.
            // This ensures no concurrent chatAsync can enqueue+persist between drain and save.
            List<String> remaining = preCtx.drainPendingUserMessages();
            List<Message> finalMessages = result.getMessages();
            if (!remaining.isEmpty()) {
                for (String text : remaining) {
                    finalMessages.add(Message.user(text));
                }
                log.info("Appended {} remaining queued messages after loop end: sessionId={}", remaining.size(), sessionId);
            }

            // 几种静默退出：把 finalMessage 作为 assistant 消息追加，让用户看到原因。
            // P9-2: max_tokens_exhausted 也是显式失败，同样落消息让用户可诊断。
            String resultStatus = result.getStatus();
            boolean isSilentExit = "token_budget_exceeded".equals(resultStatus)
                    || "duration_exceeded".equals(resultStatus)
                    || "max_loops_reached".equals(resultStatus)
                    || "max_tokens_exhausted".equals(resultStatus);
            if (isSilentExit && finalMessage != null && !finalMessage.isBlank()) {
                finalStatus = resultStatus;   // ← 同步 finalStatus，确保 subAgentRegistry 和 SessionEnd hook 收到正确 reason
                Message notifyMsg = Message.assistant(finalMessage);
                finalMessages.add(notifyMsg);
                // OBS-2 M1 §A.1 row 5 / §D.3: 静默退出 notify 也归当前 trace。
                if (broadcaster != null) {
                    broadcaster.messageAppended(sessionId, preCtx.getTraceId(), notifyMsg);
                }
                log.info("Silent exit notified to user: status={}, sessionId={}", resultStatus, sessionId);
            }

            cancellationRegistry.unregister(sessionId);

            // 保存最终 messages(engine 已经把 user msg + 之后所有消息组装好了)
            // OBS-2 M1 §D.5: 透传 traceId 让 engine 输出（assistant / tool_result）行 trace_id 不为 null。
            sessionService.updateSessionMessages(sessionId, finalMessages,
                    result.getTotalInputTokens(), result.getTotalOutputTokens(), traceId);

            if (waitingUser) {
                persistPendingControl(sessionId, result.getPendingControl());
                ModelUsageEntity usage = new ModelUsageEntity();
                usage.setUserId(userId);
                usage.setAgentId(agentEntity.getId());
                usage.setSessionId(sessionId);
                usage.setModelId(agentDef.getModelId());
                usage.setInputTokens((int) result.getTotalInputTokens());
                usage.setOutputTokens((int) result.getTotalOutputTokens());
                try {
                    usage.setToolCalls(objectMapper.writeValueAsString(result.getToolCalls()));
                } catch (JsonProcessingException e) {
                    usage.setToolCalls("[]");
                }
                modelUsageRepository.save(usage);

                SessionEntity s = sessionService.getSession(sessionId);
                s.setCompletedAt(java.time.Instant.now());
                s.setRuntimeStatus("waiting_user");
                s.setRuntimeStep("waiting_control");
                s.setRuntimeError(null);
                sessionService.saveSession(s);
                if (broadcaster != null) {
                    broadcaster.sessionStatus(sessionId, "waiting_user", "waiting_control", null);
                    broadcaster.userEvent(s.getUserId(), sessionUpdatedPayload(s, s.getMessageCount()));
                }
                finalStatus = "waiting_user";
                return;
            }

            // 记录 ModelUsage
            ModelUsageEntity usage = new ModelUsageEntity();
            usage.setUserId(userId);
            usage.setAgentId(agentEntity.getId());
            usage.setSessionId(sessionId);
            usage.setModelId(agentDef.getModelId());
            usage.setInputTokens((int) result.getTotalInputTokens());
            usage.setOutputTokens((int) result.getTotalOutputTokens());
            try {
                usage.setToolCalls(objectMapper.writeValueAsString(result.getToolCalls()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize tool calls", e);
                usage.setToolCalls("[]");
            }
            modelUsageRepository.save(usage);

            // 更新 session runtime 状态 = idle / error
            // 取消退出也是 idle, 通过 step="cancelled" 标注, 避免引入新的 runtimeStatus 枚举值
            // aborted_by_hook → error + message，视为用户显式拒绝的流程
            SessionEntity s = sessionService.getSession(sessionId);
            s.setCompletedAt(java.time.Instant.now());
            if (wasAbortedByHook) {
                s.setRuntimeStatus("error");
                s.setRuntimeStep(null);
                s.setRuntimeError(finalMessage != null ? finalMessage : "Aborted by lifecycle hook");
            } else {
                s.setRuntimeStatus("idle");
                s.setRuntimeStep(wasCancelled ? "cancelled" : null);
                s.setRuntimeError(null);
            }
            sessionService.saveSession(s);
            if (broadcaster != null) {
                if (wasAbortedByHook) {
                    broadcaster.sessionStatus(sessionId, "error", null, s.getRuntimeError());
                } else {
                    broadcaster.sessionStatus(sessionId, "idle", wasCancelled ? "cancelled" : null, null);
                }
                broadcaster.userEvent(s.getUserId(), sessionUpdatedPayload(s, result.getMessages().size()));
            }

            // 在 loop 完成后(messages 已经累积了若干轮)异步触发智能命名
            int finalCount = result.getMessages().size();
            log.info("Triggering maybeScheduleSmartRename: sessionId={}, msgCount={}", sessionId, finalCount);
            sessionTitleService.maybeScheduleSmartRename(sessionId, finalCount);

            // 异步触发记忆提取:不等待,失败不影响主流程
            sessionDigestExtractor.triggerExtractionAsync(sessionId);

            // SessionEnd lifecycle hook (异步执行 via hookExecutor，本身不阻塞这里).
            // reason: completed / cancelled / aborted_by_hook
            // by-design: uses startup snapshot of agentDef to avoid reading stale DB state during session teardown
            try {
                String reasonStr = wasCancelled ? "cancelled"
                        : wasAbortedByHook ? "aborted_by_hook" : "completed";
                lifecycleHookDispatcher.fireSessionEnd(agentDef, sessionId, userId,
                        finalCount, reasonStr);
            } catch (Exception e) {
                log.warn("SessionEnd hook dispatch threw (session={}): {}", sessionId, e.toString());
            }

            log.info("Agent loop completed: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("Agent loop failed: sessionId={}", sessionId, e);
            finalStatus = "error";
            // Generic, non-leaking message for client-facing surfaces.
            // Full exception detail is captured in the server log above.
            // 简要错误（用于 finalMessage / assistant-facing message 兜底）保持不变；
            // runtime_error 和 WS error 字段改成完整异常详情，便于追溯根因。
            String safeError = "Agent loop failed";
            // errorDetail 仅包含原始异常栈文本，前缀由前端/消费方自行组装（避免双层前缀）。
            String errorDetail = StackTraceFormatter.format(e, 10);
            finalMessage = safeError;
            // OBS-2 M1 §D.8.3 (r2 review r2): exception path 保底 finalize trace。
            // engine 抛 unhandled exception 时确保 t_llm_trace.status 不留 'running'。
            // toolCallCount/eventCount 用 0/0 fallback（exception 路径下 engine 局部计数器
            // 不可达，0 是合理 fallback：前端按 trace 拉 spans 实际算计数）。
            // 与 engine 正常 finalize 互斥：engine 已 finalize → status terminal → §B.3
            // `WHERE status='running'` 守卫让本次 UPDATE 0 rows（幂等）。
            try {
                traceStore.finalizeTrace(new TraceFinalizeRequest(
                        traceId,
                        "error",
                        "agent_loop_exception",
                        System.currentTimeMillis() - startedAt,
                        0, 0,
                        Instant.now()));
            } catch (Exception ignored) {
                /* observability 失败不影响主路径 */
            }
            try {
                SessionEntity s = sessionService.getSession(sessionId);
                s.setCompletedAt(java.time.Instant.now());
                s.setRuntimeStatus("error");
                s.setRuntimeStep(null);
                s.setRuntimeError(errorDetail);
                sessionService.saveSession(s);
                if (broadcaster != null) {
                    broadcaster.sessionStatus(sessionId, "error", null, errorDetail);
                    broadcaster.userEvent(s.getUserId(), sessionUpdatedPayload(s, s.getMessageCount()));
                }
                // SessionEnd hook on error path as well (reason=error)
                try {
                    AgentDefinition errDef = agentService.toAgentDefinition(agentEntity);
                    lifecycleHookDispatcher.fireSessionEnd(errDef, sessionId, userId,
                            s.getMessageCount(), "error");
                } catch (Exception hookErr) {
                    log.warn("SessionEnd hook dispatch on error path failed: {}", hookErr.toString());
                }
            } catch (Exception inner) {
                log.error("Failed to mark session error: sessionId={}", sessionId, inner);
            }
        } finally {
            // Ensure CancellationRegistry is cleaned up (may already be done in happy path)
            try {
                cancellationRegistry.unregister(sessionId);
            } catch (Exception ignored) {
            }
            // Wake any pending install confirmation for this session (cancel cascade).
            // Safe to always invoke — no-op when no pending confirmation exists.
            try {
                if (!"waiting_user".equals(finalStatus) && pendingConfirmationRegistry != null) {
                    pendingConfirmationRegistry.completeAllForSession(sessionId, Decision.DENIED);
                }
            } catch (Exception ignored) {
            }
            // r3: only a true root session clears its install-confirm cache. Child sessions
            // inherit the root's approvals and must not wipe them on their own loop end.
            try {
                if (!"waiting_user".equals(finalStatus) && sessionConfirmCache != null) {
                    String rootSid = rootSessionLookup != null
                            ? rootSessionLookup.resolveRoot(sessionId)
                            : sessionId;
                    if (sessionId != null && sessionId.equals(rootSid)) {
                        sessionConfirmCache.clear(rootSid);
                    }
                }
            } catch (Exception ignored) {
            }
            // SubAgent 回调钩子:如果这是子 session,把结果 push 到父;如果这是父,drain 等待中的子结果
            try {
                if (!"waiting_user".equals(finalStatus)) {
                    subAgentRegistry.onSessionLoopFinished(sessionId, finalMessage, finalStatus,
                            toolCallCount, System.currentTimeMillis() - startedAt);
                }
            } catch (Exception hookErr) {
                log.error("SubAgentRegistry hook failed: sessionId={}", sessionId, hookErr);
            }

            // CollabRun hooks: cancel cascade FIRST, then notify completion (null-safe for tests)
            try {
                if (!"waiting_user".equals(finalStatus)
                        && collabRunService != null && collabRunRepository != null) {
                    SessionEntity finishedSession = sessionService.getSession(sessionId);
                    String finishedCollabRunId = finishedSession.getCollabRunId();
                    if (finishedCollabRunId != null) {
                        // Cancel cascade FIRST: if leader was cancelled, cancel all others before marking completion
                        if ("cancelled".equals(finalStatus)) {
                            CollabRunEntity collabRun = collabRunRepository.findById(finishedCollabRunId).orElse(null);
                            if (collabRun != null && sessionId.equals(collabRun.getLeaderSessionId())) {
                                log.info("Cancel cascade: leader session {} cancelled, cancelling entire collab run {}",
                                        sessionId, finishedCollabRunId);
                                collabRunService.cancelRun(finishedCollabRunId);
                            }
                        }
                        // Then notify collab run of member completion
                        collabRunService.onMemberCompleted(finishedCollabRunId, sessionId);
                    }
                }
            } catch (Exception collabErr) {
                log.error("CollabRun hook failed: sessionId={}", sessionId, collabErr);
            }
        }
    }

    public void answerAsk(String sessionId, String askId, String answer, Long userId) {
        SessionMessageEntity control = sessionService.getControlMessage(
                sessionId, SessionService.MESSAGE_TYPE_ASK_USER, askId);
        Map<String, Object> metadata = readMetadata(control);
        Object toolUseIdObj = metadata.get("toolUseId");
        String toolUseId = toolUseIdObj != null ? toolUseIdObj.toString() : null;
        if (toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalStateException("ask continuation missing toolUseId");
        }
        synchronized (compactionService.lockFor(sessionId)) {
            sessionService.markControlAnswered(
                    sessionId,
                    SessionService.MESSAGE_TYPE_ASK_USER,
                    askId,
                    "answered",
                    answer,
                    "card");
            Message toolResult = Message.toolResult(toolUseId, "User answered: " + answer, false);
            // OBS-2 M1 §D.4: resumeTraceId 在持久化前生成 — 让 toolResult + 后续 engine 共享同一 trace。
            String resumeTraceId = UUID.randomUUID().toString();

            // OBS-4 §2.1 §6.2: ask answer 是 user message 内的续接（原任务还没完），不是新的
            // user message 边界。读 active_root：非 null 继承（INV-3）；null 则 defensive 自己当 root
            // 并回填（兜底，正常流程上一个 trace 创建时已经回填过）。
            String existingActiveRoot = sessionService.getActiveRootTraceId(sessionId);
            String resumeRootTraceId;
            if (existingActiveRoot == null) {
                resumeRootTraceId = resumeTraceId;
                sessionService.setActiveRootTraceId(sessionId, resumeRootTraceId);
            } else {
                resumeRootTraceId = existingActiveRoot;
            }

            sessionService.appendNormalMessages(sessionId, List.of(toolResult), resumeTraceId);
            SessionEntity session = sessionService.getSession(sessionId);
            AgentEntity agentEntity = agentService.getAgent(session.getAgentId());
            session.setRuntimeStatus("running");
            session.setRuntimeStep("Resuming");
            session.setRuntimeError(null);
            sessionService.saveSession(session);
            List<Message> history = sessionService.getContextMessages(sessionId);
            if (broadcaster != null) {
                broadcaster.messageAppended(sessionId, resumeTraceId, toolResult);
                broadcaster.sessionStatus(sessionId, "running", "Resuming", null);
            }
            final String capturedRootTraceId = resumeRootTraceId;
            chatLoopExecutor.execute(() -> runLoop(sessionId, null, userId, agentEntity, history, resumeTraceId, capturedRootTraceId));
        }
    }

    public void answerConfirmation(String sessionId, String confirmationId, Decision decision, Long userId) {
        SessionMessageEntity control = sessionService.getControlMessage(
                sessionId, SessionService.MESSAGE_TYPE_CONFIRMATION, confirmationId);
        Map<String, Object> metadata = readMetadata(control);
        String toolUseId = stringValue(metadata.get("toolUseId"));
        String toolName = stringValue(metadata.get("toolName"));
        String confirmationKind = stringValue(metadata.get("confirmationKind"));
        String installTool = stringValue(metadata.get("installTool"));
        String installTarget = stringValue(metadata.get("installTarget"));
        Map<String, Object> toolInput = mapValue(metadata.get("toolInput"));
        if (toolUseId == null || toolUseId.isBlank() || toolName == null || toolName.isBlank()) {
            throw new IllegalStateException("confirmation continuation missing tool identity");
        }
        synchronized (compactionService.lockFor(sessionId)) {
            sessionService.markControlAnswered(
                    sessionId,
                    SessionService.MESSAGE_TYPE_CONFIRMATION,
                    confirmationId,
                    decision == Decision.APPROVED ? "approved" : "denied",
                    decision.name().toLowerCase(),
                    "card");
            if (pendingConfirmationRegistry != null) {
                pendingConfirmationRegistry.complete(confirmationId, decision, null);
                pendingConfirmationRegistry.removeIfPresent(confirmationId);
            }
            SessionEntity session = sessionService.getSession(sessionId);
            AgentEntity agentEntity = agentService.getAgent(session.getAgentId());
            AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
            Message toolResult = agentLoopEngine.completeConfirmedTool(
                    agentDef,
                    sessionId,
                    userId,
                    toolUseId,
                    toolName,
                    toolInput,
                    confirmationKind,
                    installTool,
                    installTarget,
                    decision);
            // OBS-2 M1 §D.4: resumeTraceId 在持久化前生成。
            String resumeTraceId = UUID.randomUUID().toString();

            // OBS-4 §2.1 §6.2: confirmation 答复是 user message 内的续接，不是新边界。
            // 同 answerAsk：非 null 继承；null 则 defensive 自己当 root 并回填。
            String existingActiveRoot = sessionService.getActiveRootTraceId(sessionId);
            String resumeRootTraceId;
            if (existingActiveRoot == null) {
                resumeRootTraceId = resumeTraceId;
                sessionService.setActiveRootTraceId(sessionId, resumeRootTraceId);
            } else {
                resumeRootTraceId = existingActiveRoot;
            }

            sessionService.appendNormalMessages(sessionId, List.of(toolResult), resumeTraceId);
            session.setRuntimeStatus("running");
            session.setRuntimeStep("Resuming");
            session.setRuntimeError(null);
            sessionService.saveSession(session);
            List<Message> history = sessionService.getContextMessages(sessionId);
            if (broadcaster != null) {
                broadcaster.messageAppended(sessionId, resumeTraceId, toolResult);
                broadcaster.sessionStatus(sessionId, "running", "Resuming", null);
            }
            final String capturedRootTraceId = resumeRootTraceId;
            chatLoopExecutor.execute(() -> runLoop(sessionId, null, userId, agentEntity, history, resumeTraceId, capturedRootTraceId));
        }
    }

    private void persistPendingControl(String sessionId, InteractiveControlRequest control) {
        if (control == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("controlId", control.getControlId());
        metadata.put("interactionKind", control.getInteractionKind());
        metadata.put("toolUseId", control.getToolUseId());
        metadata.put("toolName", control.getToolName());
        metadata.put("question", control.getQuestion());
        metadata.put("context", control.getContext());
        metadata.put("options", control.getOptions());
        metadata.put("allowOther", control.isAllowOther());
        metadata.put("state", "pending");
        metadata.put("answer", null);
        metadata.put("answerMode", null);
        metadata.put("assistantToolUseMessage", control.getAssistantToolUseMessage());
        metadata.putAll(control.getExtra());

        Message card = Message.assistant(control.getQuestion() != null ? control.getQuestion() : "");
        String messageType = "confirmation".equals(control.getInteractionKind())
                ? SessionService.MESSAGE_TYPE_CONFIRMATION
                : SessionService.MESSAGE_TYPE_ASK_USER;
        sessionService.appendInteractiveControlMessage(
                sessionId,
                messageType,
                control.getControlId(),
                card,
                metadata);
        if (broadcaster != null && SessionService.MESSAGE_TYPE_ASK_USER.equals(messageType)) {
            ChatEventBroadcaster.AskUserEvent event = new ChatEventBroadcaster.AskUserEvent();
            event.askId = control.getControlId();
            event.question = control.getQuestion();
            event.context = control.getContext();
            event.allowOther = control.isAllowOther();
            event.options = control.getOptions().stream()
                    .map(opt -> new ChatEventBroadcaster.AskUserEvent.Option(
                            opt.get("label") != null ? opt.get("label").toString() : "",
                            opt.get("description") != null ? opt.get("description").toString() : null))
                    .toList();
            broadcaster.askUser(sessionId, event);
        }
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k != null) {
                    out.put(k.toString(), v);
                }
            });
            return out;
        }
        return Map.of();
    }

    private Map<String, Object> readMetadata(SessionMessageEntity entity) {
        if (entity.getMetadataJson() == null || entity.getMetadataJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(entity.getMetadataJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("invalid control metadata", e);
        }
    }
}
