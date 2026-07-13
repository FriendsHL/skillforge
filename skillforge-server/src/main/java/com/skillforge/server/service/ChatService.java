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
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.RootSessionLookup;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.reminder.ReminderBuilder;
import com.skillforge.core.reminder.ReminderContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceStore.TraceFinalizeRequest;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.exception.MultimodalNoVisionException;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.memory.SessionDigestExtractor;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import com.skillforge.server.subagent.CollabRunService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;


import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    /**
     * Q2 (cache-friendly migration, 2026-05-10): builds the {@code <system-reminder>} block
     * inserted as the first ContentBlock on every user Message that has anything to remind.
     * Persisting reminder text on the user message keeps the request history byte-identical
     * across turns so Anthropic prompt-cache breakpoints (BP2 tools, BP3 history prefix) hit
     * instead of every turn invalidating the cache via system-prompt churn. Nullable so test
     * setups that don't care about reminders can pass {@code null}.
     */
    private final ReminderBuilder reminderBuilder;
    private final ChatAttachmentService chatAttachmentService;
    /**
     * MULTIMODAL-MVP Task #4: per-provider vision allowlist. Null in test setups
     * disables the capability check (Phase 1 test compatibility). Wired by
     * Spring with {@link LlmProperties} bean in production.
     */
    private final LlmProperties llmProperties;
    private final ArtifactWorkspaceService artifactWorkspaceService;

    /**
     * P12: publishes {@link SessionLoopFinishedEvent} in the loop teardown finally
     * block so external consumers (e.g. {@code ScheduledTaskExecutor}) can react to
     * terminal session states without a hard ChatService dependency.
     */
    private final ApplicationEventPublisher applicationEventPublisher;

    @Autowired
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
                       LlmTraceStore traceStore,
                       ApplicationEventPublisher applicationEventPublisher,
                       ReminderBuilder reminderBuilder,
                       ChatAttachmentService chatAttachmentService,
                       LlmProperties llmProperties,
                       ArtifactWorkspaceService artifactWorkspaceService) {
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
        this.applicationEventPublisher = applicationEventPublisher;
        this.reminderBuilder = reminderBuilder;
        this.chatAttachmentService = chatAttachmentService;
        this.llmProperties = llmProperties;
        this.artifactWorkspaceService = artifactWorkspaceService;
    }

    /**
     * <b>TEST ONLY.</b> Production must use the 24-arg constructor (Spring-injected).
     * Wires {@code chatAttachmentService} and {@code llmProperties} to null so legacy
     * non-multimodal tests don't have to mock either dependency. r2 W7: if a multimodal
     * turn is ever exercised through this path, {@code runLoop} throws
     * {@link IllegalStateException} rather than silently skipping the vision check.
     */
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
                       LlmTraceStore traceStore,
                       ApplicationEventPublisher applicationEventPublisher,
                       ReminderBuilder reminderBuilder) {
        this(agentService, sessionService, skillRegistry, agentLoopEngine, modelUsageRepository,
                broadcaster, chatLoopExecutor, sessionTitleService, subAgentRegistry,
                cancellationRegistry, compactionService, collabRunRepository, collabRunService,
                objectMapper, sessionDigestExtractor, lifecycleHookDispatcher, sessionConfirmCache,
                pendingConfirmationRegistry, rootSessionLookup, traceStore, applicationEventPublisher,
                reminderBuilder, null, null, null);
    }

    /**
     * <b>TEST ONLY.</b> Production must use the 24-arg constructor (Spring-injected).
     * Used by multimodal-MVP tests that mock {@code chatAttachmentService} but don't
     * exercise the vision capability check — passes {@code null} for {@link LlmProperties}.
     * r2 W7: if a multimodal turn reaches the runLoop through this path,
     * {@code IllegalStateException} fires rather than silent vision-check skip.
     */
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
                       LlmTraceStore traceStore,
                       ApplicationEventPublisher applicationEventPublisher,
                       ReminderBuilder reminderBuilder,
                       ChatAttachmentService chatAttachmentService) {
        this(agentService, sessionService, skillRegistry, agentLoopEngine, modelUsageRepository,
                broadcaster, chatLoopExecutor, sessionTitleService, subAgentRegistry,
                cancellationRegistry, compactionService, collabRunRepository, collabRunService,
                objectMapper, sessionDigestExtractor, lifecycleHookDispatcher, sessionConfirmCache,
                pendingConfirmationRegistry, rootSessionLookup, traceStore, applicationEventPublisher,
                reminderBuilder, chatAttachmentService, null, null);
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
        chatAsync(sessionId, userMessage, userId, List.of(), false);
    }

    public void chatAsync(String sessionId, String userMessage, Long userId, List<String> attachmentIds) {
        chatAsync(sessionId, userMessage, userId, attachmentIds, false);
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
        chatAsync(sessionId, userMessage, userId, List.of(), preserveActiveRoot);
    }

    public void chatAsync(String sessionId, String userMessage, Long userId,
                          List<String> attachmentIds, boolean preserveActiveRoot) {
        List<String> normalizedAttachmentIds = attachmentIds != null ? attachmentIds : List.of();
        String normalizedUserMessage = userMessage != null ? userMessage : "";
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
                                normalizedUserMessage,
                                "direct_input"));
            }

            // If session is already running, enqueue the message instead of starting a new loop
            if ("running".equals(session.getRuntimeStatus())) {
                if (!normalizedAttachmentIds.isEmpty()) {
                    throw new IllegalStateException("Attachments cannot be queued while the session is running");
                }
                LoopContext ctx = cancellationRegistry.getContext(sessionId);
                if (ctx != null) {
                    ctx.enqueueUserMessage(normalizedUserMessage);
                    try {
                        sessionService.appendNormalMessages(sessionId, List.of(Message.user(normalizedUserMessage)));
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
                        broadcaster.messageAppended(sessionId, null, Message.user(normalizedUserMessage));
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
            // Q2 (cache-friendly migration, 2026-05-10): build the <system-reminder> block
            // BEFORE materialising the user message so we can prepend it as the first
            // ContentBlock when there is something to remind. Persisting the block on the
            // message itself keeps history byte-identical across turns (BP2/BP3 cache hits).
            Message userMsg = buildUserMessageWithReminder(
                    sessionId, userId, normalizedUserMessage, history, agentEntity);
            userMsg = withAttachmentRefs(sessionId, userId, userMsg, normalizedAttachmentIds);
            long userSeqNo = sessionService.appendNormalMessages(sessionId, List.of(userMsg), traceId);
            if (chatAttachmentService != null && !normalizedAttachmentIds.isEmpty()) {
                chatAttachmentService.bindToMessage(sessionId, userId, normalizedAttachmentIds, userSeqNo);
            }

            // 2.1 第一条 user message 时立即生成截断标题(同步,极快)
            // 同时触发 SessionStart lifecycle hook（仅在首条消息时，非每轮）
            if (fullHistory.isEmpty()) {
                sessionTitleService.applyImmediateTitle(sessionId,
                        !normalizedUserMessage.isBlank() ? normalizedUserMessage : "Attachment");
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
            // Q2 reminder fix (2026-05-10): capture the constructed userMsg (may have
            // <system-reminder> ContentBlock prepended) so the engine receives the same
            // Message object it persisted to DB. Without this the engine would rebuild
            // Message.user(userMessage) from the raw string and drop the reminder.
            final Message userMsgWithReminder = userMsg;
            chatLoopExecutor.execute(() -> runLoop(sessionId, normalizedUserMessage, userMsgWithReminder, userId,
                    agentEntity, historyForLoop, capturedTraceId, capturedRootTraceId));
        }
    }

    /**
     * MULTIMODAL-MVP: returns true when {@code message.content} is a block list
     * containing any block of type {@code image_ref} or {@code pdf_ref}.
     * Used by {@link #runLoop} to trigger the defense-in-depth vision capability
     * check against the resolved effective model.
     *
     * <p>Block objects can be either {@link ContentBlock} instances (in-memory) or
     * {@link Map} (after Jackson deserialization of persisted messages). Both forms
     * are handled.</p>
     *
     * <p>String-content messages have no blocks → returns false. tool_result blocks
     * carrying nested image content are intentionally NOT counted here — only the
     * current user-message-level reference blocks gate the vision capability check.</p>
     *
     * <p>r2 (N2 fix): the materialized {@code image} type is NOT included.
     * Post-B2-fix, the engine's messages list is guaranteed to be in
     * {@code image_ref} / {@code pdf_ref} shape only — materialized {@code image}
     * blocks live exclusively inside the transient request copy built by
     * {@code MessageMaterializer.expandForProvider} and never appear here.</p>
     */
    static boolean messageHasMultimodalBlocks(Message message) {
        if (message == null || !(message.getContent() instanceof List<?> blocks)) {
            return false;
        }
        for (Object block : blocks) {
            String type = null;
            if (block instanceof ContentBlock cb) {
                type = cb.getType();
            } else if (block instanceof Map<?, ?> map && map.get("type") != null) {
                type = map.get("type").toString();
            }
            if ("image_ref".equals(type) || "pdf_ref".equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the per-session tool allowlist from the agent's {@code tool_ids}.
     *
     * <p>Returns {@code null} when no allowlist is configured (empty/absent → all
     * registered tools allowed, unchanged behavior). When an allowlist IS set,
     * members of a collab run additionally get {@code TeamSend} + {@code TeamList}
     * auto-granted, so subagents can always message each other (kin-mesh) and
     * discover teammates regardless of the agent's allowlist. Both team tools are
     * no-ops outside a collab run and carry their own kin-adjacency / leader-only
     * guards, so the grant is safe. Name-consistent with the hardcoded tool names
     * used elsewhere in this method (e.g. the depth-aware exclude set).
     */
    static Set<String> resolveAllowedToolNames(List<String> toolIds, String collabRunId) {
        if (toolIds == null || toolIds.isEmpty()) {
            return null;
        }
        Set<String> allowed = new HashSet<>(toolIds);
        if (collabRunId != null) {
            allowed.add("TeamSend");
            allowed.add("TeamList");
        }
        return allowed;
    }

    private Message withAttachmentRefs(String sessionId, Long userId, Message userMsg, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return userMsg;
        }
        List<ContentBlock> refs = chatAttachmentService.referenceBlocks(sessionId, userId, attachmentIds);
        List<Object> blocks = new ArrayList<>();
        Object content = userMsg.getContent();
        if (content instanceof List<?> existing) {
            blocks.addAll(existing);
        } else {
            String text = content instanceof String ? (String) content : userMsg.getTextContent();
            if (text != null && !text.isBlank()) {
                blocks.add(ContentBlock.text(text));
            }
        }
        blocks.addAll(refs);
        Message out = new Message();
        out.setRole(userMsg.getRole());
        out.setContent(blocks);
        out.setReasoningContent(userMsg.getReasoningContent());
        return out;
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
        runLoop(sessionId, userMessage, null, userId, agentEntity, history, null, null);
    }

    /**
     * 6-arg overload 委托给 7-arg 版本（externalRootTraceId=null → engine 调用 store 时
     * 由 SQL COALESCE 兜底为 traceId 自身，自己当 root，跟历史 backfill 行为一致）。
     */
    private void runLoop(String sessionId, String userMessage, Long userId,
                         AgentEntity agentEntity, List<Message> history,
                         String externalTraceId) {
        runLoop(sessionId, userMessage, null, userId, agentEntity, history, externalTraceId, null);
    }

    /** Legacy 7-arg overload (no userMsgWithReminder) — delegates with null block so
     *  engine builds plain {@code Message.user(userMessage)} (callers that didn't go
     *  through Q2 buildUserMessageWithReminder, e.g. answerAsk / answerConfirmation). */
    private void runLoop(String sessionId, String userMessage, Long userId,
                         AgentEntity agentEntity, List<Message> history,
                         String externalTraceId, String externalRootTraceId) {
        runLoop(sessionId, userMessage, null, userId, agentEntity, history,
                externalTraceId, externalRootTraceId);
    }

    /**
     * 实际的 loop 执行(在 chatLoopExecutor 线程里跑)。
     * <p>OBS-2 M1 §D.2: 加 externalTraceId 参数 — 由 chatAsync / answerAsk / answerConfirmation
     * 在 submit 前生成 UUID 并透传，让 user message 广播 + engine 内部 trace 共享同一 traceId。
     * null 时内部生成 fallback（兜底，正常路径不应触发）。
     * <p>OBS-4 §2.2: 加 externalRootTraceId 参数 — 由 chatAsync / answerAsk / answerConfirmation
     * 决策后透传到 engine（engine 写入 t_llm_trace.root_trace_id）。null 时存储层 SQL
     * COALESCE 兜底为 traceId 自身（自己当 root）。
     * <p>Q2 reminder fix (2026-05-10): {@code userMsgWithReminder} 是 ChatService.chatAsync
     * 通过 {@code buildUserMessageWithReminder()} 构造的 Message（可能含 reminder ContentBlock）。
     * 透传给 engine 让其追加同一对象到 in-memory message list，避免引擎 rebuild
     * {@code Message.user(userMessage)} 丢失 reminder。null 时 engine 走 legacy String 路径。
     */
    private void runLoop(String sessionId, String userMessage,
                         Message userMsgWithReminder, Long userId,
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
        Path artifactWorkspace = null;
        try {
            // 解析 agent definition,并把 session 的 executionMode 注入 config
            AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
            SessionEntity freshSession = sessionService.getSession(sessionId);
            if (artifactWorkspaceService != null) {
                artifactWorkspace = artifactWorkspaceService.create(userId, sessionId, traceId);
                String existingPrompt = agentDef.getSystemPrompt() != null ? agentDef.getSystemPrompt() : "";
                agentDef.setSystemPrompt(existingPrompt + "\n\n"
                        + artifactWorkspaceService.promptInstruction(artifactWorkspace));
            }
            // MULTIMODAL-MVP redesign (2026-05-14): the agent has a single
            // `modelId` only. Effective model picks /model runtime override when
            // set, otherwise agent.modelId. No more per-turn effective-model
            // switching based on multimodal blocks — if the user wants vision,
            // they pick a vision-capable model as the agent's main model
            // (FE picker tags vision-capable options with a "多模态" chip).
            String runtimeOverride = freshSession.getRuntimeModelOverride();
            if (runtimeOverride != null && !runtimeOverride.isBlank()) {
                // P10 INV-4: /model runtime override takes precedence over agent.modelId.
                agentDef.setModelId(runtimeOverride);
            }

            // SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18): per-session skill
            // override (stamped by SubAgentTool.handleDispatch when the parent
            // passes skillIdsOverride; see V92 + SessionEntity.skillOverridesJson).
            // NULL = legacy semantics (use agent.skillIds); "[]" = explicit no-
            // skill baseline (without_skill case in eval); non-empty list = use
            // those names. Same JSON shape as t_agent.skill_ids — see
            // AgentService.toAgentDefinition line ~293-302 for the parent
            // pattern this mirrors. Iron Law audit: column lives on t_session
            // not t_session_message, so java.md footgun #4 (persistence-shape)
            // / #5 (identity-on-rewrite) DO NOT apply.
            String skillOverridesJson = freshSession.getSkillOverridesJson();
            if (skillOverridesJson != null && !skillOverridesJson.isBlank()) {
                try {
                    List<String> overrideNames = objectMapper.readValue(
                            skillOverridesJson, new TypeReference<List<String>>() {});
                    agentDef.setSkillIds(overrideNames);
                } catch (JsonProcessingException e) {
                    log.warn("Session {} has malformed skill_overrides_json — falling back to agent.skillIds: {}",
                            sessionId, e.getMessage());
                }
            }

            // MULTIMODAL-MVP defense-in-depth: when this turn carries multimodal
            // blocks, refuse if the resolved effective model is not in any provider's
            // visionModels allowlist. The FE upload-button gate + BE upload endpoint
            // gate (`requireVisionCapableModel`) already block the common path, but
            // this check guards against race conditions (agent.modelId changed
            // between upload and send) and replayed / stale-FE requests. Throwing
            // here lets the existing catch (Exception) block on line ~847 surface
            // it as runtimeError + WS sessionStatus("error") — the FE maps the
            // wire code MULTIMODAL_MODEL_NO_VISION_CAPABILITY to a "switch model" hint.
            //
            // r2 (W7 fix): in production, `llmProperties` MUST be wired (the 24-arg
            // constructor is the Spring-injected path). The 22/23-arg constructors
            // pass null for test compat only — fail loud if a multimodal turn ever
            // reaches the null-llmProperties code path.
            boolean hasMultimodalBlocks = userMsgWithReminder != null
                    && messageHasMultimodalBlocks(userMsgWithReminder);
            if (hasMultimodalBlocks) {
                if (llmProperties == null) {
                    throw new IllegalStateException(
                            "LlmProperties not wired — cannot validate vision capability for multimodal turn. "
                                    + "Production code path must use the 24-arg ChatService constructor.");
                }
                if (!llmProperties.supportsVision(agentDef.getModelId())) {
                    throw new MultimodalNoVisionException(agentDef.getModelId());
                }
            }
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
                            + "- Do NOT use Bash, Read, Grep, or other tools yourself — delegate to team members\n"
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
            if (artifactWorkspace != null) {
                preCtx.setArtifactOutputDirectory(artifactWorkspace.toString());
            }
            // OBS-2 M1 §D.1: 透传 traceId 到 engine，让 rootSpan id == traceId 形成单一锚点。
            preCtx.setTraceId(traceId);
            // OBS-4 §2.2: 透传 rootTraceId 到 engine，让 t_llm_trace.root_trace_id 写入对应 root。
            // null 时存储层 SQL 用 COALESCE 兜底为 trace_id 自身（自己当 root）。
            preCtx.setRootTraceId(externalRootTraceId);
            // MULTIMODAL-MVP r2 (B2 fix): wire the engine-boundary materializer so
            // `image_ref` / `pdf_ref` blocks in the persisted user message expand to
            // provider-bound `image` / text blocks ONLY for the LLM request. The engine's
            // messages list and the DB row keep the reference form — preventing
            // mid-prefix divergence guard rewrites that would persist base64 image bytes
            // into t_session_message.content_json (PRD §"Attachment 存储" / persistence-shape-invariant.md).
            if (chatAttachmentService != null) {
                preCtx.setMessageMaterializer(chatAttachmentService);
            }

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

            // Apply allowedToolNames from agent config (tool_ids). Collab members also get
            // TeamSend/TeamList auto-granted (see resolveAllowedToolNames) so subagents can
            // always message + discover each other regardless of the agent's allowlist.
            Object toolIdsObj = agentDef.getConfig().get("tool_ids");
            if (toolIdsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> toolIdList = (List<String>) toolIdsObj;
                Set<String> allowedTools = resolveAllowedToolNames(toolIdList, collabRunId);
                if (allowedTools != null) {
                    preCtx.setAllowedToolNames(allowedTools);
                    log.info("Tool filtering: allowing {} tools for session={}{}", allowedTools.size(), sessionId,
                            collabRunId != null ? " (+TeamSend/TeamList for collab member)" : "");
                }
            }

            // P11 MCP-CLIENT INV-4: per-agent enable filter for MCP-sourced tools.
            // Always set (incl. empty set) so the engine treats absence of an MCP entry
            // as "no MCP tools allowed" rather than the legacy "all tools allowed"
            // semantic of allowedToolNames=null. Default agent.mcp_server_ids="" → empty set.
            String mcpServerIdsCsv = agentEntity.getMcpServerIds();
            Set<String> allowedMcpServers = new HashSet<>(
                    com.skillforge.server.mcp.service.McpServerService.parseServerIds(mcpServerIdsCsv));
            preCtx.setAllowedMcpServerNames(allowedMcpServers);
            if (!allowedMcpServers.isEmpty()) {
                log.info("MCP filter: agent allows servers={} for session={}",
                        allowedMcpServers, sessionId);
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
            // Q2 reminder fix: pass userMsgWithReminder through new 7-arg engine.run
            // overload. null → engine builds plain Message.user(userMessage) (legacy
            // path used by answerAsk / answerConfirmation that don't run reminder build).
            //
            // MULTIMODAL-MVP r2 (B2 fix): hand the engine the IMAGE_REF (persisted) form,
            // NOT the materialized base64 `image` form. The engine's messages list must
            // mirror the DB row shape so updateSessionMessages' commonPrefixSize byte-
            // comparison doesn't trigger the mid-prefix divergence guard and rewrite the
            // session with base64. Materialization happens engine-side via
            // LoopContext.messageMaterializer right before each chatStream call (see
            // AgentLoopEngine.applyMaterializer) — purely transient, never escapes the
            // request boundary.
            LoopResult result = agentLoopEngine.run(agentDef, userMessage, userMsgWithReminder,
                    history, sessionId, userId, preCtx);
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

            List<Message> deferredArtifactMessages = result.getDeferredBroadcastMessages();
            if (deferredArtifactMessages != null && !deferredArtifactMessages.isEmpty()) {
                if (broadcaster != null) {
                    for (Message message : deferredArtifactMessages) {
                        try {
                            broadcaster.messageAppended(sessionId, traceId, message);
                        } catch (RuntimeException e) {
                            log.warn("Deferred artifact broadcast failed after persistence: sessionId={}", sessionId, e);
                        }
                    }
                }
                if (chatAttachmentService != null) {
                    try {
                        chatAttachmentService.markPublishedFromMessages(deferredArtifactMessages);
                    } catch (RuntimeException e) {
                        log.warn("Deferred artifact status repair failed: sessionId={}", sessionId, e);
                    }
                }
                if (artifactWorkspaceService != null && artifactWorkspace != null) {
                    try {
                        artifactWorkspaceService.deleteWorkspace(artifactWorkspace);
                        artifactWorkspace = null;
                    } catch (RuntimeException e) {
                        log.warn("Published artifact workspace cleanup deferred to TTL: sessionId={}", sessionId, e);
                    }
                }
            }

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
                // SubAgent terminate guard: 父显式 'terminate' 子 session 时 handleTerminate
                // 把 child.runtime_status 设为 "terminated"。loop teardown 不能 downgrade 它
                // 回 "idle"。与 SubAgentRegistry.onSessionLoopFinished 里 TERMINATED 的
                // status guard 对称。仅守 idle 路径；"error"（hook abort / exception）仍按
                // 真实失败反映。Broadcast 仍发 "idle"（前端不引入 "terminated" 枚举），DB 持
                // 久态保留 "terminated" 供 'list' / panel 读取。
                if (!"terminated".equals(s.getRuntimeStatus())) {
                    s.setRuntimeStatus("idle");
                }
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
            // 用户友好错误信息：根据 cause chain 识别常见异常类型映射成 actionable 中文提示，
            // 写入 runtime_error / WS error 推给前端展示。完整 stack trace 仅记日志（line above），
            // 不再回灌前端避免暴露内部结构 + 让用户能直接看懂"该重试 / 调超时 / 检查网络"。
            String safeError = "Agent loop failed";
            String errorDetail = toFriendlyChatError(e);
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

            // P12: publish a generic session-finished event for external consumers
            // (e.g. ScheduledTaskExecutor). Fired even on waiting_user, since a paused
            // scheduled-task session is terminal from the schedule's POV (run.status=paused).
            // Defensive: any listener exception MUST NOT bubble into the loop teardown.
            try {
                applicationEventPublisher.publishEvent(new SessionLoopFinishedEvent(
                        sessionId, finalMessage, finalStatus, userId));
            } catch (Exception evtErr) {
                log.error("SessionLoopFinishedEvent publish failed: sessionId={}", sessionId, evtErr);
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
            ResumeLoopSubmission submission = reserveResumeLoop();
            try {
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
                submission.start(new ResumeLoopRequest(
                        sessionId, userId, agentEntity, history, resumeTraceId, resumeRootTraceId));
            } catch (RuntimeException | Error error) {
                submission.abort(error);
                throw error;
            }
        }
    }

    /**
     * Unified confirmation answer path (ACP-EXTERNAL-AGENT P1c-2, Seam 2).
     *
     * <p>One door, discriminated internally by whether a persisted CONTROL row
     * exists for {@code (sessionId, CONFIRMATION, confirmationId)}:
     * <ul>
     *   <li><b>control row present → ENGINE path</b> (install-confirm / ask_user):
     *       unchanged behavior — markControlAnswered + registry.complete +
     *       {@code completeConfirmedTool} (executes the tool) + resume the engine
     *       loop.</li>
     *   <li><b>no control row → ACP/cc path</b>: the ACP run sub-session is a RECORD
     *       (not engine-driven). Verify the registry has a pending confirmation for
     *       {@code confirmationId} AND it is bound to this {@code sessionId}
     *       (P1b Gate-2 binding), then {@code registry.complete} only — the
     *       {@code AcpPermissionBridge} wait-thread wakes and responds to cc. NO
     *       {@code completeConfirmedTool} / engine resume.</li>
     *   <li><b>neither → unknown confirmation</b>: throw
     *       {@link IllegalArgumentException} (the controller maps it to 404/410).</li>
     * </ul>
     *
     * <p>The session-ownership gate ({@code requireOwnedSession}) is enforced by the
     * caller (ChatController) for BOTH paths before this method runs — no cross-user
     * approval regression (P1b BLOCKER stays closed). This method additionally
     * enforces the per-confirmation session-binding gate for the ACP path.
     */
    public void answerConfirmation(String sessionId, String confirmationId, Decision decision, Long userId) {
        java.util.Optional<SessionMessageEntity> controlOpt = sessionService.findControlMessage(
                sessionId, SessionService.MESSAGE_TYPE_CONFIRMATION, confirmationId);
        if (controlOpt.isEmpty()) {
            // No persisted control row → ACP/cc confirmation (or genuinely unknown).
            answerAcpConfirmation(sessionId, confirmationId, decision, userId);
            return;
        }
        SessionMessageEntity control = controlOpt.get();
        log.info("Confirmation answered via ENGINE path: userId={} sessionId={} confirmationId={} decision={}",
                userId, sessionId, confirmationId, decision);
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
            ResumeLoopSubmission submission = reserveResumeLoop();
            try {
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
            // P10 INV-4: respect session-scoped /model override on resume path too.
                String resumeOverride = session.getRuntimeModelOverride();
                if (resumeOverride != null && !resumeOverride.isBlank()) {
                    agentDef.setModelId(resumeOverride);
                }
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
                submission.start(new ResumeLoopRequest(
                        sessionId, userId, agentEntity, history, resumeTraceId, resumeRootTraceId));
            } catch (RuntimeException | Error error) {
                submission.abort(error);
                throw error;
            }
        }
    }

    /** Reserve executor capacity before consuming a one-shot control message. */
    private ResumeLoopSubmission reserveResumeLoop() {
        CompletableFuture<ResumeLoopRequest> prepared = new CompletableFuture<>();
        chatLoopExecutor.execute(() -> {
            ResumeLoopRequest request;
            try {
                request = prepared.join();
            } catch (CompletionException ignored) {
                return;
            }
            runLoop(request.sessionId(), null, request.userId(), request.agentEntity(),
                    request.history(), request.traceId(), request.rootTraceId());
        });
        return new ResumeLoopSubmission(prepared);
    }

    private record ResumeLoopRequest(
            String sessionId,
            Long userId,
            AgentEntity agentEntity,
            List<Message> history,
            String traceId,
            String rootTraceId) {
    }

    private record ResumeLoopSubmission(CompletableFuture<ResumeLoopRequest> prepared) {
        void start(ResumeLoopRequest request) {
            prepared.complete(request);
        }

        void abort(Throwable error) {
            prepared.completeExceptionally(error);
        }
    }

    /**
     * ACP/cc confirmation answer (no persisted control row). Migrated from the
     * now-removed {@code AcpRunController} confirmation endpoint (P1c-2): the run
     * sub-session is a RECORD, so we only verify the per-confirmation binding gate
     * and wake the {@link PendingConfirmationRegistry} latch — the
     * {@code AcpPermissionBridge} wait-thread maps the decision back to cc. No
     * engine resume / {@code completeConfirmedTool}.
     *
     * <p>Gate (BLOCKER-1b binding): the pending confirmation must exist AND be bound
     * to THIS {@code sessionId}. Session ownership is enforced by the caller. A
     * confirmationId that is unknown or bound to a different session →
     * {@link IllegalArgumentException} (mapped to 404/410 by the controller) so a
     * cross-session confirmation cannot be answered.
     */
    private void answerAcpConfirmation(String sessionId, String confirmationId, Decision decision, Long userId) {
        PendingConfirmation pc = pendingConfirmationRegistry != null
                ? pendingConfirmationRegistry.peek(confirmationId)
                : null;
        if (pc == null || !sessionId.equals(pc.sessionId())) {
            // Neither a control row nor a session-bound registry pending → unknown.
            throw new IllegalArgumentException("unknown confirmation");
        }
        // pc != null implies pendingConfirmationRegistry != null (peek above returned non-null),
        // so the unguarded call below cannot NPE.
        boolean woke = pendingConfirmationRegistry.complete(confirmationId, decision, null);
        if (!woke) {
            // Already completed / expired between peek and complete.
            throw new IllegalArgumentException("confirmation has expired or does not exist");
        }
        log.info("Confirmation answered via ACP path: userId={} sessionId={} confirmationId={} decision={}",
                userId, sessionId, confirmationId, decision);
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

    /**
     * Q2 (cache-friendly migration, 2026-05-10): build the user-Message that will be persisted
     * + broadcast + fed into the engine. When the {@link ReminderBuilder} produces a non-empty
     * {@code <system-reminder>} block, the user message becomes a two-block ContentBlock list:
     * <pre>
     * [ {type:"text", text:"&lt;system-reminder&gt;…&lt;/system-reminder&gt;\n"},
     *   {type:"text", text:"&lt;raw user input&gt;"} ]
     * </pre>
     * Otherwise it stays as a plain String content (legacy/back-compat shape; smaller payload).
     *
     * <p>Errors inside the builder are swallowed — reminders MUST NEVER block a user message.
     *
     * <p>The {@code currentTurnIndex} passed to {@link ReminderContext} is {@code history.size()}
     * <em>before</em> the new user message is appended, matching PRD D3 debounce semantics.
     */
    private Message buildUserMessageWithReminder(String sessionId,
                                                 Long userId,
                                                 String userText,
                                                 List<Message> historyBeforeAppend,
                                                 AgentEntity agentEntity) {
        if (reminderBuilder == null) {
            return Message.user(userText);
        }
        String reminderText;
        try {
            AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
            // The context-window denominator MUST match the window the engine gates compaction on,
            // otherwise ContextUsageSource reports a wrong "Context X% used" and the model wraps up
            // early thinking it is tight. Route through the canonical resolver (per-agent
            // context_window_tokens → known-model map → default) — the SAME one
            // CompactionService/AgentLoopEngine use. The legacy agentDef.getMaxContextTokens() read a
            // different key (max_context_tokens) and defaulted to 100K, so the reminder divided by
            // 100K instead of the real window (e.g. 400K) and systematically over-reported usage.
            // getSession throws if absent; the enclosing try/catch then skips the reminder this turn.
            int contextWindowTokens = compactionService.resolveContextWindowForSession(
                    sessionService.getSession(sessionId));
            int requestMaxTokens = agentDef.getMaxTokens();
            String systemPrompt = agentDef.getSystemPrompt() != null ? agentDef.getSystemPrompt() : "";
            // Q2 approximation: ChatService cannot easily reconstruct the full engine-built
            // request envelope (skill defs / behavior rules / context providers / tools list).
            // Pass the raw agent systemPrompt + empty tool list. ContextUsageSource still
            // estimates current ratio over messages + raw systemPrompt + maxTokens reservation
            // — close enough to gate the 70% reminder near the same point engine compaction
            // would, accepting a small under-estimate as the price of cache friendliness.
            ReminderContext ctx = new ReminderContext(
                    sessionId,
                    userId,
                    historyBeforeAppend != null ? historyBeforeAppend.size() : 0,
                    historyBeforeAppend,
                    contextWindowTokens,
                    systemPrompt,
                    java.util.Collections.emptyList(),
                    requestMaxTokens,
                    objectMapper,
                    null /* per-provider thresholds resolved here would require the provider;
                             null → DEFAULTS in the context constructor */,
                    reminderBuilder);
            reminderText = reminderBuilder.build(ctx);
        } catch (Exception e) {
            log.warn("ReminderBuilder failed in ChatService for session={}: {}",
                    sessionId, e.toString());
            reminderText = "";
        }
        if (reminderText == null || reminderText.isEmpty()) {
            return Message.user(userText);
        }
        // Q2 wire shape: array content with reminder block first, raw user text second. FE
        // filters by exact `<system-reminder>` prefix on the first text block.
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(
                ContentBlock.text(reminderText),
                ContentBlock.text(userText)));
        return msg;
    }

    /**
     * Map a chat-loop exception to a user-facing actionable hint (Chinese).
     * Walks the cause chain to identify common HTTP / network failure modes from
     * okhttp / SSE streaming and gives users a clear next step. Falls back to
     * the top-level message (no stack) for unrecognized types. Full stack stays
     * in {@code log.error} above this caller.
     */
    private static String toFriendlyChatError(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof MultimodalNoVisionException mnv) {
                // MULTIMODAL-MVP redesign (2026-05-14): the agent now has a single
                // modelId; vision-capable status drives the FE upload gate and the
                // BE upload-endpoint gate. This exception is the runtime
                // defense-in-depth (race: agent model swapped between upload and
                // send). FE detects the stable wire CODE and prompts user to
                // switch the agent's main model to a vision-capable one.
                return MultimodalNoVisionException.CODE + ": 当前 agent 的主模型 `"
                        + mnv.getModelId() + "` 不支持图像 / PDF 输入。"
                        + "请把 agent 配置中的模型切换为多模态模型（picker 上带 \"多模态\" 标签的项），"
                        + "或在 application.yml 的 provider.vision-models 中加入此模型 id。";
            }
            if (c instanceof java.net.SocketTimeoutException) {
                return "模型响应超时：流式响应中长时间未收到新 chunk。"
                        + "推理模型深度思考时常见，可重试，或在 application.yml 调高对应 provider 的 read-timeout-seconds。";
            }
            if (c instanceof java.net.UnknownHostException) {
                return "无法解析 LLM 提供方域名（" + safeMsgOrType(c) + "）。"
                        + "检查 application.yml 里该 provider 的 base-url 拼写、DNS、或代理设置。";
            }
            if (c instanceof java.net.ConnectException) {
                return "连接 LLM 提供方失败（" + safeMsgOrType(c) + "）。"
                        + "检查网络 / API key / base-url 是否正确，或 provider 是否短暂不可用。";
            }
            if (c instanceof javax.net.ssl.SSLHandshakeException) {
                return "TLS 握手失败（" + safeMsgOrType(c) + "）。"
                        + "可能是证书过期、代理拦截、或 base-url 协议错误（http / https 混用）。";
            }
            // okhttp 取消（用户主动 stop / cancellation）保持原 message
            if (c instanceof java.io.InterruptedIOException) {
                return "请求被中断（可能是用户取消或服务关闭）。可重新发送 message。";
            }
        }
        // 未识别类型：保留 top-level message（不带 stack）让开发者也有线索
        String topMsg = e.getMessage();
        if (topMsg == null || topMsg.isBlank()) topMsg = e.getClass().getSimpleName();

        // 识别常见 LLM provider HTTP 错误 — 给出可操作 hint（不修底层异常类型，
        // OkHttp / okhttp-based providers 抛 RuntimeException with message
        // "<provider> API error: HTTP <code> - <body>" 模式）
        if (topMsg.contains("HTTP 401")
                || topMsg.contains("invalid_api_key")
                || topMsg.contains("invalid access token or token expired")) {
            String provider = extractProviderFromError(topMsg);
            return "LLM 调用未授权（HTTP 401）：" + provider + " 的 API key 失效或未配置。"
                    + "检查对应环境变量（ANTHROPIC_API_KEY / DASHSCOPE_API_KEY / DEEPSEEK_API_KEY / XIAOMI_MIMO_API_KEY）"
                    + "是否有效，或在 agent 配置中切换到一个可用 provider 的模型（dashboard Agents 页可改）。";
        }
        if (topMsg.contains("HTTP 403")) {
            return "LLM 调用被拒绝（HTTP 403）：API key 权限不足、配额耗尽、或 IP 被封。"
                    + "登录 provider 控制台核查余额 / 配额 / 白名单设置。";
        }
        if (topMsg.contains("HTTP 429")) {
            return "LLM 调用被限流（HTTP 429）：触发了 provider 的速率限制，请稍后重试，"
                    + "或降低并发 / 升级 provider 计费档位。";
        }
        if (topMsg.contains("HTTP 5") && (topMsg.contains("HTTP 500") || topMsg.contains("HTTP 502")
                || topMsg.contains("HTTP 503") || topMsg.contains("HTTP 504"))) {
            return "LLM 提供方服务端错误（" + (topMsg.contains("HTTP 504") ? "HTTP 504 网关超时" :
                    topMsg.contains("HTTP 503") ? "HTTP 503 服务不可用" :
                    topMsg.contains("HTTP 502") ? "HTTP 502 网关错误" : "HTTP 500")
                    + "）：provider 短暂不可用，可重试。持续报错请检查 provider 状态页。";
        }
        return "Agent 执行失败：" + topMsg + "。完整堆栈见 server 日志。";
    }

    /**
     * 从错误消息形如 "<provider> API error: HTTP 401 - ..." 提取 provider 名。
     * 5 个 provider 命名：claude / bailian / dashscope / deepseek / xiaomi-mimo。
     */
    private static String extractProviderFromError(String msg) {
        if (msg.contains("bailian")) return "bailian (dashscope)";
        if (msg.contains("dashscope")) return "bailian (dashscope)";
        if (msg.contains("claude") || msg.contains("anthropic")) return "claude (anthropic)";
        if (msg.contains("deepseek")) return "deepseek";
        if (msg.contains("xiaomi") || msg.contains("mimo")) return "xiaomi-mimo";
        return "LLM provider";
    }

    /** Return non-blank getMessage() or fall back to simple class name. */
    private static String safeMsgOrType(Throwable t) {
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.getClass().getSimpleName();
    }
}
