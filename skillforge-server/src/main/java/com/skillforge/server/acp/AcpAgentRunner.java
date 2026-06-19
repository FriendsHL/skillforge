package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs ONE cc prompt as a SkillForge sub-session and streams it live
 * (ACP-EXTERNAL-AGENT P1a-2 — the AC-1 milestone).
 *
 * <p>Flow:
 * <ol>
 *   <li>create a viewable SkillForge session for this cc run;</li>
 *   <li>mark it {@code running} (DB + broadcast);</li>
 *   <li>spawn a cc adapter via {@link AcpClientFactory}; {@code initialize} →
 *       {@code session/new} → optional {@code session/set_model} → {@code prompt};</li>
 *   <li>translate {@code session/update} → broadcaster (text/reasoning live);</li>
 *   <li>on completion, PERSIST the accumulated assistant text (Option A — the
 *       run is a reviewable record) via the normal {@link SessionService} append
 *       path, then mark {@code idle};</li>
 *   <li>ALWAYS close the cc client/process (try/finally); on error mark
 *       {@code error}.</li>
 * </ol>
 *
 * <p><b>Session shape (decision):</b> P1a-2's trigger is standalone (no parent
 * agent dispatches it — that is P1c). So we create a top-level
 * {@link SessionService#createSession(Long, Long)} session owned by a configured
 * agent/user, which is the simplest shape that is independently viewable in the
 * dashboard. When P1c wires the {@code RunExternalAgent} tool, it can instead use
 * {@link SessionService#createSubSession} off the dispatching parent.
 *
 * <p><b>Persistence-shape note:</b> this sub-session is a RECORD of a cc run — it
 * is NOT re-run through the SkillForge engine, so the persisted assistant message
 * is not subject to engine-reconstruction byte-identity reconciliation. We still
 * persist via the normal {@code appendNormalMessages} path (no hand-written rows),
 * text-only (no tool_use blocks in P1a-2 → no tool_use↔tool_result pairing).
 *
 * <p><b>Threading (J-W3):</b> {@code session/update} listeners run on the cc
 * transport reader thread. The listener here only accumulates text + broadcasts
 * (non-blocking), so it never stalls the reader. The caller thread blocks on the
 * prompt future with a configurable deadline.
 */
public class AcpAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AcpAgentRunner.class);

    private final AcpClientFactory clientFactory;
    private final SessionService sessionService;
    private final AgentRepository agentRepository;
    private final ChatEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final AcpRunnerProperties properties;
    private final AcpPermissionBridge permissionBridge;
    /**
     * P1c-1: dependencies for the SubAgent-mode entry ({@link #runAsSubAgent}).
     * Nullable so the standalone P1a-2 demo path ({@link #run}) and the existing
     * 7-arg unit-test constructor keep working without them. {@code runAsSubAgent}
     * requires all three to be non-null (the Spring bean wires them).
     */
    private final Executor subAgentExecutor;
    private final SubAgentRegistry subAgentRegistry;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Standalone (P1a-2) constructor — leaves the SubAgent-mode deps
     * ({@code subAgentExecutor} / {@code subAgentRegistry} / {@code eventPublisher})
     * null. This is the demo-endpoint-only wiring used by {@code POST /api/acp/runs}
     * and the existing unit tests. {@link #runAsSubAgent} is unavailable here: it
     * guards on these null deps and throws {@link IllegalStateException} (not an NPE)
     * rather than running. The Spring bean always uses the full constructor below.
     */
    public AcpAgentRunner(AcpClientFactory clientFactory,
                          SessionService sessionService,
                          AgentRepository agentRepository,
                          ChatEventBroadcaster broadcaster,
                          ObjectMapper objectMapper,
                          AcpRunnerProperties properties,
                          AcpPermissionBridge permissionBridge) {
        this(clientFactory, sessionService, agentRepository, broadcaster, objectMapper,
                properties, permissionBridge, null, null, null);
    }

    /**
     * Full (P1c-1) constructor — adds the SubAgent-mode wiring so a parent agent
     * can dispatch cc via {@link com.skillforge.server.tool.SubAgentTool}.
     *
     * @param subAgentExecutor executor that runs the (long-blocking) cc prompt off
     *                         the dispatch thread, so {@code runAsSubAgent} returns
     *                         immediately (matches {@code ChatService.chatAsync})
     * @param subAgentRegistry reused to deliver cc's result back to the parent
     *                         session (its {@code onSessionLoopFinished} pumps the
     *                         result into the parent's pending mailbox — AC-5)
     * @param eventPublisher   publishes {@link SessionLoopFinishedEvent} for the
     *                         child cc session, mirroring {@code ChatService}'s
     *                         teardown so generic listeners (channel async delivery,
     *                         scheduled-task tracking) fire for cc runs too
     */
    public AcpAgentRunner(AcpClientFactory clientFactory,
                          SessionService sessionService,
                          AgentRepository agentRepository,
                          ChatEventBroadcaster broadcaster,
                          ObjectMapper objectMapper,
                          AcpRunnerProperties properties,
                          AcpPermissionBridge permissionBridge,
                          Executor subAgentExecutor,
                          SubAgentRegistry subAgentRegistry,
                          ApplicationEventPublisher eventPublisher) {
        this.clientFactory = clientFactory;
        this.sessionService = sessionService;
        this.agentRepository = agentRepository;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.permissionBridge = permissionBridge;
        this.subAgentExecutor = subAgentExecutor;
        this.subAgentRegistry = subAgentRegistry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Result of one ACP run: the created sub-session id plus the number of cc
     * subagent dispatches observed (AC-2a; a {@code Task} tool_call = one subagent).
     */
    public record RunResult(String subSessionId, int subagentCount) {
    }

    /**
     * Run a cc prompt as a sub-session and stream it live. Blocks until the cc
     * prompt completes (or the deadline / an error fires), then returns the
     * created sub-session id so the caller (the P1a-2 trigger endpoint) can hand
     * it back for the dashboard to open.
     *
     * @param prompt the user prompt to send to cc (required, non-blank)
     * @param model  optional cc model id (from {@code session/new} models); null ⇒ cc default
     * @param userId the authenticated caller's id — OWNS the created sub-session
     *               (BLOCKER-1a: must not default to the config user, or any caller
     *               could later answer another user's permission prompt)
     * @return the created SkillForge sub-session id
     */
    public String run(String prompt, String model, Long userId) {
        return runResult(prompt, model, userId).subSessionId();
    }

    /**
     * Run a cc prompt as a sub-session and stream it live, returning the created
     * sub-session id AND the cc subagent count (AC-2a).
     *
     * @param userId the authenticated caller's id; OWNS the sub-session (BLOCKER-1a)
     */
    public RunResult runResult(String prompt, String model, Long userId) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }

        // Standalone (P1a-2) path: this runner creates and owns its own viewable
        // session. executeOnSession (shared core) persists + marks idle + sets the
        // title (incl. AC-2a subagent count) on success, or marks error on failure.
        SessionEntity session = createRunSession(userId);
        CcOutcome outcome = executeOnSession(session, prompt, model);
        if (outcome.status() == Status.ERROR) {
            // executeOnSession already marked the session error + closed the client;
            // preserve the standalone contract of throwing AcpException to the caller.
            throw new AcpException("ACP run failed: " + outcome.errorMessage());
        }
        return new RunResult(session.getId(), outcome.subagentCount());
    }

    /**
     * P1c-1 — SubAgent mode. Run a cc prompt on the GIVEN child session (created
     * by {@code SubAgentTool.createSubSession}) ASYNCHRONOUSLY, then deliver cc's
     * result back to the parent session + origin channel by reusing the SubAgent
     * registry pump + the generic {@link SessionLoopFinishedEvent} (AC-5).
     *
     * <p>Returns immediately (the cc prompt is long-blocking, so the actual run is
     * submitted to {@link #subAgentExecutor}) — exactly like
     * {@code ChatService.chatAsync}, so {@code SubAgentTool.handleDispatch} can hand
     * the runId back without blocking the dispatching agent's loop.
     *
     * <p>Differences from {@link #runResult}: (1) does NOT create a session — runs
     * on {@code child}; (2) does NOT throw on cc failure — the error is surfaced as
     * a finished event with {@code status=error} so the parent still gets a result;
     * (3) on completion (success OR error) emits the same finished signals
     * {@code ChatService} emits in its loop teardown.
     *
     * @param child  the child session this cc run executes in (parent linkage +
     *               sub-agent run id already set by SubAgentTool); must be non-null
     * @param task   the prompt to send to cc (required, non-blank)
     * @param userId owning user id (carried into {@link SessionLoopFinishedEvent})
     */
    public void runAsSubAgent(SessionEntity child, String task, Long userId) {
        if (child == null) {
            throw new IllegalArgumentException("child session must not be null");
        }
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        if (subAgentExecutor == null || subAgentRegistry == null || eventPublisher == null) {
            throw new IllegalStateException(
                    "runAsSubAgent requires the full AcpAgentRunner constructor (executor + registry + eventPublisher)");
        }
        final String childSessionId = child.getId();
        // ASYNC: submit so the dispatching agent's tool call returns immediately
        // (mirrors ChatService.chatAsync). The cc prompt blocks up to promptTimeout.
        subAgentExecutor.execute(() -> {
            long startedAt = System.currentTimeMillis();
            CcOutcome outcome;
            try {
                outcome = executeOnSession(child, task, null);
            } catch (RuntimeException e) {
                // executeOnSession is designed to capture cc errors into a CcOutcome
                // (status=ERROR) rather than throw; this catch is a last-resort guard so
                // an unexpected throw still delivers a result to the parent (no hang).
                log.error("ACP runAsSubAgent unexpected failure (child {}): {}",
                        childSessionId, e.toString(), e);
                outcome = CcOutcome.error(safeErr(e), 0);
            }
            finishSubAgent(childSessionId, userId, outcome, System.currentTimeMillis() - startedAt);
        });
    }

    /**
     * SubAgent-mode completion: persistence + idle/error were already handled by
     * {@code executeOnSession}; here we ONLY emit the finished signals that
     * {@code ChatService.runLoop}'s teardown emits, so the existing delivery wiring
     * fires for the cc child with zero new delivery code:
     * <ul>
     *   <li>{@code subAgentRegistry.onSessionLoopFinished} — pushes cc's final text
     *       into the parent session's pending mailbox and resumes the parent loop
     *       (which, when it finishes, triggers channel async delivery — AC-5);</li>
     *   <li>{@code SessionLoopFinishedEvent} — the generic event consumed by
     *       channel async delivery / scheduled-task tracking.</li>
     * </ul>
     * Both calls use the same argument shapes {@code ChatService} uses (registry:
     * 5-arg with toolCalls + durationMs; event: 4-arg
     * sessionId/finalMessage/status/userId).
     *
     * <p><b>java-W1 — why no {@code waiting_user} guard:</b> {@code ChatService}'s
     * teardown skips emitting these signals when {@code finalStatus == "waiting_user"}
     * (an engine loop paused on a confirmation/ask is not yet terminal). That status
     * is <b>impossible on the ACP path</b>: cc permission requests are awaited
     * <i>inline within this run</i> by {@link AcpPermissionBridge} (the wait runs on a
     * worker thread; the cc prompt future stays pending until the user answers via the
     * ACP transport) — the SkillForge child session is NEVER set to
     * {@code waiting_user} (that status is only written by the engine confirmation
     * flow, which this path bypasses). {@code executeOnSession} therefore only ever
     * returns a terminal outcome (cc {@code stopReason} → completed, or an error), so
     * {@code finalStatus} here is always {@code completed} or {@code error} and the
     * guard would be dead code.
     */
    private void finishSubAgent(String childSessionId, Long userId, CcOutcome outcome, long durationMs) {
        String finalStatus = outcome.status() == Status.ERROR ? "error" : "completed";
        String finalMessage = outcome.status() == Status.ERROR
                ? "ACP cc run failed: " + outcome.errorMessage()
                : outcome.finalText();
        try {
            subAgentRegistry.onSessionLoopFinished(
                    childSessionId, finalMessage, finalStatus, outcome.toolCallCount(), durationMs);
        } catch (Exception e) {
            log.error("ACP runAsSubAgent: SubAgentRegistry hook failed for child {}", childSessionId, e);
        }
        try {
            eventPublisher.publishEvent(new SessionLoopFinishedEvent(
                    childSessionId, finalMessage, finalStatus, userId));
        } catch (Exception e) {
            log.error("ACP runAsSubAgent: SessionLoopFinishedEvent publish failed for child {}",
                    childSessionId, e);
        }
        log.info("ACP runAsSubAgent finished (child {}, status={}, toolCalls={}, durationMs={})",
                childSessionId, finalStatus, outcome.toolCallCount(), durationMs);
    }

    /** Terminal state of one cc run. */
    private enum Status { COMPLETED, ERROR }

    /**
     * Outcome of one cc run on a session: the accumulated final text, the subagent
     * count (AC-2a), the total tool_call count (for the finished-event count), and
     * — on the error path — the error message. {@code executeOnSession} already did
     * the persistence + runtime-status side effects; this record only carries what
     * the callers need to finalize (title / delivery).
     */
    private record CcOutcome(Status status, String finalText, int subagentCount,
                             int toolCallCount, String errorMessage) {
        static CcOutcome ok(String finalText, int subagentCount, int toolCallCount) {
            return new CcOutcome(Status.COMPLETED, finalText, subagentCount, toolCallCount, null);
        }
        static CcOutcome error(String errorMessage, int toolCallCount) {
            return new CcOutcome(Status.ERROR, "", 0, toolCallCount, errorMessage);
        }
    }

    /**
     * Shared cc-run core (used by both {@link #runResult} standalone and
     * {@link #runAsSubAgent}). Runs on the GIVEN session: marks it running, spawns
     * cc, drives the handshake + prompt, live-streams + accumulates updates, then on
     * success builds + PERSISTS the canonical turn (INV-1 paired) and marks the
     * session idle; on any failure marks the session error. Closes the cc client +
     * cleans the working dir in a finally.
     *
     * <p>Never throws for cc/transport failures — returns a {@link CcOutcome} with
     * {@code status=ERROR} so the async SubAgent caller can still deliver a result.
     * (The standalone caller re-derives an {@link AcpException} from the outcome to
     * preserve its throwing contract.)
     */
    private CcOutcome executeOnSession(SessionEntity session, String prompt, String model) {
        String subSessionId = session.getId();

        Path cwd = null;
        AcpClient client = null;
        // W-5: assistantText + toolCalls are WRITTEN on the cc transport reader thread
        // (via the update listener) and READ on this caller thread only AFTER
        // promptFuture.get() returns. CompletableFuture.get() establishes a happens-before
        // edge between the reader thread's last write (which precedes the future completion)
        // and this thread's read, so no extra synchronization is needed. (Reads in the
        // error/timeout path don't touch them.)
        StringBuilder assistantText = new StringBuilder();
        AcpToolCallAccumulator toolCalls = new AcpToolCallAccumulator(objectMapper);
        // W-2: ensure assistantStreamEnd fires at most once per run, even if finishOk
        // partially succeeds (stream-end emitted) then throws → outer catch → finishError.
        AtomicBoolean streamEnded = new AtomicBoolean(false);
        try {
            // W-1: markRunning is INSIDE the try so a saveSession/broadcast failure here
            // routes to finishError (session never stuck "running" with no error status).
            markRunning(session);

            cwd = createWorkingDir();
            // P2-1: inject OTLP telemetry env so cc exports its log/events (api_request /
            // tool_result / subagent_completed / ...) to the SkillForge OtlpReceiverController,
            // tagged with sf.session_id=<this sub-session> so the ingest layer binds them back.
            client = clientFactory.create(cwd.toString(), buildTelemetryEnv(session));

            // Accumulate + live-stream. Runs on the cc reader thread — keep it non-blocking.
            client.setUpdateListener(u -> onUpdate(subSessionId, u, assistantText, toolCalls));
            // Permission bridge: async (J-W3) — handler returns immediately, the latch
            // wait runs on the bridge's executor and answers via the responder later.
            final String sid = subSessionId;
            client.setServerRequestHandler((req, responder) ->
                    permissionBridge.handlePermissionRequest(sid, req, responder));
            client.start();

            client.initialize().get(handshakeTimeout(), TimeUnit.SECONDS);

            JsonNode newSessionResult = client.newSession(cwd.toString(), List.of())
                    .get(handshakeTimeout(), TimeUnit.SECONDS);
            String ccSessionId = requireCcSessionId(newSessionResult);

            // Set the configured permission mode. Default "auto" → cc runs
            // autonomously (its classifier approves/denies tools, no user prompt) so
            // cc completes without the user answering per-tool cards. "default" →
            // cc prompts (AC-3) for dangerous ops — only usable once the confirmation
            // card reliably reaches the user. Best-effort: an adapter without set_mode
            // just stays in its default (also "auto").
            trySetPermissionMode(client, ccSessionId, subSessionId);

            if (model != null && !model.isBlank()) {
                client.setModel(ccSessionId, model).get(handshakeTimeout(), TimeUnit.SECONDS);
            }

            JsonNode promptBlock = objectMapper.createObjectNode().put("type", "text").put("text", prompt);
            CompletableFuture<JsonNode> promptFuture = client.prompt(ccSessionId, List.of(promptBlock));

            JsonNode promptResult;
            try {
                promptResult = promptFuture.get(properties.getPromptTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                // cc futures never time out by contract — cancel + let finally close.
                log.warn("ACP prompt timed out after {}s, cancelling cc session {} (sub-session {})",
                        properties.getPromptTimeoutSeconds(), ccSessionId, subSessionId);
                try {
                    client.cancel(ccSessionId);
                } catch (RuntimeException ignore) {
                    // best-effort cancel; finally still closes the process
                }
                throw new AcpException("ACP prompt timed out after "
                        + properties.getPromptTimeoutSeconds() + "s");
            }

            String stopReason = (promptResult != null && promptResult.hasNonNull("stopReason"))
                    ? promptResult.get("stopReason").asText() : "unknown";
            int subagents = toolCalls.subagentCount();
            int toolCallCount = toolCalls.toolCallCount();
            // Shared: build + persist the canonical (INV-1 paired) turn, then mark idle.
            finishOk(subSessionId, assistantText.toString(), toolCalls, stopReason, subagents, streamEnded);
            return CcOutcome.ok(assistantText.toString(), subagents, toolCallCount);
        } catch (Exception e) {
            log.error("ACP run failed for sub-session {}: {}", subSessionId, e.toString(), e);
            finishError(subSessionId, e, streamEnded);
            return CcOutcome.error(safeErr(e), toolCalls.toolCallCount());
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException ce) {
                    log.warn("ACP client close threw for sub-session {} (ignored)", subSessionId, ce);
                }
            }
            cleanupWorkingDir(cwd);
        }
    }

    /**
     * Best-effort {@code session/set_mode <configured mode>}. Default {@code "auto"}
     * → cc runs autonomously (no per-tool prompt); {@code "default"} → cc prompts,
     * which requires the confirmation card to reach the user (AC-3). Non-fatal: an
     * adapter without set_mode just stays in its own default (also {@code "auto"}).
     */
    private void trySetPermissionMode(AcpClient client, String ccSessionId, String subSessionId) {
        String mode = properties.getPermissionMode();
        try {
            client.setMode(ccSessionId, mode).get(handshakeTimeout(), TimeUnit.SECONDS);
        } catch (Exception e) {
            // Non-fatal: an adapter without set_mode just stays in its default mode.
            // Log and continue rather than failing the run.
            log.warn("ACP set_mode '{}' failed (sub-session {}): {}",
                    mode, subSessionId, e.toString());
        }
    }

    // ───────────────────────────── session lifecycle ─────────────────────────────

    private SessionEntity createRunSession(Long ownerUserId) {
        Long agentId = resolveAgentId();
        // BLOCKER-1a: the sub-session is owned by the authenticated caller, NOT the
        // config default user — so requireOwnedSession on the confirmation endpoint
        // binds permission approval to the run's owner.
        SessionEntity session = sessionService.createSession(ownerUserId, agentId);
        session.setTitle("ACP cc run");
        return sessionService.saveSession(session);
    }

    /**
     * Resolve the agent that owns the run session. Configured agent wins; else any
     * available agent (so the session is viewable). Throws if there is no agent at
     * all — a session with a dangling agentId would not be openable.
     */
    private Long resolveAgentId() {
        if (properties.getAgentId() != null
                && agentRepository.findById(properties.getAgentId()).isPresent()) {
            return properties.getAgentId();
        }
        return agentRepository.findAll().stream()
                .map(AgentEntity::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No agent available to own the ACP run session; configure skillforge.acp.agent-id"));
    }

    private void markRunning(SessionEntity session) {
        session.setRuntimeStatus("running");
        session.setRuntimeStep("ACP cc");
        session.setRuntimeError(null);
        sessionService.saveSession(session);
        broadcaster.sessionStatus(session.getId(), "running", "ACP cc", null);
    }

    private void finishOk(String subSessionId, String fullText, AcpToolCallAccumulator toolCalls,
                          String stopReason, int subagentCount, AtomicBoolean streamEnded) {
        // Build the turn in the canonical SkillForge/Anthropic shape so it renders and
        // can be loaded/compacted later (the compact pairing logic expects tool_result
        // in a FOLLOWING user message, not inside the assistant message):
        //   - ASSISTANT: leading text (if any) + tool_use blocks;
        //   - USER:      the matching tool_result blocks (INV-1, paired by id; an
        //                incomplete tool_use gets a synthesized error tool_result).
        // When there are no tool calls, persist plain text (string content) so
        // text-only runs stay byte-identical to P1a-2.
        List<Message> turn = new ArrayList<>(2);
        Message assistantMsg;
        if (toolCalls.hasAnyToolCalls()) {
            List<ContentBlock> assistantBlocks = new ArrayList<>();
            if (!fullText.isEmpty()) {
                assistantBlocks.add(ContentBlock.text(fullText));
            }
            assistantBlocks.addAll(toolCalls.buildToolUseBlocks());
            assistantMsg = new Message();
            assistantMsg.setRole(Message.Role.ASSISTANT);
            assistantMsg.setContent(assistantBlocks);
            turn.add(assistantMsg);

            Message toolResultMsg = new Message();
            toolResultMsg.setRole(Message.Role.USER);
            toolResultMsg.setContent(toolCalls.buildToolResultBlocks());
            turn.add(toolResultMsg);
        } else {
            assistantMsg = Message.assistant(fullText);
            turn.add(assistantMsg);
        }

        // Option A: persist via the normal append path (no hand-written rows).
        // W-2: emit assistantStreamEnd only AFTER persistence succeeds — if append throws,
        // the outer catch → finishError handles stream-end (guarded), so it fires once.
        sessionService.appendNormalMessages(subSessionId, turn);

        emitStreamEndOnce(subSessionId, streamEnded);
        for (Message m : turn) {
            broadcaster.messageAppended(subSessionId, null, m);
        }

        SessionEntity session = sessionService.getSession(subSessionId);
        session.setRuntimeStatus("idle");
        session.setRuntimeStep(null);
        session.setRuntimeError(null);
        // AC-2a: surface the subagent count on the (viewable, persisted) sub-session title.
        if (subagentCount > 0) {
            session.setTitle("ACP cc run (" + subagentCount
                    + (subagentCount == 1 ? " subagent)" : " subagents)"));
            if (broadcaster != null) {
                broadcaster.sessionTitleUpdated(subSessionId, session.getTitle());
            }
        }
        sessionService.saveSession(session);
        broadcaster.sessionStatus(subSessionId, "idle", null, null);
        log.info("ACP run completed (sub-session {}, stopReason={}, chars={}, subagents={})",
                subSessionId, stopReason, fullText.length(), subagentCount);
    }

    private void finishError(String subSessionId, Exception e, AtomicBoolean streamEnded) {
        // compact-W4: the run's accumulated tool calls are INTENTIONALLY discarded on
        // the error path — we do NOT partially persist tool_use/tool_result here. A
        // partial persist could leave an orphan tool_use (its completion may never have
        // arrived) violating INV-1; finishOk is the only place that persists the turn,
        // and only it pairs every tool_use with a (real or synthesized) tool_result.
        try {
            emitStreamEndOnce(subSessionId, streamEnded);
            SessionEntity session = sessionService.getSession(subSessionId);
            session.setRuntimeStatus("error");
            session.setRuntimeStep(null);
            session.setRuntimeError(safeErr(e));
            sessionService.saveSession(session);
            broadcaster.sessionStatus(subSessionId, "error", null, safeErr(e));
        } catch (RuntimeException re) {
            log.warn("ACP failed to set error status on sub-session {} (ignored)", subSessionId, re);
        }
    }

    /** W-2: broadcast assistantStreamEnd at most once per run. */
    private void emitStreamEndOnce(String subSessionId, AtomicBoolean streamEnded) {
        if (streamEnded.compareAndSet(false, true)) {
            broadcaster.assistantStreamEnd(subSessionId);
        }
    }

    // ───────────────────────────── update streaming ─────────────────────────────

    /** Live-stream a translated update. Runs on the cc reader thread — non-blocking. */
    private void onUpdate(String subSessionId, AcpSessionUpdate u, StringBuilder assistantText,
                          AcpToolCallAccumulator toolCalls) {
        AcpUpdate update = u.update();
        if (update instanceof AcpUpdate.TextChunk text) {
            assistantText.append(text.text());
            broadcaster.assistantDelta(subSessionId, text.text());
        } else if (update instanceof AcpUpdate.ThoughtChunk thought) {
            broadcaster.reasoningDelta(subSessionId, thought.text());
        } else if (update instanceof AcpUpdate.ToolCall tc) {
            // tool_call (pending) → start a tool_use; stream it to the UI.
            toolCalls.onToolCall(tc);
            if (tc.toolCallId() != null) {
                broadcaster.toolStarted(subSessionId, tc.toolCallId(),
                        toolCalls.nameOf(tc.toolCallId()), toolCalls.inputOf(tc.toolCallId()));
            }
        } else if (update instanceof AcpUpdate.ToolCallUpdate tcu) {
            // tool_call_update: refine input (UI) and, on completion, record tool_result.
            // No double-persist — buildToolUseBlocks()/buildToolResultBlocks() at run
            // end emit one pair per id.
            toolCalls.onToolCallUpdate(tcu);
            if (tcu.toolCallId() != null) {
                if ("completed".equals(tcu.status()) || "failed".equals(tcu.status())) {
                    broadcaster.toolFinished(subSessionId, tcu.toolCallId(),
                            tcu.status(), 0L, null);
                } else {
                    broadcaster.toolUseComplete(subSessionId, tcu.toolCallId(),
                            toolCalls.inputOf(tcu.toolCallId()));
                }
            }
        } else {
            // plan / available_commands / mode / unknown: log so we never crash on them.
            log.debug("ACP update ignored (sub-session {}): kind={}", subSessionId, update.rawKind());
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private String requireCcSessionId(JsonNode newSessionResult) {
        if (newSessionResult == null || !newSessionResult.hasNonNull("sessionId")) {
            throw new AcpException("session/new did not return a sessionId");
        }
        return newSessionResult.get("sessionId").asText();
    }

    /**
     * P2-1 — build the OTLP telemetry env injected into the spawned cc child so it
     * exports its log/events to the SkillForge {@code OtlpReceiverController}, bound
     * to this run via {@code OTEL_RESOURCE_ATTRIBUTES=sf.session_id=<sub-session>}.
     *
     * <p>Matches the spike's working env block (/tmp/acp-spike/otel-spike.mjs):
     * telemetry on, OTLP http/json exporter, base endpoint = SkillForge, prompt
     * flush via short schedule delays. Returns an EMPTY map (no telemetry) when
     * {@code skillforge.acp.otlp-endpoint} is blank, so telemetry can be disabled.
     *
     * <p>Note: cc appends {@code /v1/<signal>} to {@code OTEL_EXPORTER_OTLP_ENDPOINT}
     * itself, so we pass the BASE url only. The values are merged on top of the
     * sanitized parent env by {@code ProcessAcpTransport.sanitizeEnv}.
     */
    private Map<String, String> buildTelemetryEnv(SessionEntity session) {
        String endpoint = properties.getOtlpEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return Map.of();
        }
        Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("CLAUDE_CODE_ENABLE_TELEMETRY", "1");
        env.put("OTEL_LOGS_EXPORTER", "otlp");
        env.put("OTEL_METRICS_EXPORTER", "otlp");
        env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json");
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
        // Flush promptly so events land while the run is still observable.
        env.put("OTEL_BSP_SCHEDULE_DELAY", "1000");
        env.put("OTEL_BLRP_SCHEDULE_DELAY", "1000");
        // Bind every emitted event back to this SkillForge sub-session + owning agent.
        StringBuilder resourceAttrs = new StringBuilder("sf.session_id=").append(session.getId());
        if (session.getAgentId() != null) {
            resourceAttrs.append(",sf.agent_id=").append(session.getAgentId());
        }
        env.put("OTEL_RESOURCE_ATTRIBUTES", resourceAttrs.toString());
        return env;
    }

    /** A safe per-run cwd — NEVER the SkillForge repo root (cc would touch our own source). */
    private Path createWorkingDir() throws IOException {
        String root = properties.getWorkspaceRoot();
        if (root != null && !root.isBlank()) {
            Path base = Path.of(root);
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "acp-run-");
        }
        return Files.createTempDirectory("acp-run-");
    }

    private void cleanupWorkingDir(Path cwd) {
        if (cwd == null) {
            return;
        }
        try (var paths = Files.walk(cwd)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                            // best-effort cleanup
                        }
                    });
        } catch (IOException e) {
            log.debug("ACP working dir cleanup skipped for {}: {}", cwd, e.getMessage());
        }
    }

    /** Handshake calls (initialize/newSession/setModel) get a short, fixed deadline. */
    private long handshakeTimeout() {
        return Math.min(60, Math.max(10, properties.getPromptTimeoutSeconds()));
    }

    private static String safeErr(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }
}
