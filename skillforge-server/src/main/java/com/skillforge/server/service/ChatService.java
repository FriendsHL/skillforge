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
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.engine.durability.DurableToolExecutionIncompleteException;
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
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.exception.MultimodalNoVisionException;
import com.skillforge.server.exception.RetryBusyException;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.memory.SessionDigestExtractor;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import com.skillforge.server.runtime.RuntimeFailureClassifier;
import com.skillforge.server.runtime.RuntimeFailureEvidence;
import com.skillforge.server.runtime.RuntimeFailureFact;
import com.skillforge.server.runtime.RuntimeFailureState;
import com.skillforge.server.subagent.CollabRunService;
import com.skillforge.server.subagent.SubAgentRegistry;
import com.skillforge.server.session.SessionDurableCompletionReconciler;
import com.skillforge.server.session.SessionDurableCancellationService;
import com.skillforge.server.session.SessionInteractiveControlTransactionService;
import com.skillforge.server.session.OccurrenceArchivePreparation;
import com.skillforge.server.session.SessionLoopAdmissionService;
import com.skillforge.server.session.SessionLoopLeaseHeartbeat;
import com.skillforge.server.session.SessionQueuedUserInboxService;
import com.skillforge.server.session.DurableSessionRecoveryCoordinator;
import com.skillforge.server.session.DurableRecoveryFailureException;
import com.skillforge.server.session.DurableRecoveryRetryableException;
import com.skillforge.server.session.ResolvedUnknownContinuationRun;
import com.skillforge.server.session.SessionRunCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final RuntimeFailureClassifier RUNTIME_FAILURE_CLASSIFIER =
            new RuntimeFailureClassifier();

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
    private final ConcurrentHashMap<String, AtomicInteger> activeLoopTaskCounts = new ConcurrentHashMap<>();
    /** Covers the executor handoff gap before runLoop increments activeLoopTaskCounts. */
    private final Set<String> durableRecoveryReservations = ConcurrentHashMap.newKeySet();

    /** Optional only for direct-constructor tests; all three beans are present in production. */
    private SessionHistoryProperties sessionHistoryProperties;
    private SessionLoopAdmissionService sessionLoopAdmissionService;
    private SessionDurableCompletionReconciler sessionDurableCompletionReconciler;
    private SessionLoopLeaseHeartbeat sessionLoopLeaseHeartbeat;
    private DurableSessionRecoveryCoordinator durableSessionRecoveryCoordinator;
    private SessionDurableCancellationService sessionDurableCancellationService;
    private SessionInteractiveControlTransactionService interactiveControlTransactions;
    private OccurrenceArchivePreparation occurrenceArchivePreparation;
    private SessionQueuedUserInboxService sessionQueuedUserInboxService;
    private SessionRunCoordinator sessionRunCoordinator;

    @Autowired
    void configureSessionDurability(
            SessionHistoryProperties properties,
            SessionLoopAdmissionService admissionService,
            SessionDurableCompletionReconciler completionReconciler,
            SessionLoopLeaseHeartbeat leaseHeartbeat,
            DurableSessionRecoveryCoordinator recoveryCoordinator) {
        this.sessionHistoryProperties = properties;
        this.sessionLoopAdmissionService = admissionService;
        this.sessionDurableCompletionReconciler = completionReconciler;
        this.sessionLoopLeaseHeartbeat = leaseHeartbeat;
        this.durableSessionRecoveryCoordinator = recoveryCoordinator;
    }

    @Autowired
    void configureDurableCancellation(
            SessionDurableCancellationService cancellationService) {
        this.sessionDurableCancellationService = cancellationService;
    }

    @Autowired
    void configureDurableInteractiveContinuation(
            SessionInteractiveControlTransactionService interactiveTransactions,
            OccurrenceArchivePreparation archivePreparation) {
        this.interactiveControlTransactions = interactiveTransactions;
        this.occurrenceArchivePreparation = archivePreparation;
    }

    @Autowired
    void configureDurableQueuedUserInbox(
            SessionQueuedUserInboxService queuedUserInboxService) {
        this.sessionQueuedUserInboxService = queuedUserInboxService;
    }

    @Autowired
    void configureResolvedUnknownContinuation(SessionRunCoordinator runCoordinator) {
        this.sessionRunCoordinator = runCoordinator;
    }

    /** Re-emits only already committed unknown-result occurrences. */
    public void republishResolvedUnknownResults(List<PersistedBlockOccurrence> results) {
        if (broadcaster == null || results == null) return;
        results.stream()
                .sorted(java.util.Comparator.comparingInt(
                        PersistedBlockOccurrence::resultBatchOrdinal))
                .forEach(block -> {
                    try {
                        broadcaster.messageAppended(
                                block.sessionId(), block.traceId(),
                                Message.toolResult(
                                        block.toolUseId(), block.content(), block.error(),
                                        block.errorType()));
                    } catch (RuntimeException publishFailure) {
                        log.warn("Resolved unknown result broadcast failed: sessionId={}",
                                block.sessionId());
                    }
                });
    }

    /** Starts Provider continuation only after the durable post-action gates have accepted it. */
    public void continueResolvedUnknownAsync(ResolvedUnknownContinuationRun run) {
        if (run == null || sessionRunCoordinator == null) {
            throw new IllegalStateException("Resolved unknown continuation is unavailable");
        }
        startResolvedUnknownContinuation(run);
    }

    private void startResolvedUnknownContinuation(ResolvedUnknownContinuationRun run) {
        synchronized (compactionService.lockFor(run.scope().sessionId())) {
            String sessionId = run.scope().sessionId();
            SessionEntity session = sessionService.getSession(sessionId);
            if (!"running".equals(session.getRuntimeStatus())
                    || !java.util.Objects.equals(session.getUserId(), run.scope().userId())
                    || session.getHistoryEpoch() != run.scope().historyEpoch()
                    || !run.scope().loopId().equals(session.getActiveLoopId())
                    || session.getLoopFence() != run.scope().loopFence()
                    || !run.scope().ownerInstanceId().equals(
                            session.getLoopOwnerInstanceId())
                    || session.getLoopLeaseUntil() == null
                    || !session.getLoopLeaseUntil().equals(run.leaseUntil())) {
                throw new IllegalStateException(
                        "Resolved unknown continuation authority changed");
            }
            if (hasActiveLoopTask(sessionId)
                    || !durableRecoveryReservations.add(sessionId)) {
                throw new RetryBusyException();
            }

            ResumeLoopSubmission submission;
            try {
                submission = reserveResumeLoop();
            } catch (RuntimeException | Error error) {
                durableRecoveryReservations.remove(sessionId);
                throw error;
            }
            try {
                List<Message> history = sessionService.getContextMessages(sessionId);
                if (history.isEmpty()) {
                    throw new IllegalStateException(
                            "Resolved unknown continuation has no persisted boundary");
                }
                AgentEntity agent = agentService.getAgent(session.getAgentId());
                String traceId = UUID.randomUUID().toString();
                String rootTraceId = sessionService.getActiveRootTraceId(sessionId);
                if (rootTraceId == null) {
                    rootTraceId = traceId;
                    sessionService.setActiveRootTraceId(sessionId, rootTraceId);
                }
                if (broadcaster != null) {
                    broadcaster.sessionStatus(
                            sessionId, "running", "Continuing resolved unknown outcome", null);
                }
                DurableSessionRecoveryCoordinator.RecoveryPlan plan =
                        DurableSessionRecoveryCoordinator.RecoveryPlan
                                .resolvedUnknownContinuation(run);
                submission.start(new ResumeLoopRequest(
                        sessionId, run.scope().userId(), agent, history, traceId,
                        rootTraceId, null, null, plan));
            } catch (RuntimeException | Error error) {
                submission.abort(error);
                durableRecoveryReservations.remove(sessionId);
                throw error;
            }
        }
    }

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
        chatAsyncWithRequestId(
                sessionId, userMessage, userId, attachmentIds,
                preserveActiveRoot, UUID.randomUUID());
    }

    /** Dashboard/API entrypoint with a retry-stable ordered-inbox identity. */
    public ChatSubmissionAck submitUserMessage(
            String sessionId,
            String userMessage,
            Long userId,
            List<String> attachmentIds,
            UUID requestId) {
        UUID effectiveRequestId = requestId != null ? requestId : UUID.randomUUID();
        chatAsyncWithRequestId(
                sessionId, userMessage, userId, attachmentIds,
                false, effectiveRequestId);
        return new ChatSubmissionAck(effectiveRequestId, "scheduled");
    }

    private void chatAsyncWithRequestId(
            String sessionId,
            String userMessage,
            Long userId,
            List<String> attachmentIds,
            boolean preserveActiveRoot,
            UUID requestId) {
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

            // Atomic durable completion releases the DB scope before the old loop's
            // post-commit broadcasts and hooks finish. Keep a new local turn out of
            // that teardown window so old idle/cache events cannot race the new run.
            if (!"running".equals(session.getRuntimeStatus())
                    && hasActiveLoopTask(sessionId)) {
                throw new RetryBusyException();
            }

            if ("waiting_user".equals(session.getRuntimeStatus())) {
                if (isDurableConversationEnabled()) {
                    // Direct input cannot silently rewrite a durable control. Supersede must
                    // eventually use the same claimed full-vector resolution protocol.
                    throw new IllegalStateException(
                            "Durable interactive control must be answered explicitly");
                }
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
                if (isDurableConversationEnabled()) {
                    if (!normalizedAttachmentIds.isEmpty()) {
                        throw new IllegalStateException(
                                "Attachments cannot be queued while the session is running");
                    }
                    if (sessionQueuedUserInboxService == null) {
                        throw new IllegalStateException(
                                "Durable queued-user inbox is not configured");
                    }
                    SessionQueuedUserInboxService.AcceptanceAck acceptance =
                            sessionQueuedUserInboxService.acceptOrRoute(
                                    sessionId,
                                    userId,
                                    requestId,
                                    MessageSnapshot.capture(
                                            Message.user(normalizedUserMessage)));
                    if (acceptance.mode()
                            == SessionQueuedUserInboxService.AcceptanceMode
                                    .QUEUED_RECOVERY_REQUIRED) {
                        // Acceptance is already committed. The periodic poller remains the
                        // durable retry owner if an immediate fenced claim cannot be scheduled.
                        try {
                            resumeDurableInterruptedTurnAsync(sessionId);
                        } catch (RuntimeException recoveryDeferred) {
                            log.info("Queued USER accepted; fenced recovery deferred: sessionId={}",
                                    sessionId);
                        }
                        return;
                    }
                    if (acceptance.mode()
                                    == SessionQueuedUserInboxService.AcceptanceMode.QUEUED_LIVE
                            || acceptance.mode()
                                    == SessionQueuedUserInboxService.AcceptanceMode.ALREADY_DRAINED) {
                        return;
                    }
                    // The DB loop closed between the runtime read and inbox acceptance. Re-enter
                    // normal admission below with the same request identity.
                    session = sessionService.getSession(sessionId);
                    if (hasActiveLoopTask(sessionId)) {
                        throw new RetryBusyException();
                    }
                }
                if (!isDurableConversationEnabled() && !normalizedAttachmentIds.isEmpty()) {
                    throw new IllegalStateException("Attachments cannot be queued while the session is running");
                }
                LoopContext ctx = isDurableConversationEnabled()
                        ? null
                        : cancellationRegistry.getContext(sessionId);
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
            final SessionLoopAdmissionService.AdmissionAck durableAdmission;
            final long userSeqNo;
            if (isDurableConversationEnabled()) {
                String loopId = UUID.randomUUID().toString();
                durableAdmission = admitDurableLoop(
                        sessionId,
                        userId,
                        requestId,
                        loopId,
                        MessageSnapshot.capture(userMsg),
                        traceId);
                userSeqNo = durableAdmission.userMessage().seqNo();
            } else {
                durableAdmission = null;
                userSeqNo = sessionService.appendNormalMessages(
                        sessionId, List.of(userMsg), traceId);
            }
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
                        if (durableAdmission != null) {
                            RuntimeFailureFact failure = RUNTIME_FAILURE_CLASSIFIER.hookFailure(
                                    "SESSION_START_HOOK_ABORTED",
                                    "A session start policy stopped the run.");
                            if (!sessionLoopAdmissionService.failIfNoBlockingAttempt(
                                    durableAdmission.scope(), failure)) {
                                throw new IllegalStateException(
                                        "Durable SessionStart failure requires recovery");
                            }
                            session = sessionService.getSession(sessionId);
                        } else {
                            session.setRuntimeStatus("error");
                            RuntimeFailureFact failure = RUNTIME_FAILURE_CLASSIFIER.hookFailure(
                                    "SESSION_START_HOOK_ABORTED",
                                    "A session start policy stopped the run.");
                            RuntimeFailureState.apply(session, failure);
                            session.setCompletedAt(java.time.Instant.now());
                            sessionService.saveSession(session);
                        }
                        if (broadcaster != null) {
                            broadcastFailureStatus(sessionId, session);
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
            if (durableAdmission == null) {
                session.setRuntimeStatus("running");
                RuntimeFailureState.clear(session);
                session.setRuntimeStep("Starting");
                session.setLastUserMessageAt(java.time.Instant.now());
                sessionService.saveSession(session);
            }

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
            try {
                chatLoopExecutor.execute(() -> runLoop(
                        sessionId, normalizedUserMessage, userMsgWithReminder, userId,
                        agentEntity, historyForLoop, capturedTraceId, capturedRootTraceId,
                        durableAdmission));
            } catch (RejectedExecutionException rejected) {
                // The user row is already durable, but the loop never started: zero SSE,
                // zero tools, and therefore a safe retry through retryFailedTurnAsync.
                if (durableAdmission != null) {
                    RuntimeFailureFact queueFailure = fullHistory.isEmpty()
                        ? RUNTIME_FAILURE_CLASSIFIER.harnessFailure(
                                "EXECUTOR_BUSY", "The agent runtime is busy.", "possible")
                        : RUNTIME_FAILURE_CLASSIFIER.retryableHarnessFailure(
                                "EXECUTOR_BUSY", "The agent runtime is busy. Please retry.");
                    if (!sessionLoopAdmissionService.failIfNoBlockingAttempt(
                            durableAdmission.scope(), queueFailure)) {
                        throw new IllegalStateException(
                                "Durable executor rejection requires recovery");
                    }
                }
                SessionEntity rejectedSession = sessionService.getSession(sessionId);
                if (durableAdmission == null) {
                    rejectedSession.setRuntimeStatus("error");
                    rejectedSession.setCompletedAt(java.time.Instant.now());
                    RuntimeFailureFact queueFailure = fullHistory.isEmpty()
                            ? RUNTIME_FAILURE_CLASSIFIER.harnessFailure(
                                    "EXECUTOR_BUSY", "The agent runtime is busy.", "possible")
                            : RUNTIME_FAILURE_CLASSIFIER.retryableHarnessFailure(
                                    "EXECUTOR_BUSY", "The agent runtime is busy. Please retry.");
                    RuntimeFailureState.apply(rejectedSession, queueFailure);
                    sessionService.saveSession(rejectedSession);
                }
                if (broadcaster != null) {
                    try {
                        broadcastFailureStatus(sessionId, rejectedSession);
                        broadcaster.userEvent(rejectedSession.getUserId(),
                                sessionUpdatedPayload(rejectedSession, fullHistory.size() + 1));
                    } catch (RuntimeException broadcastError) {
                        log.warn("Executor rejection status broadcast failed: sessionId={}",
                                sessionId, broadcastError);
                    }
                }
                throw rejected;
            }
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
     * members of a collab run additionally get Team communication and shared Task
     * tools auto-granted, so workers can discover peers and participate in the
     * leader's authoritative task graph regardless of their static allowlist.
     * These tools keep their own context and authorization guards. Name-consistent
     * with the hardcoded tool names
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
            allowed.add("TaskCreate");
            allowed.add("TaskUpdate");
            allowed.add("TaskGet");
            allowed.add("TaskList");
        }
        return allowed;
    }

    static void appendTeamTaskPrompt(AgentDefinition agentDef, CollabRunEntity collabRun, String sessionId) {
        if (agentDef == null || collabRun == null || sessionId == null) return;
        String existing = agentDef.getSystemPrompt() != null ? agentDef.getSystemPrompt() : "";
        agentDef.setSystemPrompt(existing + "\n\n## Team Task Protocol\n\n"
                + "All Team members share the leader Session's persistent Task graph; never invent a graph or owner ID.\n"
                + "- Only the Team leader creates shared Tasks or edits dependencies.\n"
                + "- Call TaskList(availableOnly=true), then TaskGet to read the latest version before claiming.\n"
                + "- Claim with TaskUpdate(taskId, expectedRevision, status=in_progress). Do not send owner.\n"
                + "- Only the active owner completes a Task. The owner or Team leader may release unfinished work with status=pending.\n"
                + "- A revision conflict means another member changed the graph: refresh with TaskGet/List instead of blind retry.\n"
                + "- TaskCreate creates work only; it never starts an Agent. TeamCreate is the explicit Agent-spawn action.\n"
                + "- Do not poll for dependencies. A [TeamTaskEvent] message announces newly available work.\n");
        if (!sessionId.equals(collabRun.getLeaderSessionId())) return;
        agentDef.setSystemPrompt(agentDef.getSystemPrompt() + "\n\n## Team Leader Protocol\n\n"
                + "You are the LEADER of a multi-agent team. Your role is to DELEGATE, not to do the work yourself.\n\n"
                + "**Workflow:**\n"
                + "1. Analyze the request and create a persistent Task for each independently verifiable sub-task\n"
                + "2. Use TeamCreate to spawn members; include the corresponding taskId in each assignment\n"
                + "3. After spawning all members, STOP calling tools and briefly summarize the delegation\n"
                + "4. Wait for [TeamResult]/[TeamTaskEvent] messages — do NOT poll TeamList\n"
                + "5. Once all results arrive, verify Task state and synthesize the final response\n\n"
                + "**Rules:**\n"
                + "- Do NOT use Bash, Read, Grep, or other tools yourself — delegate to team members\n"
                + "- Do NOT do research or investigation yourself — that's what team members are for\n"
                + "- You MAY use TeamSend to send additional context to a running member\n"
                + "- You MAY use TeamKill to cancel a member that is no longer needed\n"
                + "- If a member's result is insufficient, spawn a new member with a refined task\n");
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
        m.put("failureSource", s.getRuntimeFailureSource());
        m.put("failureCode", s.getRuntimeFailureCode());
        m.put("retryable", s.isRuntimeRetryable());
        m.put("sideEffects", s.getRuntimeSideEffects());
        m.put("messageCount", messageCount);
        m.put("title", s.getTitle());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    private void broadcastFailureStatus(String sessionId, SessionEntity session) {
        if (broadcaster == null) {
            return;
        }
        broadcaster.sessionStatus(
                sessionId,
                "error",
                session.getRuntimeStep(),
                session.getRuntimeError(),
                session.getRuntimeFailureSource(),
                session.getRuntimeFailureCode(),
                session.isRuntimeRetryable(),
                session.getRuntimeSideEffects());
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
        runLoop(sessionId, userMessage, userMsgWithReminder, userId, agentEntity, history,
                externalTraceId, externalRootTraceId, null);
    }

    private void runLoop(String sessionId, String userMessage,
                         Message userMsgWithReminder, Long userId,
                         AgentEntity agentEntity, List<Message> history,
                         String externalTraceId, String externalRootTraceId,
                         SessionLoopAdmissionService.AdmissionAck durableAdmission) {
        runLoop(sessionId, userMessage, userMsgWithReminder, userId, agentEntity, history,
                externalTraceId, externalRootTraceId, durableAdmission, null);
    }

    private void runLoop(String sessionId, String userMessage,
                         Message userMsgWithReminder, Long userId,
                         AgentEntity agentEntity, List<Message> history,
                         String externalTraceId, String externalRootTraceId,
                         SessionLoopAdmissionService.AdmissionAck durableAdmission,
                         DurableSessionRecoveryCoordinator.RecoveryPlan recoveryPlan) {
        DurableLoopContinuation continuation = runLoopOnce(
                sessionId, userMessage, userMsgWithReminder, userId, agentEntity, history,
                externalTraceId, externalRootTraceId, durableAdmission, recoveryPlan);
        while (continuation != null) {
            continuation = runLoopOnce(
                    continuation.sessionId(),
                    null,
                    null,
                    continuation.userId(),
                    continuation.agentEntity(),
                    continuation.history(),
                    continuation.traceId(),
                    continuation.rootTraceId(),
                    continuation.authority(),
                    null);
        }
    }

    private DurableLoopContinuation runLoopOnce(
                         String sessionId, String userMessage,
                         Message userMsgWithReminder, Long userId,
                         AgentEntity agentEntity, List<Message> history,
                         String externalTraceId, String externalRootTraceId,
                         SessionLoopAdmissionService.AdmissionAck durableAdmission,
                         DurableSessionRecoveryCoordinator.RecoveryPlan recoveryPlan) {
        // OBS-2 M1 §D.8.2: 显式 startedAt 给 §D.8.3 catch 块 finalize 使用。
        final long startedAt = System.currentTimeMillis();
        String finalMessage = null;
        int toolCallCount = 0;
        String finalStatus = "completed";
        SessionEntity deferredErrorSession = null;
        SessionDurableCancellationService.CancellationAck committedCancellation = null;
        SessionLoopLeaseHeartbeat.Handle durableHeartbeat = null;
        boolean durableTerminalAccepted = !isDurableConversationEnabled();
        final UUID completionBatchId = UUID.randomUUID();
        // OBS-2 M1 §D.1: traceId 在 runLoop 入口必须存在 — 优先使用调用方传入的，否则 fallback 生成。
        final String traceId = externalTraceId != null ? externalTraceId : UUID.randomUUID().toString();
        LoopContext preCtx = null;
        Path artifactWorkspace = null;
        markLoopTaskStarted(sessionId);
        try {
            // 解析 agent definition,并把 session 的 executionMode 注入 config
            AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
            SessionEntity freshSession = sessionService.getSession(sessionId);
            if (artifactWorkspaceService != null) {
                artifactWorkspace = artifactWorkspaceService.create(userId, sessionId, traceId);
                agentDef.getConfig().put(
                        AgentLoopEngine.RUNTIME_SYSTEM_CONTEXT_CONFIG,
                        artifactWorkspaceService.promptInstruction(artifactWorkspace));
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
            Message currentUserTurn = userMsgWithReminder != null
                    ? userMsgWithReminder
                    : findLatestUserTurn(history);
            boolean hasMultimodalBlocks = messageHasMultimodalBlocks(currentUserTurn);
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
                appendTeamTaskPrompt(agentDef, collabRunForPrompt, sessionId);
            }

            // 收集 zip 包 Skill 定义
            List<SkillDefinition> skills = new ArrayList<>();
            for (String skillId : agentDef.getSkillIds()) {
                skillRegistry.getSkillDefinition(skillId).ifPresent(skills::add);
            }

            log.info("Running agent loop (async): sessionId={}, agentId={}, mode={}", sessionId, agentEntity.getId(), mode);
            // 预建 LoopContext 并注册到 CancellationRegistry, 让 /cancel 端点可以找到它
            preCtx = new LoopContext();
            if (isDurableConversationEnabled()) {
                if ((durableAdmission == null) == (recoveryPlan == null)) {
                    throw new IllegalStateException(
                            "Durable loop requires exactly one authority acknowledgement");
                }
                var scope = durableAdmission != null
                        ? durableAdmission.scope()
                        : recoveryPlan.admission().scope();
                var frontier = durableAdmission != null
                        ? durableAdmission.frontier()
                        : recoveryPlan.frontier();
                preCtx.setDurabilityScope(scope);
                preCtx.setExpectedDurableFrontier(frontier);
                if (recoveryPlan != null) {
                    preCtx.setRecoveredToolAttempt(recoveryPlan.recoveredToolAttempt());
                }
                durableHeartbeat = sessionLoopLeaseHeartbeat.start(scope);
            }
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
                if (isDurableConversationEnabled()) {
                    throw new IllegalStateException(
                            "Durable queued messages require the ordered inbox");
                }
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
                Message notifyMsg;
                if (isDurableConversationEnabled()
                        && result.getDeferredBroadcastMessages().size() == 1) {
                    notifyMsg = result.getDeferredBroadcastMessages().get(0);
                    mergeTerminalNotice(notifyMsg, finalMessage);
                } else {
                    notifyMsg = Message.assistant(finalMessage);
                    finalMessages.add(notifyMsg);
                }
                // OBS-2 M1 §A.1 row 5 / §D.3: 静默退出 notify 也归当前 trace。
                if (broadcaster != null && !isDurableConversationEnabled()) {
                    broadcaster.messageAppended(sessionId, preCtx.getTraceId(), notifyMsg);
                } else if (isDurableConversationEnabled()) {
                    if (result.getDeferredBroadcastMessages().isEmpty()) {
                        result.getDeferredBroadcastMessages().add(notifyMsg);
                    }
                }
                log.info("Silent exit notified to user: status={}, sessionId={}", resultStatus, sessionId);
            }

            cancellationRegistry.unregister(sessionId);

            // 保存最终 messages(engine 已经把 user msg + 之后所有消息组装好了)
            // OBS-2 M1 §D.5: 透传 traceId 让 engine 输出（assistant / tool_result）行 trace_id 不为 null。
            if (isDurableConversationEnabled() && waitingUser) {
                if (preCtx.getDurabilityScope() == null
                        || preCtx.getExpectedDurableFrontier() == null
                        || result.getPendingControl() == null) {
                    throw new IllegalStateException(
                            "Durable waiting control is missing database authority");
                }
                durableHeartbeat.assertAuthoritative();
                durableHeartbeat.close();
                durableHeartbeat = null;
                sessionLoopAdmissionService.parkForManualContinuation(
                        preCtx.getDurabilityScope(),
                        com.skillforge.core.engine.durability.DurableToolAttemptState.WAITING_USER,
                        null);
                completeResolvedUnknownTranscript(recoveryPlan);
                durableTerminalAccepted = true;
                result.setDeferredBroadcastMessages(List.of());
            } else if (isDurableConversationEnabled()) {
                if (preCtx.getDurabilityScope() == null
                        || preCtx.getExpectedDurableFrontier() == null) {
                    throw new IllegalStateException(
                            "Durable loop completion is missing database authority");
                }
                MessageSnapshot terminalAssistant = durableTerminalAssistant(result);
                if (terminalAssistant == null) {
                    if (finalMessage == null || finalMessage.isBlank()) {
                        throw new IllegalStateException(
                                "Durable loop has no terminal assistant to reconcile");
                    }
                    Message terminal = Message.assistant(finalMessage);
                    result.setDeferredBroadcastMessages(List.of(terminal));
                    terminalAssistant = MessageSnapshot.capture(terminal);
                }
                durableHeartbeat.assertAuthoritative();
                durableHeartbeat.close();
                durableHeartbeat = null;
                SessionDurableCompletionReconciler.CompletionAck completionAck =
                        reconcileDurableCompletion(
                        preCtx.getDurabilityScope(),
                        preCtx.getExpectedDurableFrontier(),
                        completionBatchId,
                        terminalAssistant,
                        traceId,
                        result.getTotalInputTokens(),
                        result.getTotalOutputTokens());
                completeResolvedUnknownTranscript(recoveryPlan);
                if (completionAck.continuationRequired()) {
                    // The terminal assistant was committed before the queued USER rows, and the
                    // completion transaction retained this exact loop/fence under the Session
                    // lock. Continue on the same authority so input accepted during the final
                    // Provider call cannot be stranded after an idle transition.
                    Message committedIntermediate =
                            completionAck.terminalAssistant().message().toMessage();
                    if (broadcaster != null) {
                        try {
                            broadcaster.messageAppended(
                                    sessionId, traceId, committedIntermediate);
                        } catch (RuntimeException broadcastFailure) {
                            log.warn("Intermediate terminal broadcast failed: sessionId={}",
                                    sessionId);
                        }
                    }
                    if (chatAttachmentService != null) {
                        try {
                            chatAttachmentService.markPublishedFromMessages(
                                    List.of(committedIntermediate));
                        } catch (RuntimeException attachmentFailure) {
                            log.warn("Intermediate attachment publication repair failed: sessionId={}",
                                    sessionId);
                        }
                    }
                    if (artifactWorkspaceService != null && artifactWorkspace != null) {
                        try {
                            artifactWorkspaceService.deleteWorkspace(artifactWorkspace);
                            artifactWorkspace = null;
                        } catch (RuntimeException cleanupFailure) {
                            log.warn("Intermediate artifact workspace cleanup deferred: sessionId={}",
                                    sessionId);
                        }
                    }

                    ModelUsageEntity intermediateUsage = new ModelUsageEntity();
                    intermediateUsage.setUserId(userId);
                    intermediateUsage.setAgentId(agentEntity.getId());
                    intermediateUsage.setSessionId(sessionId);
                    intermediateUsage.setModelId(agentDef.getModelId());
                    intermediateUsage.setInputTokens((int) result.getTotalInputTokens());
                    intermediateUsage.setOutputTokens((int) result.getTotalOutputTokens());
                    try {
                        intermediateUsage.setToolCalls(
                                objectMapper.writeValueAsString(result.getToolCalls()));
                    } catch (JsonProcessingException serializationFailure) {
                        intermediateUsage.setToolCalls("[]");
                    }
                    modelUsageRepository.save(intermediateUsage);

                    SessionLoopAdmissionService.AdmissionAck continuationAuthority =
                            new SessionLoopAdmissionService.AdmissionAck(
                                    preCtx.getDurabilityScope(),
                                    completionAck.terminalAssistant(),
                                    completionAck.postCompletionFrontier(),
                                    completionAck.continuationLeaseUntil());
                    List<Message> continuationHistory =
                            sessionService.getContextMessages(sessionId);
                    durableTerminalAccepted = false;
                    result.setDeferredBroadcastMessages(List.of());
                    return new DurableLoopContinuation(
                            sessionId,
                            userId,
                            agentEntity,
                            continuationHistory,
                            UUID.randomUUID().toString(),
                            externalRootTraceId,
                            continuationAuthority);
                }
                durableTerminalAccepted = true;
                result.setDeferredBroadcastMessages(completionAck.terminalAssistant() == null
                        ? List.of()
                        : List.of(completionAck.terminalAssistant().message().toMessage()));
            } else {
                sessionService.updateSessionMessages(sessionId, finalMessages,
                        result.getTotalInputTokens(), result.getTotalOutputTokens(), traceId);
            }

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
                if (!isDurableConversationEnabled()) {
                    persistPendingControl(sessionId, result.getPendingControl());
                }
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
                if (!isDurableConversationEnabled()) {
                    s.setCompletedAt(java.time.Instant.now());
                    s.setRuntimeStatus("waiting_user");
                    RuntimeFailureState.clear(s);
                    s.setRuntimeStep("waiting_control");
                    clearRecoveryState(s);
                    sessionService.saveSession(s);
                }
                if (broadcaster != null) {
                    broadcaster.sessionStatus(sessionId, "waiting_user", "waiting_control", null);
                    broadcaster.userEvent(s.getUserId(), sessionUpdatedPayload(s, s.getMessageCount()));
                }
                finalStatus = "waiting_user";
                return null;
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
            if (!isDurableConversationEnabled()) {
                s.setCompletedAt(java.time.Instant.now());
                if (wasAbortedByHook) {
                    s.setRuntimeStatus("error");
                    RuntimeFailureState.apply(s, RUNTIME_FAILURE_CLASSIFIER.hookFailure(
                            "LIFECYCLE_HOOK_ABORTED", "A lifecycle policy stopped the run."));
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
                    RuntimeFailureState.clear(s);
                    s.setRuntimeStep(wasCancelled ? "cancelled" : null);
                    clearRecoveryState(s);
                }
                sessionService.saveSession(s);
            }
            if (broadcaster != null) {
                if (wasAbortedByHook) {
                    broadcastFailureStatus(sessionId, s);
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
            if (durableHeartbeat != null) {
                durableHeartbeat.close();
                durableHeartbeat = null;
            }
            RuntimeFailureFact classifiedFailure = RUNTIME_FAILURE_CLASSIFIER.classify(
                    e, failureEvidence(sessionId, preCtx));
            if (isDurableConversationEnabled() && preCtx != null
                    && preCtx.getDurabilityScope() != null) {
                try {
                    if (sessionDurableCancellationService != null) {
                        committedCancellation = sessionDurableCancellationService
                                .findCommittedForTarget(preCtx.getDurabilityScope())
                                .orElse(null);
                    }
                    if (committedCancellation != null) {
                        if (committedCancellation.outcome()
                                == SessionDurableCancellationService.CancellationOutcome
                                        .EXECUTION_OUTCOME_UNCERTAIN) {
                            finalMessage = "Cancellation requires Tool outcome resolution";
                            durableTerminalAccepted = false;
                        } else {
                            finalStatus = "cancelled";
                            finalMessage = "Cancelled by user";
                            durableTerminalAccepted = true;
                        }
                    } else if (e instanceof DurableRecoveryRetryableException) {
                        durableTerminalAccepted = false;
                    } else if (e instanceof DurableToolExecutionIncompleteException incomplete
                            && incomplete.isUserCancelled()
                            && preCtx.getActiveDurableExecution() != null) {
                        sessionLoopAdmissionService.parkCancelledExecution(
                                preCtx.getDurabilityScope(),
                                preCtx.getActiveDurableExecution(),
                                new RuntimeFailureFact(
                                        "user_action",
                                        "CANCELLED_TOOL_OUTCOME_UNCERTAIN",
                                        false,
                                        "possible",
                                        "Cancellation was requested while a Tool may still be running."));
                        durableTerminalAccepted = true;
                    } else {
                        durableTerminalAccepted = sessionLoopAdmissionService
                                .failIfNoBlockingAttempt(
                                        preCtx.getDurabilityScope(), classifiedFailure);
                    }
                    if (!durableTerminalAccepted && committedCancellation == null) {
                        log.info("Durable loop retained for recovery: sessionId={}", sessionId);
                    }
                } catch (RuntimeException unresolvedOrStale) {
                    durableTerminalAccepted = false;
                    log.info("Stale durable loop cannot mutate Session: sessionId={}", sessionId);
                }
            }
            // 用户友好错误信息：根据 cause chain 识别常见异常类型映射成 actionable 中文提示，
            // 写入 runtime_error / WS error 推给前端展示。完整 stack trace 仅记日志（line above），
            // 不再回灌前端避免暴露内部结构 + 让用户能直接看懂"该重试 / 调超时 / 检查网络"。
            if (finalMessage == null || finalMessage.isBlank()) {
                finalMessage = "Agent loop failed";
            }
            // OBS-2 M1 §D.8.3 (r2 review r2): exception path 保底 finalize trace。
            // engine 抛 unhandled exception 时确保 t_llm_trace.status 不留 'running'。
            // toolCallCount/eventCount 用 0/0 fallback（exception 路径下 engine 局部计数器
            // 不可达，0 是合理 fallback：前端按 trace 拉 spans 实际算计数）。
            // 与 engine 正常 finalize 互斥：engine 已 finalize → status terminal → §B.3
            // `WHERE status='running'` 守卫让本次 UPDATE 0 rows（幂等）。
            try {
                traceStore.finalizeTrace(new TraceFinalizeRequest(
                        traceId,
                        finalStatus,
                        "cancelled".equals(finalStatus)
                                ? "user_cancelled"
                                : "agent_loop_exception",
                        System.currentTimeMillis() - startedAt,
                        0, 0,
                        Instant.now()));
            } catch (Exception ignored) {
                /* observability 失败不影响主路径 */
            }
            try {
                if (committedCancellation != null) {
                    SessionEntity s = sessionService.getSession(sessionId);
                    if (committedCancellation.outcome()
                            == SessionDurableCancellationService.CancellationOutcome
                                    .EXECUTION_OUTCOME_UNCERTAIN) {
                        // The controller that committed/replayed the receipt owns the
                        // authoritative WS projection. The stale worker only tears down local
                        // resources; publishing here would duplicate the same terminal state.
                    } else {
                        try {
                            AgentDefinition cancelledDef = agentService.toAgentDefinition(agentEntity);
                            lifecycleHookDispatcher.fireSessionEnd(
                                    cancelledDef, sessionId, userId,
                                    s.getMessageCount(), "cancelled");
                        } catch (Exception hookErr) {
                            log.warn("SessionEnd hook dispatch on cancellation failed: {}",
                                    hookErr.toString());
                        }
                    }
                } else if (isDurableConversationEnabled() && !durableTerminalAccepted) {
                    deferredErrorSession = null;
                } else {
                SessionEntity s = sessionService.getSession(sessionId);
                if (!isDurableConversationEnabled()) {
                    s.setCompletedAt(java.time.Instant.now());
                    s.setRuntimeStatus("error");
                    RuntimeFailureState.apply(s, classifiedFailure);
                    sessionService.saveSession(s);
                }
                deferredErrorSession = s;
                // SessionEnd hook on error path as well (reason=error)
                try {
                    AgentDefinition errDef = agentService.toAgentDefinition(agentEntity);
                    lifecycleHookDispatcher.fireSessionEnd(errDef, sessionId, userId,
                            s.getMessageCount(), "error");
                } catch (Exception hookErr) {
                    log.warn("SessionEnd hook dispatch on error path failed: {}", hookErr.toString());
                }
                }
            } catch (Exception inner) {
                log.error("Failed to mark session error: sessionId={}", sessionId, inner);
            }
        } finally {
            if (durableHeartbeat != null) {
                durableHeartbeat.close();
            }
            // Ensure CancellationRegistry is cleaned up (may already be done in happy path)
            try {
                cancellationRegistry.unregister(sessionId);
            } catch (Exception ignored) {
            }
            // Wake any pending install confirmation for this session (cancel cascade).
            // Safe to always invoke — no-op when no pending confirmation exists.
            try {
                if ((!isDurableConversationEnabled() || durableTerminalAccepted)
                        && !"waiting_user".equals(finalStatus)
                        && pendingConfirmationRegistry != null) {
                    pendingConfirmationRegistry.completeAllForSession(sessionId, Decision.DENIED);
                }
            } catch (Exception ignored) {
            }
            // r3: only a true root session clears its install-confirm cache. Child sessions
            // inherit the root's approvals and must not wipe them on their own loop end.
            try {
                if ((!isDurableConversationEnabled() || durableTerminalAccepted)
                        && !"waiting_user".equals(finalStatus) && sessionConfirmCache != null) {
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
                if ((!isDurableConversationEnabled() || durableTerminalAccepted)
                        && !"waiting_user".equals(finalStatus)) {
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
                if (!isDurableConversationEnabled() || durableTerminalAccepted) {
                    applicationEventPublisher.publishEvent(new SessionLoopFinishedEvent(
                            sessionId, finalMessage, finalStatus, userId));
                }
            } catch (Exception evtErr) {
                log.error("SessionLoopFinishedEvent publish failed: sessionId={}", sessionId, evtErr);
            }

            // CollabRun hooks: cancel cascade FIRST, then notify completion (null-safe for tests)
            try {
                if ((!isDurableConversationEnabled() || durableTerminalAccepted)
                        && !"waiting_user".equals(finalStatus)
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
            // Publish exception failures only after teardown is complete. Otherwise the Retry
            // action can start a new loop while this finally block is still clearing the old
            // session's registries and confirmations. Keep the reservation until the old
            // error event is published so it cannot overwrite a newer retry's running event.
            try {
                if (deferredErrorSession != null && broadcaster != null) {
                    broadcastFailureStatus(sessionId, deferredErrorSession);
                    broadcaster.userEvent(deferredErrorSession.getUserId(),
                            sessionUpdatedPayload(deferredErrorSession,
                                    deferredErrorSession.getMessageCount()));
                }
            } finally {
                durableRecoveryReservations.remove(sessionId);
                markLoopTaskFinished(sessionId);
            }
        }
        return null;
    }

    private record DurableLoopContinuation(
            String sessionId,
            Long userId,
            AgentEntity agentEntity,
            List<Message> history,
            String traceId,
            String rootTraceId,
            SessionLoopAdmissionService.AdmissionAck authority) {
    }

    private boolean isDurableConversationEnabled() {
        return sessionHistoryProperties != null
                && sessionHistoryProperties.isEnabled()
                && sessionLoopAdmissionService != null
                && sessionDurableCompletionReconciler != null
                && sessionLoopLeaseHeartbeat != null
                && durableSessionRecoveryCoordinator != null;
    }

    /** Stable synchronous acceptance returned before the asynchronous loop produces output. */
    public record ChatSubmissionAck(UUID requestId, String status) {
        public ChatSubmissionAck {
            java.util.Objects.requireNonNull(requestId, "requestId");
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status must not be blank");
            }
        }
    }

    private SessionLoopAdmissionService.AdmissionAck admitDurableLoop(
            String sessionId,
            long userId,
            UUID admissionRequestId,
            String loopId,
            MessageSnapshot userMessage,
            String traceId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return sessionLoopAdmissionService.admit(
                        sessionId, userId, admissionRequestId, loopId, userMessage, traceId);
            } catch (IllegalStateException failure) {
                // Retry the exact immutable admission identity.
            }
        }
        throw new IllegalStateException("Durable loop admission failed");
    }

    private SessionDurableCompletionReconciler.CompletionAck reconcileDurableCompletion(
            com.skillforge.core.engine.durability.LoopDurabilityScope scope,
            DurableFrontier acknowledgedFrontier,
            UUID completionBatchId,
            MessageSnapshot terminalAssistant,
            String traceId,
            long inputTokens,
            long outputTokens) {
        boolean onlyRetryableFailures = true;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return sessionDurableCompletionReconciler.reconcile(
                        scope, acknowledgedFrontier, completionBatchId,
                        terminalAssistant, traceId, inputTokens, outputTokens);
            } catch (DurableRecoveryRetryableException retryable) {
                // Retry the exact immutable completion identity.
            } catch (IllegalStateException failure) {
                // Retry the exact immutable completion identity.
                onlyRetryableFailures = false;
            }
        }
        if (onlyRetryableFailures) throw new DurableRecoveryRetryableException();
        throw new IllegalStateException("Durable completion reconciliation failed");
    }

    private void completeResolvedUnknownTranscript(
            DurableSessionRecoveryCoordinator.RecoveryPlan recoveryPlan) {
        if (recoveryPlan == null || recoveryPlan.postActionClaim() == null) return;
        if (sessionRunCoordinator == null
                || sessionRunCoordinator.acceptTranscriptContinuation(
                                recoveryPlan.postActionClaim())
                        .disposition()
                        == SessionRunCoordinator.HandoffDisposition.HANDOFF_REJECTED) {
            throw new DurableRecoveryRetryableException();
        }
    }

    /** Explicit Engine carrier: durable unpersisted suffix is either empty or one assistant. */
    private static MessageSnapshot durableTerminalAssistant(LoopResult result) {
        List<Message> pending = result.getDeferredBroadcastMessages();
        if (pending == null || pending.isEmpty()) return null;
        if (pending.size() != 1) {
            throw new IllegalStateException("Durable terminal suffix is not singular");
        }
        Message terminal = pending.get(0);
        if (terminal == null || terminal.getRole() != Message.Role.ASSISTANT
                || !terminal.getToolUseBlocks().isEmpty()) {
            throw new IllegalStateException("Durable terminal suffix is invalid");
        }
        return MessageSnapshot.capture(terminal);
    }

    private static void mergeTerminalNotice(Message terminal, String notice) {
        if (terminal == null || terminal.getRole() != Message.Role.ASSISTANT
                || notice == null || notice.isBlank()) {
            throw new IllegalStateException("Durable terminal notice cannot be merged");
        }
        if (terminal.getContent() instanceof String text) {
            terminal.setContent(text.isBlank() ? notice : text + "\n\n" + notice);
            return;
        }
        if (terminal.getContent() instanceof List<?> existing) {
            List<Object> blocks = new ArrayList<>();
            blocks.add(ContentBlock.text(notice));
            blocks.addAll(existing);
            terminal.setContent(blocks);
            return;
        }
        throw new IllegalStateException("Durable terminal notice has unsupported content");
    }

    /** Publishes only the authoritative state committed with a durable cancellation receipt. */
    public void publishDurableCancellationState(
            SessionDurableCancellationService.CancellationAck acknowledgement) {
        if (acknowledgement == null || broadcaster == null) return;
        SessionEntity session = sessionService.getSession(acknowledgement.sessionId());
        if (!java.util.Objects.equals(session.getUserId(), acknowledgement.userId())
                || session.getHistoryEpoch() != acknowledgement.historyEpoch()
                || session.getLoopFence() != acknowledgement.targetLoopFence()
                || session.getActiveLoopId() != null) {
            throw new IllegalStateException(
                    "Durable cancellation state is no longer authoritative");
        }
        if (acknowledgement.outcome()
                == SessionDurableCancellationService.CancellationOutcome
                        .EXECUTION_OUTCOME_UNCERTAIN) {
            broadcastFailureStatus(acknowledgement.sessionId(), session);
        } else {
            broadcaster.sessionStatus(
                    acknowledgement.sessionId(), "idle", "cancelled", null);
        }
        broadcaster.userEvent(session.getUserId(),
                sessionUpdatedPayload(session, session.getMessageCount()));
    }

    public void answerAsk(String sessionId, String askId, String answer, Long userId) {
        if (isDurableConversationEnabled()) {
            answerDurableInteractive(
                    sessionId, askId, answer, null, userId,
                    com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind.ASK_USER);
            return;
        }
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
                RuntimeFailureState.clear(session);
                session.setRuntimeStep("Resuming");
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
        if (isDurableConversationEnabled()) {
            answerDurableInteractive(
                    sessionId, confirmationId,
                    decision.name().toLowerCase(java.util.Locale.ROOT), decision, userId,
                    com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind.CONFIRMATION);
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
                RuntimeFailureState.clear(session);
                session.setRuntimeStep("Resuming");
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

    private void answerDurableInteractive(
            String sessionId,
            String controlId,
            String answer,
            Decision decision,
            Long userId,
            com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind expectedKind) {
        if (interactiveControlTransactions == null || occurrenceArchivePreparation == null) {
            throw new IllegalStateException(
                    "Durable interactive continuation is not configured");
        }
        if (userId == null) throw new IllegalArgumentException("userId is required");

        synchronized (compactionService.lockFor(sessionId)) {
            SessionEntity session = sessionService.getSession(sessionId);
            String continuationLoopId =
                    DurableSessionRecoveryCoordinator.stableRecoveryLoopId(sessionId);
            SessionLoopAdmissionService.ManualContinuationClaimAck continuation =
                    sessionLoopAdmissionService.claimManualContinuation(
                            sessionId, userId, session.getHistoryEpoch(), continuationLoopId);
            SessionLoopLeaseHeartbeat.Handle heartbeat =
                    sessionLoopLeaseHeartbeat.start(continuation.scope());
            try {
                UUID claimRequestId = durableInteractiveId("claim", sessionId, controlId);
                ExecutionClaimCommand claimCommand = new ExecutionClaimCommand(
                        continuation.scope(), continuation.attemptId(), continuation.stepId(),
                        claimRequestId, DurableToolAttemptState.WAITING_USER, 0L);
                SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck claim =
                        interactiveControlTransactions.claimAnswerForDispatch(
                                claimCommand, controlId);
                if (claim.selectedControl().kind() != expectedKind) {
                    throw new IllegalStateException(
                            "Interactive control kind does not match answer endpoint");
                }

                SessionInteractiveControlTransactionService.InteractiveResultAck result;
                if (claim.execution().state() == DurableToolAttemptState.RESULTS_COMMITTED) {
                    result = interactiveControlTransactions.loadCommittedAnswerResults(
                            claim.execution(), controlId);
                    requireDurableResolutionMatch(result, expectedKind, answer, decision);
                } else {
                    if (claim.execution().state() != DurableToolAttemptState.EXECUTING) {
                        throw new IllegalStateException(
                                "Interactive answer did not acquire a valid execution generation");
                    }
                    if (expectedKind
                            == com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind
                                    .CONFIRMATION
                            && decision == Decision.APPROVED
                            && !claim.dispatchGranted()) {
                        throw new RetryBusyException();
                    }
                    Message selectedResult = buildDurableInteractiveResult(
                            session, claim, expectedKind, answer, decision, userId);
                    SessionInteractiveControlTransactionService.ResolutionKind resolutionKind =
                            expectedKind
                                    == com.skillforge.core.engine.durability.InteractiveStepPlanner
                                            .CallKind.ASK_USER
                                    ? SessionInteractiveControlTransactionService.ResolutionKind
                                            .ANSWERED
                                    : decision == Decision.APPROVED
                                            ? SessionInteractiveControlTransactionService
                                                    .ResolutionKind.APPROVED
                                            : SessionInteractiveControlTransactionService
                                                    .ResolutionKind.DENIED;
                    result = interactiveControlTransactions.commitAnswerResults(
                            new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                                    claim.execution(), controlId,
                                    durableInteractiveId("results", sessionId, controlId),
                                    MessageSnapshot.capture(selectedResult), resolutionKind,
                                    answer, "card",
                                    durableInteractiveId("trace", sessionId, controlId).toString()));
                    requireDurableResolutionMatch(result, expectedKind, answer, decision);
                }

                ArchivePreparationCommand archiveCommand =
                        ArchivePreparationCommand.from(result.results());
                occurrenceArchivePreparation.ensurePrepared(archiveCommand);
                heartbeat.assertAuthoritative();
                occurrenceArchivePreparation.withResultVisibilityAuthority(
                        archiveCommand,
                        () -> publishDurableInteractiveResults(result));
            } finally {
                heartbeat.close();
            }

            try {
                // The recovery coordinator uses the same stable loop identity installed by the
                // manual claim, so this is an exact continuation rather than a second takeover.
                resumeDurableInterruptedTurnAsync(sessionId);
            } catch (RetryBusyException alreadyResuming) {
                log.info("Durable interactive continuation is already running: sessionId={}",
                        sessionId);
            }
        }
    }

    private Message buildDurableInteractiveResult(
            SessionEntity session,
            SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck claim,
            com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind kind,
            String answer,
            Decision decision,
            Long userId) {
        String toolUseId = claim.selectedControl().call().toolUseId();
        if (kind == com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind.ASK_USER) {
            return Message.toolResult(toolUseId, "User answered: " + answer, false);
        }
        if (decision == null) {
            throw new IllegalArgumentException("confirmation decision is required");
        }
        Object thawedInput = claim.selectedControl().call().input().toJavaValue();
        if (!(thawedInput instanceof Map<?, ?>)) {
            throw new IllegalStateException("Durable confirmation Tool input is invalid");
        }
        Map<String, Object> canonicalInput = mapValue(thawedInput);
        Map<String, Object> payload = mapValue(claim.control().metadata().get("payload"));
        Map<String, Object> extra = mapValue(payload.get("extra"));
        if (!claim.selectedControl().call().input().equals(
                FrozenJson.capture(extra.get("toolInput")))) {
            throw new IllegalStateException(
                    "Durable confirmation payload does not match its Tool intent");
        }
        AgentEntity agentEntity = agentService.getAgent(session.getAgentId());
        AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
        String modelOverride = session.getRuntimeModelOverride();
        if (modelOverride != null && !modelOverride.isBlank()) {
            agentDef.setModelId(modelOverride);
        }
        return agentLoopEngine.completeConfirmedToolAfterDurableClaim(
                agentDef, session.getId(), userId, toolUseId,
                claim.selectedControl().call().toolName(), canonicalInput,
                stringValue(extra.get("confirmationKind")),
                stringValue(extra.get("installTool")),
                stringValue(extra.get("installTarget")), decision);
    }

    private static void requireDurableResolutionMatch(
            SessionInteractiveControlTransactionService.InteractiveResultAck result,
            com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind expectedKind,
            String answer,
            Decision decision) {
        Map<String, Object> metadata = result.control().metadata();
        String expectedState = expectedKind
                == com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind.ASK_USER
                ? "answered"
                : decision == Decision.APPROVED ? "approved" : "denied";
        if (!expectedState.equals(metadata.get("state"))
                || !java.util.Objects.equals(answer, metadata.get("answer"))
                || !"card".equals(metadata.get("answerMode"))) {
            throw new IllegalStateException(
                    "Interactive control was already resolved with a different answer");
        }
    }

    private void publishDurableInteractiveResults(
            SessionInteractiveControlTransactionService.InteractiveResultAck result) {
        if (broadcaster == null) return;
        for (var occurrence : result.results().results()) {
            broadcaster.messageAppended(
                    result.results().executionScope().sessionId(),
                    occurrence.traceId(), occurrence.message().toMessage());
        }
    }

    private void publishRecoveredResultBlocks(ArchivePreparationAck archive) {
        if (broadcaster == null || archive == null) return;
        archive.resultBlocks().stream()
                .sorted(java.util.Comparator.comparingInt(
                        com.skillforge.core.engine.durability.PersistedBlockOccurrence
                                ::resultBatchOrdinal))
                .forEach(block -> {
                    try {
                        broadcaster.messageAppended(
                                block.sessionId(), block.traceId(),
                                Message.toolResult(
                                        block.toolUseId(), block.content(), block.error(),
                                        block.errorType()));
                    } catch (RuntimeException publishFailure) {
                        // The durable row remains authoritative and will appear on refresh. A
                        // transient client transport failure must not invalidate its archive gate.
                        log.warn("Recovered Tool result broadcast failed: sessionId={}",
                                block.sessionId(), publishFailure);
                    }
                });
    }

    /**
     * Re-emits a durable parked control after restart without claiming execution authority.
     * The transaction service revalidates the scanned owner/epoch and exact persisted payload;
     * this method never schedules the engine, Provider, or selected Tool.
     */
    public void republishWaitingInteractiveControl(
            String sessionId, long expectedUserId, long expectedHistoryEpoch) {
        if (!isDurableConversationEnabled()) return;
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting;
        try {
            waiting = interactiveControlTransactions.loadParkedWaitingControl(
                    sessionId, expectedUserId, expectedHistoryEpoch);
        } catch (DurableRecoveryRetryableException retryable) {
            throw retryable;
        } catch (RuntimeException corruptOrMissing) {
            try {
                interactiveControlTransactions.recordParkedWaitingControlRecoveryFailure(
                        sessionId, expectedUserId, expectedHistoryEpoch);
            } catch (RuntimeException recordFailure) {
                log.error("Waiting control recovery failure could not be recorded: sessionId={}",
                        sessionId);
            }
            throw new DurableRecoveryFailureException();
        }
        if (broadcaster != null) {
            publishRecoveredInteractiveControl(sessionId, waiting);
            broadcaster.sessionStatus(
                    sessionId, "waiting_user", "waiting_control", null);
        }
    }

    private void publishRecoveredInteractiveControl(
            String sessionId,
            SessionInteractiveControlTransactionService.InteractiveIntentAck waiting) {
        if (broadcaster == null || waiting == null) return;
        try {
            Map<String, Object> payload = mapValue(
                    waiting.control().metadata().get("payload"));
            String kind = stringValue(payload.get("interactionKind"));
            String controlId = waiting.control().controlId();
            if ("ask_user".equals(kind)) {
                ChatEventBroadcaster.AskUserEvent event =
                        new ChatEventBroadcaster.AskUserEvent();
                event.askId = controlId;
                event.question = stringValue(payload.get("question"));
                event.context = stringValue(payload.get("context"));
                event.allowOther = Boolean.TRUE.equals(payload.get("allowOther"));
                Object rawOptions = payload.get("options");
                event.options = rawOptions instanceof List<?> options
                        ? options.stream().map(ChatService::askOption).toList()
                        : List.of();
                broadcaster.askUser(sessionId, event);
                return;
            }
            if ("confirmation".equals(kind)) {
                Map<String, Object> extra = mapValue(payload.get("extra"));
                Object rawOptions = payload.get("options");
                List<com.skillforge.core.engine.confirm.ConfirmationPromptPayload
                        .ConfirmationChoice> choices = rawOptions instanceof List<?> options
                        ? options.stream().map(ChatService::confirmationChoice).toList()
                        : List.of();
                Instant expiresAt = null;
                String rawExpiry = stringValue(extra.get("expiresAt"));
                if (rawExpiry != null && !rawExpiry.isBlank()) {
                    expiresAt = Instant.parse(rawExpiry);
                }
                broadcaster.confirmationRequired(sessionId,
                        new com.skillforge.core.engine.confirm.ConfirmationPromptPayload(
                                controlId, sessionId,
                                stringValue(extra.get("installTool")),
                                stringValue(extra.get("installTarget")),
                                stringValue(extra.get("commandPreview")),
                                stringValue(payload.get("question")),
                                stringValue(payload.get("context")),
                                choices, expiresAt));
            }
        } catch (RuntimeException malformedOrUnavailable) {
            log.warn("Recovered interactive control could not be republished", malformedOrUnavailable);
        }
    }

    private static ChatEventBroadcaster.AskUserEvent.Option askOption(Object raw) {
        Map<String, Object> option = mapValue(raw);
        return new ChatEventBroadcaster.AskUserEvent.Option(
                String.valueOf(option.getOrDefault("label", "")),
                stringValue(option.get("description")));
    }

    private static com.skillforge.core.engine.confirm.ConfirmationPromptPayload
            .ConfirmationChoice confirmationChoice(Object raw) {
        Map<String, Object> option = mapValue(raw);
        return new com.skillforge.core.engine.confirm.ConfirmationPromptPayload
                .ConfirmationChoice(
                        String.valueOf(option.getOrDefault("value", "")),
                        String.valueOf(option.getOrDefault("label", "")),
                        String.valueOf(option.getOrDefault("style", "")));
    }

    private static UUID durableInteractiveId(
            String purpose, String sessionId, String controlId) {
        String identity = "skillforge:durable-interactive:" + purpose + ":"
                + sessionId + ":" + controlId;
        return UUID.nameUUIDFromBytes(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            runLoop(request.sessionId(), request.userMessage(), request.userMessageBlock(),
                    request.userId(), request.agentEntity(), request.history(),
                    request.traceId(), request.rootTraceId(), null, request.recoveryPlan());
        });
        return new ResumeLoopSubmission(prepared);
    }

    private record ResumeLoopRequest(
            String sessionId,
            Long userId,
            AgentEntity agentEntity,
            List<Message> history,
            String traceId,
            String rootTraceId,
            String userMessage,
            Message userMessageBlock,
            DurableSessionRecoveryCoordinator.RecoveryPlan recoveryPlan) {

        private ResumeLoopRequest(String sessionId,
                                  Long userId,
                                  AgentEntity agentEntity,
                                  List<Message> history,
                                  String traceId,
                                  String rootTraceId) {
            this(sessionId, userId, agentEntity, history, traceId, rootTraceId,
                    null, null, null);
        }
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
     * Retry the persisted user turn that most recently failed without appending a duplicate
     * user message. The previous turn remains the immutable history prefix; this method only
     * allocates a fresh trace and starts the loop from that prefix.
     */
    public void retryFailedTurnAsync(String sessionId) {
        if (isDurableConversationEnabled()) {
            throw new IllegalStateException(
                    "Durable failed-turn recovery requires the fenced recovery coordinator");
        }
        synchronized (compactionService.lockFor(sessionId)) {
            SessionEntity session = sessionService.getSession(sessionId);
            if (!"error".equals(session.getRuntimeStatus())) {
                throw new IllegalStateException("session is not in error state");
            }
            if (!RuntimeFailureState.isRetryAllowed(session)) {
                throw new IllegalStateException("session failure is not retryable");
            }
            if (hasActiveLoopTask(sessionId)) {
                throw new RetryBusyException();
            }
            Long executionUserId = session.getUserId();
            if (executionUserId == null) {
                throw new IllegalStateException("session has no execution owner");
            }

            List<Message> persistedHistory = sessionService.getContextMessages(sessionId);
            if (persistedHistory.isEmpty()
                    || !isRetryableUserTurn(persistedHistory.get(persistedHistory.size() - 1))) {
                throw new IllegalStateException("session has no retryable failed user turn");
            }
            Message failedUserTurn = persistedHistory.get(persistedHistory.size() - 1);
            List<Message> historyPrefix = new ArrayList<>(
                    persistedHistory.subList(0, persistedHistory.size() - 1));
            String retryUserMessage = extractRetryUserText(failedUserTurn);

            // Reserve capacity before changing persisted runtime state. If the executor is
            // saturated, the session stays in error and the user can retry again later.
            ResumeLoopSubmission submission = reserveResumeLoop();
            try {
                AgentEntity agentEntity = agentService.getAgent(session.getAgentId());
                String retryTraceId = UUID.randomUUID().toString();
                String retryRootTraceId = sessionService.getActiveRootTraceId(sessionId);
                if (retryRootTraceId == null) {
                    retryRootTraceId = retryTraceId;
                    sessionService.setActiveRootTraceId(sessionId, retryRootTraceId);
                }

                session.setCompletedAt(null);
                session.setRuntimeStatus("running");
                RuntimeFailureState.clear(session);
                session.setRuntimeStep("Retrying");
                sessionService.saveSession(session);
                if (broadcaster != null) {
                    broadcaster.sessionStatus(sessionId, "running", "Retrying", null);
                    broadcaster.userEvent(session.getUserId(),
                            sessionUpdatedPayload(session, session.getMessageCount()));
                }
                submission.start(new ResumeLoopRequest(
                        sessionId, executionUserId, agentEntity, historyPrefix,
                        retryTraceId, retryRootTraceId, retryUserMessage, failedUserTurn, null));
            } catch (RuntimeException | Error error) {
                submission.abort(error);
                throw error;
            }
        }
    }

    /**
     * Resume a loop abandoned by a previous JVM without appending a synthetic user message.
     * The persisted transcript is the source of truth: a user tail is replayed as the current
     * turn, while a paired tool_result tail is passed as completed history.
     */
    public void resumeInterruptedTurnAsync(String sessionId) {
        if (isDurableConversationEnabled()) {
            resumeDurableInterruptedTurnAsync(sessionId);
            return;
        }
        synchronized (compactionService.lockFor(sessionId)) {
            SessionEntity session = sessionService.getSession(sessionId);
            if (!"running".equals(session.getRuntimeStatus())) {
                throw new IllegalStateException("session is not an interrupted running task");
            }
            if (hasActiveLoopTask(sessionId)) {
                throw new RetryBusyException();
            }
            Long executionUserId = session.getUserId();
            if (executionUserId == null) {
                throw new IllegalStateException("session has no execution owner");
            }
            List<Message> persisted = sessionService.getContextMessages(sessionId);
            if (persisted.isEmpty()) {
                throw new IllegalStateException("session has no persisted recovery boundary");
            }

            Message tail = persisted.get(persisted.size() - 1);
            List<Message> history;
            String userText = null;
            Message userBlock = null;
            if (isRetryableUserTurn(tail)) {
                history = new ArrayList<>(persisted.subList(0, persisted.size() - 1));
                userText = extractRetryUserText(tail);
                userBlock = tail;
            } else if (isToolResultMessage(tail)) {
                history = new ArrayList<>(persisted);
            } else {
                throw new IllegalStateException("session tail is not a resumable boundary");
            }

            ResumeLoopSubmission submission = reserveResumeLoop();
            try {
                AgentEntity agent = agentService.getAgent(session.getAgentId());
                String traceId = UUID.randomUUID().toString();
                String rootTraceId = sessionService.getActiveRootTraceId(sessionId);
                if (rootTraceId == null) {
                    rootTraceId = traceId;
                    sessionService.setActiveRootTraceId(sessionId, rootTraceId);
                }
                session.setCompletedAt(null);
                session.setRuntimeStep("Recovering");
                RuntimeFailureState.clear(session);
                sessionService.saveSession(session);
                if (broadcaster != null) {
                    broadcaster.sessionStatus(sessionId, "running", "Recovering", null);
                    broadcaster.userEvent(session.getUserId(),
                            sessionUpdatedPayload(session, session.getMessageCount()));
                }
                submission.start(new ResumeLoopRequest(sessionId, executionUserId, agent,
                        history, traceId, rootTraceId, userText, userBlock, null));
            } catch (RuntimeException | Error error) {
                submission.abort(error);
                throw error;
            }
        }
    }

    private void resumeDurableInterruptedTurnAsync(String sessionId) {
        synchronized (compactionService.lockFor(sessionId)) {
            SessionEntity session = sessionService.getSession(sessionId);
            if (!"running".equals(session.getRuntimeStatus())) {
                throw new IllegalStateException("session is not an interrupted running task");
            }
            if (hasActiveLoopTask(sessionId)
                    || !durableRecoveryReservations.add(sessionId)) {
                throw new RetryBusyException();
            }
            Long executionUserId = session.getUserId();
            if (executionUserId == null) {
                durableRecoveryReservations.remove(sessionId);
                throw new IllegalStateException("session has no execution owner");
            }

            ResumeLoopSubmission submission;
            try {
                // Reserve local capacity before changing the distributed lease owner.
                submission = reserveResumeLoop();
            } catch (RuntimeException | Error error) {
                durableRecoveryReservations.remove(sessionId);
                throw error;
            }
            DurableSessionRecoveryCoordinator.RecoveryPlan plan = null;
            try {
                plan = durableSessionRecoveryCoordinator.recover(sessionId, executionUserId);
                if (plan.disposition()
                        == DurableSessionRecoveryCoordinator.RecoveryDisposition.WAITING_USER) {
                    sessionLoopAdmissionService.parkForManualContinuation(
                            plan.admission().scope(),
                            com.skillforge.core.engine.durability.DurableToolAttemptState.WAITING_USER,
                            null);
                    submission.abort(new IllegalStateException("recovery is waiting for user input"));
                    durableRecoveryReservations.remove(sessionId);
                    if (broadcaster != null) {
                        publishRecoveredInteractiveControl(
                                sessionId, plan.recoveredWaitingControl());
                        broadcaster.sessionStatus(
                                sessionId, "waiting_user", "waiting_control", null);
                    }
                    return;
                }
                if (plan.disposition()
                        == DurableSessionRecoveryCoordinator.RecoveryDisposition
                                .UNCERTAIN_PENDING_RESOLUTION
                        || plan.disposition()
                        == DurableSessionRecoveryCoordinator.RecoveryDisposition
                                .MANUAL_CONTINUATION) {
                    boolean unresolved = plan.disposition()
                            == DurableSessionRecoveryCoordinator.RecoveryDisposition
                                    .UNCERTAIN_PENDING_RESOLUTION;
                    RuntimeFailureFact uncertainty = RUNTIME_FAILURE_CLASSIFIER.harnessFailure(
                            unresolved
                                    ? "TOOL_OUTCOME_UNCERTAIN"
                                    : "RESOLUTION_CONTINUATION_PENDING",
                            unresolved
                                    ? "A previous Tool may have completed; explicit resolution is required."
                                    : "The resolved Tool outcome requires an explicit continuation claim.",
                            "possible");
                    sessionLoopAdmissionService.parkForManualContinuation(
                            plan.admission().scope(),
                            unresolved
                                    ? com.skillforge.core.engine.durability
                                            .DurableToolAttemptState
                                            .UNCERTAIN_PENDING_RESOLUTION
                                    : com.skillforge.core.engine.durability
                                            .DurableToolAttemptState.RESOLVED_UNKNOWN,
                            uncertainty);
                    submission.abort(new IllegalStateException(
                            unresolved
                                    ? "recovery requires resolution"
                                    : "recovery requires a continuation claim"));
                    durableRecoveryReservations.remove(sessionId);
                    if (broadcaster != null) {
                        SessionEntity parked = sessionService.getSession(sessionId);
                        broadcastFailureStatus(sessionId, parked);
                        broadcaster.userEvent(parked.getUserId(),
                                sessionUpdatedPayload(parked, parked.getMessageCount()));
                    }
                    return;
                }

                if (plan.archiveVisibilityCommand() != null) {
                    ArchivePreparationCommand recoveredArchiveCommand =
                            plan.archiveVisibilityCommand();
                    ArchivePreparationAck recoveredArchive = plan.archivePreparation();
                    occurrenceArchivePreparation.withResultVisibilityAuthority(
                            recoveredArchiveCommand,
                            () -> publishRecoveredResultBlocks(recoveredArchive));
                }

                List<Message> persisted = sessionService.getContextMessages(sessionId);
                if (persisted.isEmpty()) {
                    throw new IllegalStateException("session has no persisted recovery boundary");
                }
                List<Message> history;
                String userText = null;
                Message userBlock = null;
                Message tail = persisted.get(persisted.size() - 1);
                if (plan.disposition()
                        == DurableSessionRecoveryCoordinator.RecoveryDisposition.TOOL_REPLAY) {
                    if (tail.getRole() != Message.Role.ASSISTANT
                            || tail.getToolUseBlocks().isEmpty()) {
                        throw new IllegalStateException(
                                "recovered Tool intent is not the transcript tail");
                    }
                    history = new ArrayList<>(persisted.subList(0, persisted.size() - 1));
                } else if (plan.admission().attemptId() == null
                        && isRetryableUserTurn(tail)) {
                    history = new ArrayList<>(persisted.subList(0, persisted.size() - 1));
                    userText = extractRetryUserText(tail);
                    userBlock = tail;
                } else if (plan.admission().attemptId() == null
                        && isPlainAssistantTurn(tail)) {
                    // Completion reconciliation keeps the loop lease only when a queued
                    // USER exists. If the JVM dies before that inbox row is drained, the
                    // committed terminal assistant is still the transcript tail. Resume
                    // with the full history so AgentLoopEngine can drain the durable inbox
                    // at its next provider boundary without inventing another USER.
                    history = new ArrayList<>(persisted);
                } else if (isToolResultMessage(tail)) {
                    history = new ArrayList<>(persisted);
                } else {
                    throw new IllegalStateException(
                            "session tail is not a durable recovery boundary");
                }

                AgentEntity agent = agentService.getAgent(session.getAgentId());
                String traceId = UUID.randomUUID().toString();
                String rootTraceId = sessionService.getActiveRootTraceId(sessionId);
                if (rootTraceId == null) {
                    rootTraceId = traceId;
                    sessionService.setActiveRootTraceId(sessionId, rootTraceId);
                }
                if (broadcaster != null) {
                    try {
                        broadcaster.sessionStatus(sessionId, "running", "Recovering", null);
                    } catch (RuntimeException broadcastFailure) {
                        log.warn("Durable recovery status broadcast failed: sessionId={}",
                                sessionId, broadcastFailure);
                    }
                }
                submission.start(new ResumeLoopRequest(
                        sessionId, executionUserId, agent, history, traceId, rootTraceId,
                        userText, userBlock, plan));
            } catch (RuntimeException | Error error) {
                submission.abort(error);
                durableRecoveryReservations.remove(sessionId);
                boolean retryable = error instanceof DurableRecoveryRetryableException
                        || error instanceof TransientDataAccessException
                        || error instanceof CannotCreateTransactionException;
                if (plan != null && !retryable) {
                    try {
                        sessionLoopAdmissionService.parkRecoveryFailure(
                                plan.admission().scope(),
                                RUNTIME_FAILURE_CLASSIFIER.harnessFailure(
                                        "DURABLE_RECOVERY_DISPATCH_FAILED",
                                        "The durable recovery boundary could not be dispatched.",
                                        "possible"));
                    } catch (RuntimeException staleOrAlreadyParked) {
                        log.info("Durable recovery scope was already closed: sessionId={}",
                                sessionId);
                    }
                }
                throw error;
            }
        }
    }

    private static boolean isToolResultMessage(Message message) {
        if (message == null || message.getRole() != Message.Role.USER
                || !(message.getContent() instanceof List<?> blocks)) {
            return false;
        }
        for (Object block : blocks) {
            if (block instanceof ContentBlock cb && "tool_result".equals(cb.getType())) return true;
            if (block instanceof Map<?, ?> map && "tool_result".equals(String.valueOf(map.get("type")))) return true;
        }
        return false;
    }

    private static boolean isPlainAssistantTurn(Message message) {
        return message != null
                && message.getRole() == Message.Role.ASSISTANT
                && message.getToolUseBlocks().isEmpty();
    }

    private static void clearRecoveryState(SessionEntity session) {
        session.setRecoveryAttempts(0);
        session.setRecoveryState("none");
        session.setRecoveryReason(null);
        session.setRecoveryStartedAt(null);
    }

    private static boolean isRetryableUserTurn(Message message) {
        if (message == null || message.getRole() != Message.Role.USER) {
            return false;
        }
        if (!(message.getContent() instanceof List<?> blocks)) {
            return true;
        }
        for (Object block : blocks) {
            String type = null;
            if (block instanceof ContentBlock contentBlock) {
                type = contentBlock.getType();
            } else if (block instanceof Map<?, ?> map && map.get("type") != null) {
                type = map.get("type").toString();
            }
            if ("tool_result".equals(type)) {
                return false;
            }
        }
        return true;
    }

    static Message findLatestUserTurn(List<Message> history) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (isRetryableUserTurn(message)) {
                return message;
            }
        }
        return null;
    }

    static String extractRetryUserText(Message message) {
        if (message == null) {
            return null;
        }
        Object content = message.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (!(content instanceof List<?> blocks)) {
            return message.getTextContent();
        }
        List<String> userText = new ArrayList<>();
        for (Object block : blocks) {
            String type = null;
            String text = null;
            if (block instanceof ContentBlock contentBlock) {
                type = contentBlock.getType();
                text = contentBlock.getText();
            } else if (block instanceof Map<?, ?> map) {
                Object typeValue = map.get("type");
                Object textValue = map.get("text");
                type = typeValue != null ? typeValue.toString() : null;
                text = textValue != null ? textValue.toString() : null;
            }
            if ("text".equals(type)
                    && text != null
                    && !text.startsWith("<system-reminder>")) {
                userText.add(text);
            }
        }
        return userText.isEmpty() ? null : String.join("\n", userText);
    }

    static boolean isSafeToRetryFailure(LoopContext loopContext, List<Message> history) {
        if (loopContext != null && (loopContext.hasObservedProviderStreamDelta()
                || !loopContext.getToolCallCounts().isEmpty())) {
            return false;
        }
        return history != null
                && !history.isEmpty()
                && isRetryableUserTurn(history.get(history.size() - 1));
    }

    private RuntimeFailureEvidence failureEvidence(String sessionId, LoopContext loopContext) {
        boolean streamDelta = loopContext != null
                && loopContext.hasObservedProviderStreamDelta();
        boolean toolCall = loopContext != null
                && !loopContext.getToolCallCounts().isEmpty();
        try {
            List<Message> history = sessionService.getContextMessages(sessionId);
            boolean tailIsUser = history != null && !history.isEmpty()
                    && isRetryableUserTurn(history.get(history.size() - 1));
            return new RuntimeFailureEvidence(streamDelta, toolCall, tailIsUser);
        } catch (RuntimeException evidenceError) {
            log.warn("Failed to collect runtime failure evidence: sessionId={}",
                    sessionId, evidenceError);
            return new RuntimeFailureEvidence(streamDelta, toolCall, false);
        }
    }

    void markLoopTaskStarted(String sessionId) {
        if (sessionId == null) return;
        activeLoopTaskCounts.compute(sessionId, (ignored, count) -> {
            AtomicInteger next = count != null ? count : new AtomicInteger();
            next.incrementAndGet();
            return next;
        });
    }

    void markLoopTaskFinished(String sessionId) {
        if (sessionId == null) return;
        activeLoopTaskCounts.computeIfPresent(sessionId,
                (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private boolean hasActiveLoopTask(String sessionId) {
        AtomicInteger count = activeLoopTaskCounts.get(sessionId);
        return durableRecoveryReservations.contains(sessionId)
                || (count != null && count.get() > 0);
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

}
