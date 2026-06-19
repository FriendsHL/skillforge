package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    public AcpAgentRunner(AcpClientFactory clientFactory,
                          SessionService sessionService,
                          AgentRepository agentRepository,
                          ChatEventBroadcaster broadcaster,
                          ObjectMapper objectMapper,
                          AcpRunnerProperties properties) {
        this.clientFactory = clientFactory;
        this.sessionService = sessionService;
        this.agentRepository = agentRepository;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Run a cc prompt as a sub-session and stream it live. Blocks until the cc
     * prompt completes (or the deadline / an error fires), then returns the
     * created sub-session id so the caller (the P1a-2 trigger endpoint) can hand
     * it back for the dashboard to open.
     *
     * @param prompt the user prompt to send to cc (required, non-blank)
     * @param model  optional cc model id (from {@code session/new} models); null ⇒ cc default
     * @return the created SkillForge sub-session id
     */
    public String run(String prompt, String model) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        SessionEntity session = createRunSession();
        String subSessionId = session.getId();

        Path cwd = null;
        AcpClient client = null;
        // W-5: assistantText is WRITTEN on the cc transport reader thread (via the
        // update listener) and READ on this caller thread only AFTER promptFuture.get()
        // returns. CompletableFuture.get() establishes a happens-before edge between the
        // reader thread's last write (which precedes the future completion) and this
        // thread's read, so no extra synchronization is needed. (Reads in the error/
        // timeout path don't touch assistantText.)
        StringBuilder assistantText = new StringBuilder();
        // W-2: ensure assistantStreamEnd fires at most once per run, even if finishOk
        // partially succeeds (stream-end emitted) then throws → outer catch → finishError.
        AtomicBoolean streamEnded = new AtomicBoolean(false);
        try {
            // W-1: markRunning is INSIDE the try so a saveSession/broadcast failure here
            // routes to finishError (session never stuck "running" with no error status).
            markRunning(session);

            cwd = createWorkingDir();
            client = clientFactory.create(cwd.toString(), Map.of());

            // Accumulate + live-stream. Runs on the cc reader thread — keep it non-blocking.
            client.setUpdateListener(u -> onUpdate(subSessionId, u, assistantText));
            client.start();

            client.initialize().get(handshakeTimeout(), TimeUnit.SECONDS);

            JsonNode newSessionResult = client.newSession(cwd.toString(), List.of())
                    .get(handshakeTimeout(), TimeUnit.SECONDS);
            String ccSessionId = requireCcSessionId(newSessionResult);

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
            finishOk(subSessionId, assistantText.toString(), stopReason, streamEnded);
            return subSessionId;
        } catch (Exception e) {
            log.error("ACP run failed for sub-session {}: {}", subSessionId, e.toString(), e);
            finishError(subSessionId, e, streamEnded);
            throw new AcpException("ACP run failed: " + e.getMessage(), e);
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

    // ───────────────────────────── session lifecycle ─────────────────────────────

    private SessionEntity createRunSession() {
        Long agentId = resolveAgentId();
        SessionEntity session = sessionService.createSession(properties.getUserId(), agentId);
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

    private void finishOk(String subSessionId, String fullText, String stopReason, AtomicBoolean streamEnded) {
        Message assistantMsg = Message.assistant(fullText);
        // Option A: persist via the normal append path (no hand-written rows).
        // W-2: emit assistantStreamEnd only AFTER persistence succeeds — if append throws,
        // the outer catch → finishError handles stream-end (guarded), so it fires once.
        sessionService.appendNormalMessages(subSessionId, List.of(assistantMsg));

        emitStreamEndOnce(subSessionId, streamEnded);
        broadcaster.messageAppended(subSessionId, null, assistantMsg);

        SessionEntity session = sessionService.getSession(subSessionId);
        session.setRuntimeStatus("idle");
        session.setRuntimeStep(null);
        session.setRuntimeError(null);
        sessionService.saveSession(session);
        broadcaster.sessionStatus(subSessionId, "idle", null, null);
        log.info("ACP run completed (sub-session {}, stopReason={}, chars={})",
                subSessionId, stopReason, fullText.length());
    }

    private void finishError(String subSessionId, Exception e, AtomicBoolean streamEnded) {
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
    private void onUpdate(String subSessionId, AcpSessionUpdate u, StringBuilder assistantText) {
        AcpUpdate update = u.update();
        if (update instanceof AcpUpdate.TextChunk text) {
            assistantText.append(text.text());
            broadcaster.assistantDelta(subSessionId, text.text());
        } else if (update instanceof AcpUpdate.ThoughtChunk thought) {
            broadcaster.reasoningDelta(subSessionId, thought.text());
        } else {
            // tool_call / tool_call_update / plan / available_commands / mode / unknown:
            // P1b handles tool rendering; for P1a-2 just log so we never crash on them.
            log.debug("ACP update ignored in P1a-2 (sub-session {}): kind={}", subSessionId, update.rawKind());
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private String requireCcSessionId(JsonNode newSessionResult) {
        if (newSessionResult == null || !newSessionResult.hasNonNull("sessionId")) {
            throw new AcpException("session/new did not return a sessionId");
        }
        return newSessionResult.get("sessionId").asText();
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
