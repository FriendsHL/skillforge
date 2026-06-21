package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.confirm.ConfirmationPromptPayload;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bridges an ACP {@code session/request_permission} request to a SkillForge
 * human-confirmation (ACP-EXTERNAL-AGENT P1b, AC-3).
 *
 * <p>On each permission request it:
 * <ol>
 *   <li>parses the ACP {@code options[]} ({@code allow_always}/{@code allow_once}
 *       → APPROVE choices, {@code reject_once}/{@code reject_always} → DENY) and
 *       remembers each option's {@code optionId};</li>
 *   <li>registers a {@link PendingConfirmation} in the shared
 *       {@link PendingConfirmationRegistry} (the same cross-thread latch the
 *       engine's install-confirmation flow uses) and broadcasts a
 *       {@link ChatEventBroadcaster#confirmationRequired} WS event so the dashboard
 *       renders its EXISTING confirmation card (no new UI/channel);</li>
 *   <li>hands the wait off to a worker thread (NEVER the cc reader thread, J-W3):
 *       the worker blocks on the latch, then maps the {@link Decision} back to an
 *       ACP outcome via the idempotent {@link AcpResponder} — APPROVE → the chosen
 *       allow {@code optionId}, DENY/TIMEOUT → cancelled.</li>
 * </ol>
 *
 * <p>The answer arrives from the dashboard via the ACP confirmation endpoint, which
 * calls {@link PendingConfirmationRegistry#complete}. The ACP run sub-session is a
 * RECORD (not engine-driven), so the answer is NOT routed through
 * {@code ChatService.answerConfirmation} (which resumes an engine loop) — only the
 * registry + payload + WS broadcast are reused.
 */
public class AcpPermissionBridge {

    private static final Logger log = LoggerFactory.getLogger(AcpPermissionBridge.class);

    /** ACP option kinds that approve. */
    private static final String KIND_ALLOW_ALWAYS = "allow_always";
    private static final String KIND_ALLOW_ONCE = "allow_once";
    /** Decision wire values used by the dashboard confirmation card. */
    static final String CHOICE_APPROVE = "approved";
    static final String CHOICE_DENY = "denied";

    private final PendingConfirmationRegistry registry;
    private final ChatEventBroadcaster broadcaster;
    private final ExecutorService waitExecutor;
    private final long timeoutSeconds;

    public AcpPermissionBridge(PendingConfirmationRegistry registry,
                               ChatEventBroadcaster broadcaster,
                               ExecutorService waitExecutor,
                               long timeoutSeconds) {
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.waitExecutor = waitExecutor;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 300;
    }

    /**
     * Handle one permission request. Invoked ON THE cc READER THREAD — returns
     * immediately after registering + broadcasting; the latch wait runs on
     * {@link #waitExecutor}.
     *
     * @param subSessionId the SkillForge sub-session this run renders into
     * @param req          the ACP server request ({@code session/request_permission})
     * @param responder    idempotent reply handle (first completion wins)
     */
    public void handlePermissionRequest(String subSessionId, AcpServerRequest req, AcpResponder responder) {
        if (!responder.isPermissionRequest()) {
            // Not a permission request — let the responder's deny route to a proper error.
            responder.deny();
            return;
        }
        JsonNode params = req.params();
        JsonNode toolCall = params != null ? params.get("toolCall") : null;

        // Map ACP options → confirmation choices, capturing each optionId.
        List<ConfirmationPromptPayload.ConfirmationChoice> choices = new ArrayList<>();
        String allowOptionId = null;
        String rejectOptionId = null;
        JsonNode options = params != null ? params.get("options") : null;
        if (options != null && options.isArray()) {
            for (JsonNode opt : options) {
                String kind = text(opt, "kind");
                String optionId = text(opt, "optionId");
                String name = text(opt, "name");
                if (kind == null || optionId == null) {
                    continue;
                }
                boolean isAllow = KIND_ALLOW_ALWAYS.equals(kind) || KIND_ALLOW_ONCE.equals(kind);
                if (isAllow && allowOptionId == null) {
                    allowOptionId = optionId; // first allow option = the one we select on APPROVE
                } else if (!isAllow && rejectOptionId == null) {
                    rejectOptionId = optionId;
                }
                choices.add(new ConfirmationPromptPayload.ConfirmationChoice(
                        isAllow ? CHOICE_APPROVE : CHOICE_DENY,
                        name != null ? name : (isAllow ? "Allow" : "Reject"),
                        isAllow ? "primary" : "danger"));
            }
        }
        if (allowOptionId == null && rejectOptionId == null) {
            // No usable options to present — fail closed (cancel) so cc does not hang.
            log.warn("ACP permission request with no usable options (sub-session {}); cancelling", subSessionId);
            responder.cancelPermission();
            return;
        }

        String confirmationId = UUID.randomUUID().toString();
        String toolCallId = text(toolCall, "toolCallId");
        String title = text(toolCall, "title");
        String kindLabel = text(toolCall, "kind");
        String commandPreview = previewOf(toolCall);

        PendingConfirmation pc = new PendingConfirmation(
                confirmationId, subSessionId, toolCallId,
                /* installTool */ kindLabel != null ? kindLabel : "tool",
                /* installTarget */ title != null ? title : "",
                commandPreview,
                /* triggererOpenId */ null,
                timeoutSeconds);
        registry.register(pc);

        ConfirmationPromptPayload payload = new ConfirmationPromptPayload(
                confirmationId, subSessionId,
                kindLabel != null ? kindLabel : "tool",
                title != null ? title : "",
                commandPreview,
                title != null ? title : "External agent requests permission",
                "Claude Code is requesting permission to run this operation.",
                choices,
                Instant.now().plusSeconds(timeoutSeconds));
        if (broadcaster != null) {
            broadcaster.confirmationRequired(subSessionId, payload);
        }

        final String approveOptionId = allowOptionId != null ? allowOptionId : null;
        final String denyOptionId = rejectOptionId != null ? rejectOptionId : null;
        log.info("ACP permission request bridged (sub-session {}, confirmationId {}, toolCallId {})",
                subSessionId, confirmationId, toolCallId);

        // Hand the blocking latch wait off the reader thread (J-W3).
        try {
            waitExecutor.execute(() -> awaitAndRespond(
                    subSessionId, confirmationId, approveOptionId, denyOptionId, responder));
        } catch (RejectedExecutionException ree) {
            // security-W3: the bounded pool is saturated → fail closed. Clean up the
            // registry entry and cancel the cc permission inline (on the reader thread,
            // which is non-blocking here) so the session does not hang.
            log.warn("ACP permission wait pool saturated (sub-session {}, confirmationId {}); cancelling",
                    subSessionId, confirmationId);
            registry.removeIfPresent(confirmationId);
            dispatchSafely(subSessionId, confirmationId, responder::cancelPermission);
        }
    }

    private void awaitAndRespond(String subSessionId, String confirmationId,
                                 String approveOptionId, String denyOptionId,
                                 AcpResponder responder) {
        Decision decision;
        try {
            decision = registry.await(confirmationId, timeoutSeconds);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("ACP permission wait interrupted (sub-session {}, confirmationId {})",
                    subSessionId, confirmationId);
            // java-W1: dispatch may throw if the transport already closed — never let it
            // escape into the executor (silently swallowed there).
            dispatchSafely(subSessionId, confirmationId, responder::cancelPermission);
            registry.removeIfPresent(confirmationId);
            return;
        }
        try {
            if (decision == Decision.APPROVED && approveOptionId != null) {
                dispatchSafely(subSessionId, confirmationId,
                        () -> responder.selectPermissionOption(approveOptionId));
                log.info("ACP permission APPROVED (sub-session {}, confirmationId {}, optionId {})",
                        subSessionId, confirmationId, approveOptionId);
            } else if (decision == Decision.DENIED && denyOptionId != null) {
                // Reject by selecting the reject optionId (ACP "selected" with reject id),
                // matching the spike-captured reject path; falls back to cancelled below.
                dispatchSafely(subSessionId, confirmationId,
                        () -> responder.selectPermissionOption(denyOptionId));
                log.info("ACP permission DENIED (sub-session {}, confirmationId {})",
                        subSessionId, confirmationId);
            } else {
                // TIMEOUT, or APPROVE/DENY without a matching option id → cancelled.
                dispatchSafely(subSessionId, confirmationId, responder::cancelPermission);
                log.info("ACP permission {} → cancelled (sub-session {}, confirmationId {})",
                        decision, subSessionId, confirmationId);
            }
        } finally {
            registry.removeIfPresent(confirmationId);
        }
    }

    /**
     * java-W1: run a responder dispatch, swallowing+logging any RuntimeException.
     * The responder writes to the ACP transport, which may already be closed (the
     * run finished / errored) by the time a human answers — that throws
     * {@code AcpException}; without this guard it escapes onto the wait-executor
     * thread and is silently dropped. The confirmation is harmless to drop at that
     * point (the cc session is gone), so log at warn and move on.
     */
    private void dispatchSafely(String subSessionId, String confirmationId, Runnable dispatch) {
        try {
            dispatch.run();
        } catch (RuntimeException e) {
            log.warn("ACP permission response dispatch failed (sub-session {}, confirmationId {}): {}",
                    subSessionId, confirmationId, e.toString());
        }
    }

    private static String previewOf(JsonNode toolCall) {
        if (toolCall == null) {
            return "";
        }
        JsonNode rawInput = toolCall.get("rawInput");
        if (rawInput != null && !rawInput.isNull()) {
            String s = rawInput.toString();
            return s.length() <= 500 ? s : s.substring(0, 500) + "…";
        }
        String title = text(toolCall, "title");
        return title != null ? title : "";
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }
}
