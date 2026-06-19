package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AcpAgentRunner} — driven by an in-memory
 * {@link FakeAcpTransport} via an injected {@link AcpClientFactory}. NO real cc
 * subprocess, NO network. cc's streamed {@code session/update} is simulated by
 * emitting canned JSON-RPC lines on a background thread once the runner has sent
 * its {@code session/prompt}.
 */
class AcpAgentRunnerTest {

    private static final String SUB_SESSION_ID = "sub-session-1";
    private static final long AGENT_ID = 7L;

    private ObjectMapper mapper;
    private SessionService sessionService;
    private AgentRepository agentRepository;
    private ChatEventBroadcaster broadcaster;
    private AcpRunnerProperties properties;
    private ExecutorService driverPool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        sessionService = mock(SessionService.class);
        agentRepository = mock(AgentRepository.class);
        broadcaster = mock(ChatEventBroadcaster.class);
        properties = new AcpRunnerProperties();
        properties.setPromptTimeoutSeconds(10);
        driverPool = Executors.newSingleThreadExecutor();

        // createSession returns a viewable sub-session; getSession echoes it back.
        SessionEntity session = new SessionEntity();
        session.setId(SUB_SESSION_ID);
        session.setUserId(1L);
        when(sessionService.createSession(any(), any())).thenReturn(session);
        when(sessionService.saveSession(any())).thenAnswer(i -> i.getArgument(0));
        when(sessionService.getSession(SUB_SESSION_ID)).thenReturn(session);

