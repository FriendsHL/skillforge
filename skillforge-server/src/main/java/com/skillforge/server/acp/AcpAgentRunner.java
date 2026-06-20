package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.acp.otlp.CcEventSpanTranslator;
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
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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
     * P2-3a: trace finalize deps. Both nullable so the standalone P1a-2 demo path and
     * the existing unit-test constructors keep working without observability wiring.
     * When EITHER is null the runner skips trace finalization (no-op); the spans the cc
     * event translator wrote remain, only the trace-row terminal status/duration/counts
     * are not stamped. The Spring bean always wires both.
     *
     * <p>{@code traceStore} reads back the cc trace's spans (count by kind) and stamps
     * the terminal trace row; {@code traceFinalizeScheduler} defers that finalize by a
     * short grace delay so late cc OTLP events (≈1s flush) land before the counts are
     * recomputed — WITHOUT blocking the runner thread.
     */
    private final LlmTraceStore traceStore;
    private final ScheduledExecutorService traceFinalizeScheduler;

    /**
     * Standalone (P1a-2) constructor — leaves the SubAgent-mode deps
     * ({@code subAgentExecutor} / {@code subAgentRegistry} / {@code eventPublisher})
     * AND the P2-3a trace-finalize deps null. This is the demo-endpoint-only wiring
     * used by {@code POST /api/acp/runs} and the existing unit tests.
     * {@link #runAsSubAgent} is unavailable here: it guards on the null SubAgent deps
     * and throws {@link IllegalStateException} (not an NPE) rather than running. Trace
     * finalization is simply skipped. The Spring bean always uses the full constructor.
     */
    public AcpAgentRunner(AcpClientFactory clientFactory,
                          SessionService sessionService,
                          AgentRepository agentRepository,
                          ChatEventBroadcaster broadcaster,
                          ObjectMapper objectMapper,
                          AcpRunnerProperties properties,
                          AcpPermissionBridge permissionBridge) {
        this(clientFactory, sessionService, agentRepository, broadcaster, objectMapper,
                properties, permissionBridge, null, null, null, null, null);
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
        this(clientFactory, sessionService, agentRepository, broadcaster, objectMapper,
                properties, permissionBridge, subAgentExecutor, subAgentRegistry,
                eventPublisher, null, null);
    }

    /**
     * Full (P2-3a) constructor — adds the trace-finalize deps so a completed/errored cc
     * run stamps its sub-session trace terminal status/duration/counts (the bug this
     * milestone fixes: without it a finished cc trace shows running/0/0 forever).
     *
     * @param traceStore            reads the cc trace's spans (count by kind) and
     *                              finalizes the trace row; null ⇒ finalize skipped
     * @param traceFinalizeScheduler defers the finalize by a grace delay so late cc
     *                              OTLP events land first, WITHOUT blocking the runner
     *                              thread; null ⇒ finalize skipped
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
                          ApplicationEventPublisher eventPublisher,
                          LlmTraceStore traceStore,
                          ScheduledExecutorService traceFinalizeScheduler) {
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
        this.traceStore = traceStore;
        this.traceFinalizeScheduler = traceFinalizeScheduler;
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
        // P2-3a: authoritative run start for the trace's total duration (run start → end).
        final long runStartMs = System.currentTimeMillis();

        Workspace workspace = null;
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

            workspace = createWorkspace(subSessionId);
            Path cwd = workspace.dir();
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

            // Compose the prompt cc actually receives = the session agent's system
            // prompt (role/rules framing) + the assigned task. ACP carries no separate
            // system field, so the framing is folded into the prompt text.
            String ccPrompt = buildCcPrompt(session, prompt);
            JsonNode promptBlock = objectMapper.createObjectNode().put("type", "text").put("text", ccPrompt);
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
            // P2-3a: finalize the cc trace (status=ok, run start→end duration, counts
            // recomputed from spans) after a grace delay so late cc OTLP events land.
            scheduleTraceFinalize(subSessionId, "ok", null, runStartMs);
            return CcOutcome.ok(assistantText.toString(), subagents, toolCallCount);
        } catch (Exception e) {
            log.error("ACP run failed for sub-session {}: {}", subSessionId, e.toString(), e);
            finishError(subSessionId, e, streamEnded);
            // P2-3a: finalize the cc trace as error (covers cc failure + prompt timeout).
            scheduleTraceFinalize(subSessionId, "error", safeErr(e), runStartMs);
            return CcOutcome.error(safeErr(e), toolCalls.toolCallCount());
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException ce) {
                    log.warn("ACP client close threw for sub-session {} (ignored)", subSessionId, ce);
                }
            }
            cleanupWorkspace(workspace);
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

    // ───────────────────────────── trace finalize (P2-3a) ─────────────────────────────

    /**
     * P2-3a: finalize the cc sub-session's trace once the run reaches a terminal state.
     *
     * <p>The cc event translator ({@link CcEventSpanTranslator}) builds the trace
     * on-ingest (one trace per cc sub-session, keyed by a deterministic id) and writes
     * llm/tool/event spans, but it NEVER finalizes the trace and does NOT bump the
     * trace-row tool/event counters — so a finished cc trace would otherwise show
     * {@code status=running}, {@code durationMs=0}, {@code toolCallCount=0},
     * {@code eventCount=0} forever. This stamps the terminal status, the authoritative
     * run start→end duration, and counts RECOMPUTED from the actual spans.
     *
     * <p><b>Late-event safety:</b> cc flushes OTLP events on a ~1s schedule, so the last
     * {@code api_request} / {@code subagent_completed} can arrive AFTER the prompt's
     * {@code stopReason}. The finalize is therefore SCHEDULED after a short grace delay
     * (so those late spans land before counts are recomputed) and runs on the scheduler
     * thread — NEVER blocking the runner thread.
     *
     * <p><b>Idempotency:</b> {@code finalizeTrace}'s SQL is {@code WHERE status='running'},
     * so a re-finalize or a finalize racing a late span never flips a terminal trace back
     * to running nor double-counts. A late span landing AFTER finalize only inserts a span
     * row (the translator's {@code upsertTraceStub} is {@code ON CONFLICT DO NOTHING}); it
     * does not touch the trace status. The grace delay minimizes how often that happens.
     *
     * <p>Best-effort: if the finalize deps are not wired (standalone/test paths) this is a
     * no-op; any scheduling/finalize failure is logged + swallowed (observability is never
     * allowed to break a run).
     */
    private void scheduleTraceFinalize(String subSessionId, String status, String error, long runStartMs) {
        if (traceStore == null || traceFinalizeScheduler == null) {
            return; // observability not wired (standalone/test) — skip.
        }
        long graceSec = properties.getTraceFinalizeGraceSeconds();
        try {
            if (graceSec <= 0) {
                doFinalizeTrace(subSessionId, status, error, runStartMs);
            } else {
                traceFinalizeScheduler.schedule(
                        () -> doFinalizeTrace(subSessionId, status, error, runStartMs),
                        graceSec, TimeUnit.SECONDS);
            }
        } catch (RuntimeException e) {
            log.warn("ACP trace finalize schedule failed (sub-session {}): {}",
                    subSessionId, e.toString());
        }
    }

    /**
     * Recompute tool/event counts from the actual spans the translator wrote, then
     * finalize the cc trace. Runs on the scheduler thread. Never throws.
     */
    private void doFinalizeTrace(String subSessionId, String status, String error, long runStartMs) {
        try {
            String traceId = CcEventSpanTranslator.traceIdFor(subSessionId);
            java.util.Map<String, Long> byKind = traceStore.countSpansByKind(traceId);
            int toolCount = byKind.getOrDefault("tool", 0L).intValue();
            int eventCount = byKind.getOrDefault("event", 0L).intValue();
            long now = System.currentTimeMillis();
            long durationMs = Math.max(0, now - runStartMs);
            traceStore.finalizeTrace(new LlmTraceStore.TraceFinalizeRequest(
                    traceId, status, error, durationMs, toolCount, eventCount,
                    Instant.ofEpochMilli(now)));
            log.info("ACP trace finalized (sub-session {}, status={}, durationMs={}, tools={}, events={})",
                    subSessionId, status, durationMs, toolCount, eventCount);
        } catch (RuntimeException e) {
            log.warn("ACP trace finalize failed (sub-session {}): {}", subSessionId, e.toString());
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
     * Compose the full prompt cc receives = the session agent's system prompt
     * (role / rules framing) followed by the assigned task. ACP's
     * {@code session/prompt} carries only prompt content (no separate system
     * field), so the framing must be folded into the prompt text — the
     * provider-agnostic approach (works for cc today and, later, codex). This is
     * complementary to the agent's own runtime system prompt and any
     * {@code CLAUDE.md} cc reads from its workspace.
     *
     * <p>When the session's agent has no usable system prompt (none configured,
     * blank, or the agent row cannot be loaded), the raw task is sent unchanged —
     * so a misconfiguration degrades to "task only", never to a failed run.
     */
    private String buildCcPrompt(SessionEntity session, String task) {
        String systemPrompt = resolveAgentSystemPrompt(session.getAgentId());
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return task;
        }
        return systemPrompt.strip() + "\n\n---\n\n# Task\n\n" + task;
    }

    /** Best-effort load of the agent's system prompt; null on no agent / not found / load error. */
    private String resolveAgentSystemPrompt(Long agentId) {
        if (agentId == null) {
            return null;
        }
        try {
            return agentRepository.findById(agentId)
                    .map(AgentEntity::getSystemPrompt)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("ACP: failed to load agent {} system prompt; sending raw task", agentId, e);
            return null;
        }
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

    /**
     * The cc child's working directory for one run + how to tear it down.
     *
     * @param dir        the directory cc runs in (cwd)
     * @param isWorktree true ⇒ {@code dir} is a git worktree of the configured repo
     *                   on {@code branch}; false ⇒ a throwaway scratch temp dir
     * @param branch     the worktree branch name (null when {@code !isWorktree})
     */
    private record Workspace(Path dir, boolean isWorktree, String branch) {
        static Workspace throwaway(Path dir) {
            return new Workspace(dir, false, null);
        }
        static Workspace worktree(Path dir, String branch) {
            return new Workspace(dir, true, branch);
        }
    }

    /**
     * Allowed chars for git VALUE args we build (branch name, base ref). The
     * branch's variable part is a UUID and the base ref is operator-config — this
     * is defense-in-depth, paired with a leading-'-' reject in
     * {@link #requireSafeGitValue}.
     */
    private static final Pattern SAFE_GIT_VALUE = Pattern.compile("[A-Za-z0-9._/-]+");

    /** Reject blank, non-matching, or leading-'-' (option-like) git value args. */
    private void requireSafeGitValue(String label, String value) {
        if (value == null || value.isBlank()
                || value.startsWith("-")
                || !SAFE_GIT_VALUE.matcher(value).matches()) {
            throw new AcpException("refusing unsafe git " + label + ": " + value);
        }
    }

    /**
     * Create the cc working directory for this run.
     *
     * <p>Worktree mode (option A) — when {@code skillforge.acp.repo-root} is set: a
     * fresh {@code git worktree} of that repo on a per-run branch
     * ({@code <prefix><sub-session-id>}) based on {@code worktree-base-ref}. cc edits
     * the REAL codebase, isolated to a reviewable branch; the main working tree is
     * never touched.
     *
     * <p>Throwaway mode (default) — when repo-root is blank: a fresh empty temp dir
     * (legacy behavior), under {@code workspace-root} if configured.
     */
    private Workspace createWorkspace(String subSessionId) throws IOException {
        String repoRoot = properties.getRepoRoot();
        if (repoRoot == null || repoRoot.isBlank()) {
            return Workspace.throwaway(createThrowawayDir());
        }
        return createWorktree(repoRoot.strip(), subSessionId);
    }

    private Path createThrowawayDir() throws IOException {
        String root = properties.getWorkspaceRoot();
        if (root != null && !root.isBlank()) {
            Path base = Path.of(root);
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "acp-run-");
        }
        return Files.createTempDirectory("acp-run-");
    }

    /**
     * {@code git -C <repo> worktree add -b <branch> <dir> <baseRef>}. The worktree dir
     * is created under {@code worktree-root} (must be OUTSIDE the repo — a worktree
     * cannot nest in its own repo). Throws (failing the run) on any git error rather
     * than silently degrading to a scratch dir, so a misconfiguration surfaces.
     */
    private Workspace createWorktree(String repoRoot, String subSessionId) throws IOException {
        String branch = properties.getWorktreeBranchPrefix() + subSessionId;
        String baseRef = properties.getWorktreeBaseRef();
        // Both flow to git as VALUE args. SAFE_BRANCH + a leading-'-' reject blocks git
        // argument injection (a value like "--upload-pack=..." being parsed as a flag).
        // These are operator-config, so this is defense-in-depth, not a user boundary.
        requireSafeGitValue("worktree branch", branch);
        requireSafeGitValue("worktree base ref", baseRef);
        Path worktreeBase = resolveWorktreeRoot();
        Files.createDirectories(worktreeBase);
        Path worktreeDir = worktreeBase.resolve("acp-cc-" + subSessionId);

        runGit(Path.of(repoRoot), "worktree", "add", "-b", branch,
                worktreeDir.toString(), baseRef);
        log.info("ACP worktree created (sub-session {}): branch={} dir={} base={}",
                subSessionId, branch, worktreeDir, properties.getWorktreeBaseRef());
        return Workspace.worktree(worktreeDir, branch);
    }

    /** worktree-root → workspace-root → OS temp, as the parent dir for worktrees. */
    private Path resolveWorktreeRoot() {
        String wtRoot = properties.getWorktreeRoot();
        if (wtRoot != null && !wtRoot.isBlank()) {
            return Path.of(wtRoot.strip());
        }
        String wsRoot = properties.getWorkspaceRoot();
        if (wsRoot != null && !wsRoot.isBlank()) {
            return Path.of(wsRoot.strip());
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "acp-worktrees");
    }

    /**
     * Tear down the run's workspace.
     *
     * <ul>
     *   <li>throwaway scratch dir → recursively deleted (as before);</li>
     *   <li>worktree + {@code keep-worktree-on-finish=true} (default) → KEPT and the
     *       path/branch logged so the changes are reviewable / can become a PR;</li>
     *   <li>worktree + keep=false → {@code git worktree remove --force} +
     *       {@code git branch -D} (best-effort).</li>
     * </ul>
     */
    private void cleanupWorkspace(Workspace workspace) {
        if (workspace == null) {
            return;
        }
        if (!workspace.isWorktree()) {
            deleteRecursively(workspace.dir());
            return;
        }
        if (properties.isKeepWorktreeOnFinish()) {
            log.info("ACP worktree KEPT for review: branch={} dir={} (review via "
                            + "`git -C {} status` / `git -C {} diff`)",
                    workspace.branch(), workspace.dir(), workspace.dir(), workspace.dir());
            return;
        }
        removeWorktree(workspace);
    }

    private void removeWorktree(Workspace workspace) {
        String repoRoot = properties.getRepoRoot();
        if (repoRoot == null || repoRoot.isBlank()) {
            return; // can't reach the repo to remove; leave it.
        }
        Path repo = Path.of(repoRoot.strip());
        try {
            runGit(repo, "worktree", "remove", "--force", workspace.dir().toString());
        } catch (RuntimeException | IOException e) {
            log.warn("ACP worktree remove failed (dir {}): {}", workspace.dir(), e.toString());
        }
        try {
            runGit(repo, "branch", "-D", workspace.branch());
        } catch (RuntimeException | IOException e) {
            log.warn("ACP worktree branch delete failed (branch {}): {}", workspace.branch(), e.toString());
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                            // best-effort cleanup
                        }
                    });
        } catch (IOException e) {
            log.debug("ACP working dir cleanup skipped for {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Run {@code git <args>} with {@code cwd = repo} via ProcessBuilder (no shell, so
     * args are not subject to shell injection). Throws {@link AcpException} on a
     * non-zero exit (with captured output) or a timeout.
     */
    private void runGit(Path repo, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("git");
        java.util.Collections.addAll(cmd, args);
        Process proc = new ProcessBuilder(cmd)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        // Drain stdout (stderr merged via redirectErrorStream) to completion BEFORE
        // waitFor — never swap the order: a full pipe with no reader would deadlock
        // the child against waitFor.
        String output;
        try (var in = proc.getInputStream()) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String summary = "git " + String.join(" ", args);
        boolean done;
        try {
            done = proc.waitFor(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new AcpException(summary + " interrupted");
        }
        if (!done) {
            proc.destroyForcibly();
            throw new AcpException(summary + " timed out");
        }
        if (proc.exitValue() != 0) {
            // Full git output (may contain absolute paths / credential-helper noise) goes
            // to the server log ONLY; the thrown message — which reaches the session owner
            // via runtime_error broadcast — carries just the command + exit code.
            log.warn("ACP {} failed (exit {}): {}", summary, proc.exitValue(), output.strip());
            throw new AcpException(summary + " failed (exit " + proc.exitValue() + ")");
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
