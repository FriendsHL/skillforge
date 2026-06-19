package com.skillforge.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.Message;
import com.skillforge.server.channel.event.ChannelSessionOutputEvent;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ws/chat/{sessionId} 端点处理器。维护 sessionId -> WebSocketSession 集合,
 * 同时实现 ChatEventBroadcaster 接口供 AgentLoopEngine 回调推送事件。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler implements ChatEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /** Per-session buffered reply text for channel-originated turns. */
    private final Map<String, ChannelTurnContext> channelContexts = new ConcurrentHashMap<>();

    /**
     * 3-state ownership marker for channel-turn delivery dedup (CHANNEL-ASYNC-DELIVERY).
     * Read-and-removed atomically by {@code ChannelAsyncDeliveryListener} on
     * {@code SessionLoopFinishedEvent} to decide whether the inbound path already owns
     * (delivered, or decided not to deliver) this turn:
     * <ul>
     *   <li><b>absent</b> (no entry) — no inbound turn registered for this loop:
     *       async-resumed loop (SubAgent / Team / scheduled / startup recovery) or a
     *       non-channel session. The async listener takes ownership and delivers.</li>
     *   <li><b>false</b> — inbound turn registered but the inbound path did NOT publish a
     *       delivery (loop still running, or finished blank / error / waiting_user).
     *       {@code sessionStatus} leaves it {@code false} on blank/error — it does NOT
     *       remove the marker — so the async listener sees a non-null marker → skips. This
     *       deliberately does NOT depend on {@code event.finalMessage} ≡ {@code ctx.finalText}
     *       (two independent accumulators: ChatService loop result vs WS delta buffer). An
     *       inbound turn is owned by the inbound path regardless of which accumulator ends
     *       up blank — e.g. a stream interrupted mid-flight could leave {@code ctx.finalText}
     *       blank while {@code event.finalMessage} is non-blank; leaving the marker
     *       {@code false} guarantees the async path still skips it.</li>
     *   <li><b>true</b> — inbound turn registered AND the inbound path published
     *       {@link ChannelSessionOutputEvent} (delivery already in flight). The async
     *       listener sees a non-null marker → skips (no double delivery).</li>
     * </ul>
     * The invariant: the async listener delivers iff {@code removeChannelTurnHandled}
     * returns {@code null}. Any inbound {@code registerChannelTurn} leaves the key present
     * (false or true); {@code sessionStatus} never removes it. The ONLY remover is the
     * async listener itself, which fires every loop via {@code SessionLoopFinishedEvent} —
     * so the entry is always cleaned exactly once per loop (no leak). ConcurrentHashMap
     * provides the happens-before edge; same-session loop serialization (compaction stripe
     * lock) means no two loops for one session race here.
     */
    private final Map<String, Boolean> channelTurnHandled = new ConcurrentHashMap<>();

    /**
     * Tracks a channel turn across potentially multiple LLM streams.
     * {@code currentText} accumulates deltas for the in-progress stream.
     * {@code finalText} snapshots the last completed stream so that
     * {@code sessionStatus("idle")} can publish the full final answer.
     * {@code ackReactionId} holds the typing-indicator reaction ID to remove before replying.
     */
    private static final class ChannelTurnContext {
        final String platformMessageId;
        final String ackReactionId; // nullable
        /** Platform sender id (feishu open_id) — used by install confirmation for multi-user auth. */
        final String triggererOpenId; // nullable
        final StringBuilder currentText = new StringBuilder();
        volatile String finalText = null;

        ChannelTurnContext(String platformMessageId, String ackReactionId, String triggererOpenId) {
            this.platformMessageId = platformMessageId;
            this.ackReactionId = ackReactionId;
            this.triggererOpenId = triggererOpenId;
        }
    }

    // findAndRegisterModules() picks up jackson-datatype-jsr310 (already on classpath via
    // spring-boot-starter-web) so LocalDateTime / Instant in payloads serialize cleanly.
    // disable WRITE_DATES_AS_TIMESTAMPS so the client gets ISO-8601 strings.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final UserWebSocketHandler userWebSocketHandler;
    private final CollabRunRepository collabRunRepository;
    private final SessionRepository sessionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ChatWebSocketHandler(UserWebSocketHandler userWebSocketHandler,
                                CollabRunRepository collabRunRepository,
                                SessionRepository sessionRepository,
                                ApplicationEventPublisher eventPublisher) {
        this.userWebSocketHandler = userWebSocketHandler;
        this.collabRunRepository = collabRunRepository;
        this.sessionRepository = sessionRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Called by {@link com.skillforge.server.channel.router.ChannelSessionRouter} before
     * each channel-originated turn. Captures the inbound platformMessageId so that when
     * {@link #assistantStreamEnd} fires we can emit a {@link ChannelSessionOutputEvent}
     * carrying the accumulated reply text back to the message gateway.
     */
    public void registerChannelTurn(String sessionId, String platformMessageId, String ackReactionId) {
        registerChannelTurn(sessionId, platformMessageId, ackReactionId, null);
    }

    /**
     * Extended overload that also captures the platform sender id (feishu open_id). Used by
     * install confirmation flow for multi-user chat authorization (only the triggerer can
     * approve their own install request).
     */
    public void registerChannelTurn(String sessionId, String platformMessageId, String ackReactionId,
                                    String triggererOpenId) {
        channelContexts.put(sessionId, new ChannelTurnContext(platformMessageId, ackReactionId, triggererOpenId));
        // Mark this turn as inbound-owned (not yet delivered). Transitions to true ONLY
        // when sessionStatus("idle") actually publishes the output event; on blank/error
        // it stays false. Either way the entry stays present (never removed by
        // sessionStatus) so the async delivery listener knows the inbound path owns this
        // turn (dedup). The async listener removes it. See channelTurnHandled javadoc.
        channelTurnHandled.put(sessionId, false);
    }

    /**
     * Atomically read-and-remove the channel-turn ownership marker (CHANNEL-ASYNC-DELIVERY).
     * Called by {@code ChannelAsyncDeliveryListener} on loop finish: {@code null} return
     * means no inbound turn was registered (async-resumed / non-channel session) → the
     * async listener should deliver; non-null means the inbound path already owns the turn
     * (delivered when {@code true}, or chose not to when {@code false}) → skip.
     */
    public Boolean removeChannelTurnHandled(String sessionId) {
        return channelTurnHandled.remove(sessionId);
    }

    /**
     * Returns the platform sender id (feishu open_id) for the current per-session channel turn,
     * or {@code null} if this session is not in a channel-originated turn.
     */
    public String getCurrentTriggererOpenId(String sessionId) {
        ChannelTurnContext ctx = channelContexts.get(sessionId);
        return ctx != null ? ctx.triggererOpenId : null;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = extractSessionId(session);
        if (sessionId == null) {
            log.warn("WebSocket connection without sessionId, closing: {}", session.getUri());
            try {
                session.close(CloseStatus.BAD_DATA);
            } catch (IOException ignored) {
            }
            return;
        }
        sessions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WS client connected: sessionId={}, total={}", sessionId, sessions.get(sessionId).size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = extractSessionId(session);
        if (sessionId != null) {
            Set<WebSocketSession> set = sessions.get(sessionId);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    sessions.remove(sessionId);
                }
            }
        }
        log.info("WS client disconnected: sessionId={}, status={}", sessionId, status);
    }

    private String extractSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) return null;
        return path.substring(idx + 1);
    }

    private void broadcast(String sessionId, Map<String, Object> payload) {
        Set<WebSocketSession> set = sessions.get(sessionId);
        if (set == null || set.isEmpty()) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize WS payload: {}", e.getMessage());
            return;
        }
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : set) {
            if (!s.isOpen()) continue;
            try {
                synchronized (s) {
                    s.sendMessage(msg);
                }
            } catch (Exception e) {
                log.warn("Failed to send WS message to session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    // ==== ChatEventBroadcaster ====

    @Override
    public void sessionStatus(String sessionId, String status, String step, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "session_status");
        payload.put("sessionId", sessionId);
        payload.put("status", status);
        payload.put("step", step);
        payload.put("error", error);
        broadcast(sessionId, payload);

        if ("idle".equals(status) || "error".equals(status)) {
            ChannelTurnContext ctx = channelContexts.remove(sessionId);
            if (ctx != null) {
                String replyText = "idle".equals(status) ? ctx.finalText : null;
                if (replyText != null && !replyText.isBlank()) {
                    eventPublisher.publishEvent(new ChannelSessionOutputEvent(
                            sessionId, ctx.platformMessageId, ctx.ackReactionId, replyText));
                    // CHANNEL-ASYNC-DELIVERY: inbound path published delivery for this turn.
                    // Mark true so the async delivery listener (fired later from the loop
                    // teardown finally block, after this sessionStatus call) sees a non-null
                    // marker → skips → no double send. See channelTurnHandled javadoc.
                    channelTurnHandled.put(sessionId, true);
                }
                // CHANNEL-ASYNC-DELIVERY: idle+blank or error with an inbound ctx present →
                // inbound path delivers nothing, but we MUST NOT remove the channelTurnHandled
                // marker here. It was set false by registerChannelTurn and stays false so the
                // async listener (which gets null only for genuinely never-registered turns)
                // sees a non-null marker → skips. Removing it would make the async path
                // unable to distinguish "inbound registered but didn't deliver" from "async
                // never registered" and could wrongly deliver an inbound turn via the async
                // path when ctx.finalText is blank but event.finalMessage is not (two
                // independent accumulators). The async listener removes the marker every
                // loop, so leaving it here does not leak. (channelContexts above IS removed —
                // that's the separate per-turn WS context, unrelated to dedup ownership.)
            }
        }
    }

    @Override
    public void messageAppended(String sessionId, String traceId, Message message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message_appended");
        payload.put("sessionId", sessionId);
        if (traceId != null) {
            payload.put("traceId", traceId);
        }
        payload.put("message", message);
        // Envelope-level broadcast timestamp (Instant.now() at push time, NOT the row's
        // t_session_message.created_at). In practice this is sub-ms after the JPA row
        // insert and seconds-granularity FE display treats it as ≈ entity createdAt.
        // Per-message authoritative row createdAt is available via the REST
        // /api/chat/sessions/{id}/messages history endpoint. ISO-8601 serialization via
        // findAndRegisterModules() above.
        payload.put("createdAt", Instant.now());
        // CHAT-REASONING-PANEL hotfix: surface reasoningContent at envelope-level so FE
        // doesn't have to dig into Message body shape (which serializes inconsistently
        // across String / array-content paths). Mirrors REST SessionMessageDto.reasoningContent.
        if (message != null && message.getReasoningContent() != null && !message.getReasoningContent().isEmpty()) {
            payload.put("reasoningContent", message.getReasoningContent());
        }
        broadcast(sessionId, payload);

        // CHANNEL-MIDTURN-PROGRESS: additively publish an assistant-turn event so a
        // channel-bound session can push mid-turn narration to its channel. WS behavior
        // above is UNCHANGED; this is a pure side-channel. Cheap role guard keeps the
        // event bus quiet for user/tool_result turns (those also flow through
        // messageAppended for the WS). Non-channel sessions have no listener effect.
        if (message != null && message.getRole() == Message.Role.ASSISTANT) {
            eventPublisher.publishEvent(
                    new com.skillforge.server.service.event.AssistantTurnAppendedEvent(
                            sessionId, traceId, message));
        }
    }

    @Override
    public void messagesSnapshot(String sessionId, List<Message> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "messages_snapshot");
        payload.put("sessionId", sessionId);
        payload.put("messages", messages);
        // Envelope-level broadcast timestamp (Instant.now() at push time, NOT a per-row
        // t_session_message.created_at). Per-message authoritative createdAt is not
        // carried on the engine in-memory Message list; FE relies on the REST
        // /api/chat/sessions/{id}/messages history endpoint for accurate per-row
        // timestamps when needed.
        payload.put("createdAt", Instant.now());
        broadcast(sessionId, payload);
    }

    @Override
    public void confirmationRequired(String sessionId,
            com.skillforge.core.engine.confirm.ConfirmationPromptPayload payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "confirmation_required");
        out.put("sessionId", sessionId);
        out.put("payload", payload);
        broadcast(sessionId, out);
    }

    @Override
    public void askUser(String sessionId, AskUserEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ask_user");
        payload.put("sessionId", sessionId);
        payload.put("askId", event.askId);
        payload.put("question", event.question);
        payload.put("context", event.context);
        payload.put("options", event.options);
        payload.put("allowOther", event.allowOther);
        broadcast(sessionId, payload);
    }

    @Override
    public void toolStarted(String sessionId, String toolUseId, String name, Map<String, Object> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_started");
        payload.put("sessionId", sessionId);
        payload.put("toolUseId", toolUseId);
        payload.put("name", name);
        payload.put("input", input);
        broadcast(sessionId, payload);
    }

    @Override
    public void toolFinished(String sessionId, String toolUseId, String status, long durationMs, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_finished");
        payload.put("sessionId", sessionId);
        payload.put("toolUseId", toolUseId);
        payload.put("status", status);
        payload.put("durationMs", durationMs);
        payload.put("error", error);
        broadcast(sessionId, payload);
    }

    @Override
    public void assistantDelta(String sessionId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "assistant_delta");
        payload.put("sessionId", sessionId);
        payload.put("text", text);
        broadcast(sessionId, payload);

        ChannelTurnContext ctx = channelContexts.get(sessionId);
        if (ctx != null && text != null) {
            synchronized (ctx.currentText) {
                ctx.currentText.append(text);
            }
        }
    }

    @Override
    public void assistantStreamEnd(String sessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "assistant_stream_end");
        payload.put("sessionId", sessionId);
        broadcast(sessionId, payload);

        // Snapshot the completed stream's text and reset the accumulator.
        // The channel event is emitted from sessionStatus("idle") so that the reply
        // contains the full final answer, not just an intermediate planning step.
        ChannelTurnContext ctx = channelContexts.get(sessionId);
        if (ctx != null) {
            synchronized (ctx.currentText) {
                ctx.finalText = ctx.currentText.toString();
                ctx.currentText.setLength(0);
            }
        }
    }

    @Override
    public void userEvent(Long userId, Map<String, Object> payload) {
        if (userWebSocketHandler != null) {
            userWebSocketHandler.broadcast(userId, payload);
        }
    }

    @Override
    public void sessionTitleUpdated(String sessionId, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "session_title_updated");
        payload.put("sessionId", sessionId);
        payload.put("title", title);
        broadcast(sessionId, payload);
    }

    @Override
    public void textDelta(String sessionId, String delta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "text_delta");
        payload.put("sessionId", sessionId);
        payload.put("delta", delta);
        broadcast(sessionId, payload);
    }

    @Override
    public void reasoningDelta(String sessionId, String delta) {
        // CHAT-REASONING-PANEL: WS event parallel to text_delta. Payload shape mirrors
        // text_delta exactly ({type, sessionId, delta}) so FE handler stays symmetric.
        // Older FE clients without a reasoning_delta case silently ignore unknown types.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "reasoning_delta");
        payload.put("sessionId", sessionId);
        payload.put("delta", delta);
        broadcast(sessionId, payload);
    }

    @Override
    public void toolUseDelta(String sessionId, String toolUseId, String toolName, String jsonFragment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_use_delta");
        payload.put("sessionId", sessionId);
        payload.put("toolUseId", toolUseId);
        payload.put("toolName", toolName);
        payload.put("jsonFragment", jsonFragment);
        broadcast(sessionId, payload);
    }

    @Override
    public void toolUseComplete(String sessionId, String toolUseId, Map<String, Object> parsedInput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_use_complete");
        payload.put("sessionId", sessionId);
        payload.put("toolUseId", toolUseId);
        payload.put("input", parsedInput);
        broadcast(sessionId, payload);
    }

    // ==== Multi-agent collaboration events ====

    /** Look up the userId of the leader of a collab run. Returns null on any miss. */
    private Long lookupCollabUserId(String collabRunId) {
        try {
            return collabRunRepository.findById(collabRunId)
                    .map(CollabRunEntity::getLeaderSessionId)
                    .flatMap(sessionRepository::findById)
                    .map(SessionEntity::getUserId)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("lookupCollabUserId failed for collabRunId={}: {}", collabRunId, e.getMessage());
            return null;
        }
    }

    @Override
    public void collabMemberSpawned(String collabRunId, String handle, String sessionId, String agentName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "collab_member_spawned");
        payload.put("collabRunId", collabRunId);
        payload.put("handle", handle);
        payload.put("sessionId", sessionId);
        payload.put("agentName", agentName);
        // Broadcast to the child session's chat channel
        broadcast(sessionId, payload);
        // Also broadcast to the leader's user-level channel (Teams page)
        Long userId = lookupCollabUserId(collabRunId);
        if (userId != null) userEvent(userId, payload);
    }

    @Override
    public void collabMemberFinished(String collabRunId, String handle, String status, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "collab_member_finished");
        payload.put("collabRunId", collabRunId);
        payload.put("handle", handle);
        payload.put("status", status);
        payload.put("summary", summary);
        Long userId = lookupCollabUserId(collabRunId);
        if (userId != null) {
            userEvent(userId, payload);
        } else {
            log.debug("collabMemberFinished: userId not found for collabRunId={}", collabRunId);
        }
    }

    @Override
    public void collabRunStatus(String collabRunId, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "collab_run_status");
        payload.put("collabRunId", collabRunId);
        payload.put("status", status);
        Long userId = lookupCollabUserId(collabRunId);
        if (userId != null) {
            userEvent(userId, payload);
        } else {
            log.debug("collabRunStatus: userId not found for collabRunId={}", collabRunId);
        }
    }

    @Override
    public void collabMessageRouted(String collabRunId, String fromHandle, String toHandle, String messageId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "collab_message_routed");
        payload.put("collabRunId", collabRunId);
        payload.put("fromHandle", fromHandle);
        payload.put("toHandle", toHandle);
        payload.put("messageId", messageId);
        Long userId = lookupCollabUserId(collabRunId);
        if (userId != null) {
            userEvent(userId, payload);
        } else {
            log.debug("collabMessageRouted: userId not found for collabRunId={}", collabRunId);
        }
    }
}