        // resolveAgentId: no configured agent → falls back to first available.
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        when(agentRepository.findAll()).thenReturn(List.of(agent));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        driverPool.shutdownNow();
    }

    /**
     * Build a runner whose factory hands out an AcpClient backed by {@code transport}.
     * The transport is captured so the test can drive canned inbound lines.
     */
    private AcpAgentRunner runnerWith(FakeAcpTransport transport) {
        AcpClientFactory factory = (cwd, env) ->
                new AcpClient(transport, mapper, new CcAcpUpdateTranslator());
        return new AcpAgentRunner(factory, sessionService, agentRepository,
                broadcaster, mapper, properties);
    }

    /**
     * Wire the transport so that as soon as the runner sends a {@code session/prompt}
     * (the 3rd request: initialize, session/new, then prompt — set_model skipped when
     * no model), the canned responses + update stream are emitted on a background
     * thread. Handshake responses (initialize, session/new) are answered immediately.
     */
    private FakeAcpTransport scriptedTransport(List<String> textChunks) {
        return new FakeAcpTransport() {
            @Override
            public void send(String jsonLine) {
                super.send(jsonLine);
                JsonNode msg;
                try {
                    msg = mapper.readTree(jsonLine);
                } catch (Exception e) {
                    return;
                }
                String method = msg.path("method").asText("");
                long id = msg.path("id").asLong(-1);
                if ("initialize".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":1}}");
                } else if ("session/new".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"result\":{\"sessionId\":\"cc-sess-1\"}}");
                } else if ("session/prompt".equals(method)) {
                    // Drive the update stream + final result on a background thread so
                    // the calling thread (blocked on the prompt future) can complete.
                    final long promptId = id;
                    driverPool.execute(() -> streamPrompt(this, promptId, textChunks));
                }
            }
        };
    }

    private void streamPrompt(FakeAcpTransport t, long promptId, List<String> textChunks) {
        for (String chunk : textChunks) {
            t.emit("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{"
                    + "\"sessionId\":\"cc-sess-1\",\"update\":{"
                    + "\"sessionUpdate\":\"agent_message_chunk\","
                    + "\"content\":{\"type\":\"text\",\"text\":\"" + chunk + "\"}}}}");
        }
        t.emit("{\"jsonrpc\":\"2.0\",\"id\":" + promptId
                + ",\"result\":{\"stopReason\":\"end_turn\"}}");
    }

    @Test
    @DisplayName("happy path: creates sub-session, streams each chunk, persists final assistant msg, idle")
    void run_happyPath() {
        FakeAcpTransport transport = scriptedTransport(List.of("Hello", ", ", "world"));
        AcpAgentRunner runner = runnerWith(transport);

        String subSessionId = runner.run("say hi", null);

        assertThat(subSessionId).isEqualTo(SUB_SESSION_ID);

        // sub-session created.
        verify(sessionService).createSession(eq(1L), eq(AGENT_ID));

        // each chunk broadcast live, in order.
        ArgumentCaptor<String> delta = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, atLeastOnce()).assistantDelta(eq(SUB_SESSION_ID), delta.capture());
        assertThat(delta.getAllValues()).containsExactly("Hello", ", ", "world");

        // final assistant message persisted via the normal append path (Option A).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> persisted = ArgumentCaptor.forClass(List.class);
        verify(sessionService).appendNormalMessages(eq(SUB_SESSION_ID), persisted.capture());
        Message msg = persisted.getValue().get(0);
        assertThat(msg.getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(textOf(msg)).isEqualTo("Hello, world");

        // runtime_status: running → idle (broadcast both).
        verify(broadcaster).sessionStatus(SUB_SESSION_ID, "running", "ACP cc", null);
        verify(broadcaster).sessionStatus(SUB_SESSION_ID, "idle", null, null);
        verify(broadcaster).assistantStreamEnd(SUB_SESSION_ID);
        verify(broadcaster).messageAppended(eq(SUB_SESSION_ID), isNull(), any(Message.class));
        verify(broadcaster, never()).sessionStatus(eq(SUB_SESSION_ID), eq("error"), any(), any());

        // process/client closed (transport closed in finally).
        assertThat(transport.closed).isTrue();
    }

    @Test
    @DisplayName("thought chunk streams to reasoningDelta, not assistantDelta")
    void run_reasoningChunk() {
        FakeAcpTransport transport = new FakeAcpTransport() {
            @Override
            public void send(String jsonLine) {
                super.send(jsonLine);
                JsonNode msg;
                try {
                    msg = mapper.readTree(jsonLine);
                } catch (Exception e) {
                    return;
                }
                String method = msg.path("method").asText("");
                long id = msg.path("id").asLong(-1);
                if ("initialize".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":1}}");
                } else if ("session/new".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"sessionId\":\"cc-s\"}}");
                } else if ("session/prompt".equals(method)) {
                    final long pid = id;
                    driverPool.execute(() -> {
                        emit("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{"
                                + "\"sessionId\":\"cc-s\",\"update\":{"
                                + "\"sessionUpdate\":\"agent_thought_chunk\","
                                + "\"content\":{\"type\":\"text\",\"text\":\"thinking\"}}}}");
                        emit("{\"jsonrpc\":\"2.0\",\"id\":" + pid
                                + ",\"result\":{\"stopReason\":\"end_turn\"}}");
                    });
                }
            }
        };
        AcpAgentRunner runner = runnerWith(transport);

        runner.run("ponder", null);

        verify(broadcaster).reasoningDelta(SUB_SESSION_ID, "thinking");
        verify(broadcaster, never()).assistantDelta(eq(SUB_SESSION_ID), anyString());
        // empty assistant text still persisted (no text chunks).
        verify(sessionService).appendNormalMessages(eq(SUB_SESSION_ID), any());
    }

    @Test
    @DisplayName("error path: transport/handshake error → sub-session marked error + client closed")
    void run_errorPath() {
        // initialize never gets a response → handshake times out → error path.
        properties.setPromptTimeoutSeconds(1); // short → handshakeTimeout clamps to 10s min, so instead error via exception
        FakeAcpTransport transport = new FakeAcpTransport() {
            @Override
            public void send(String jsonLine) {
                super.send(jsonLine);
                JsonNode msg;
                try {
                    msg = mapper.readTree(jsonLine);
                } catch (Exception e) {
                    return;
                }
                long id = msg.path("id").asLong(-1);
                if ("initialize".equals(msg.path("method").asText(""))) {
                    // Respond with a JSON-RPC error → initialize future fails fast.
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"error\":{\"code\":-32000,\"message\":\"boom\"}}");
                }
            }
        };
        AcpAgentRunner runner = runnerWith(transport);

        assertThatThrownBy(() -> runner.run("hi", null))
                .isInstanceOf(AcpException.class);

        // sub-session marked error + client closed.
        verify(broadcaster).sessionStatus(eq(SUB_SESSION_ID), eq("error"), isNull(), anyString());
        verify(sessionService, never()).appendNormalMessages(anyString(), any());
        assertThat(transport.closed).isTrue();
    }

    @Test
    @DisplayName("W-3: prompt deadline fires → runner cancels cc + closes transport + marks error")
    void run_promptTimeout_cancelsAndCloses() {
        // Short prompt deadline; handshakes still respond instantly (handshakeTimeout clamps
        // to >=10s, fine). The prompt response is NEVER emitted → promptFuture.get() times out.
        properties.setPromptTimeoutSeconds(1);
        FakeAcpTransport transport = new FakeAcpTransport() {
            @Override
            public void send(String jsonLine) {
                super.send(jsonLine);
                JsonNode msg;
                try {
                    msg = mapper.readTree(jsonLine);
                } catch (Exception e) {
                    return;
                }
                String method = msg.path("method").asText("");
                long id = msg.path("id").asLong(-1);
                // initialize + session/new ack immediately; session/prompt gets NO response;
                // session/cancel is a notification (no id) — recorded in `sent`, not answered.
                if ("initialize".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":1}}");
                } else if ("session/new".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"sessionId\":\"cc-s\"}}");
                }
                // session/prompt: deliberately silent → triggers the timeout branch.
            }
        };
        AcpAgentRunner runner = runnerWith(transport);

        assertThatThrownBy(() -> runner.run("hang forever", null))
                .isInstanceOf(AcpException.class)
                .hasMessageContaining("timed out");

        // timeout branch sent a session/cancel for the cc session.
        boolean sentCancel = transport.sent.stream()
                .anyMatch(l -> l.contains("\"method\":\"session/cancel\"") && l.contains("\"cc-s\""));
        assertThat(sentCancel).isTrue();
        // transport/process closed in finally + sub-session marked error.
        assertThat(transport.closed).isTrue();
        verify(broadcaster).sessionStatus(eq(SUB_SESSION_ID), eq("error"), isNull(), anyString());
        verify(sessionService, never()).appendNormalMessages(anyString(), any());
    }

    @Test
    @DisplayName("setModel is sent when a model is requested")
    void run_withModel_sendsSetModel() throws Exception {
        AtomicReference<Boolean> sawSetModel = new AtomicReference<>(false);
        FakeAcpTransport transport = new FakeAcpTransport() {
            @Override
            public void send(String jsonLine) {
                super.send(jsonLine);
                JsonNode msg;
                try {
                    msg = mapper.readTree(jsonLine);
                } catch (Exception e) {
                    return;
                }
                String method = msg.path("method").asText("");
                long id = msg.path("id").asLong(-1);
                switch (method) {
                    case "initialize" ->
                            emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":1}}");
                    case "session/new" ->
                            emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"sessionId\":\"cc-s\"}}");
                    case "session/set_model" -> {
                        sawSetModel.set(true);
                        emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"ok\":true}}");
                    }
                    case "session/prompt" -> {
                        final long pid = id;
                        driverPool.execute(() -> emit("{\"jsonrpc\":\"2.0\",\"id\":" + pid
                                + ",\"result\":{\"stopReason\":\"end_turn\"}}"));
                    }
                    default -> { /* no-op */ }
                }
            }
        };
        AcpAgentRunner runner = runnerWith(transport);

        runner.run("hi", "sonnet");

        assertThat(sawSetModel.get()).isTrue();
        // verify a set_model line carried the modelId.
        boolean sentModelId = transport.sent.stream().anyMatch(l -> l.contains("\"modelId\":\"sonnet\""));
        assertThat(sentModelId).isTrue();
    }

    @Test
    @DisplayName("blank prompt is rejected before any cc spawn")
    void run_blankPrompt_rejected() {
        FakeAcpTransport transport = scriptedTransport(List.of("x"));
        AcpAgentRunner runner = runnerWith(transport);

        assertThatThrownBy(() -> runner.run("  ", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sessionService, never()).createSession(any(), any());
        assertThat(transport.started).isFalse();
    }

    // ── helpers ──

    private String textOf(Message m) {
        Object content = m.getContent();
        if (content instanceof String s) {
            return s;
        }
        // Message.assistant(String) stores plain string content; defensive for block form.
        if (content instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                sb.append(String.valueOf(o));
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }
}
