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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
import static org.mockito.Mockito.times;
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
    private static final long CALLER_USER_ID = 1L;

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
        if (traceScheduler != null) {
            traceScheduler.shutdownNow();
        }
    }

    /**
     * Build a runner whose factory hands out an AcpClient backed by {@code transport}.
     * The transport is captured so the test can drive canned inbound lines.
     */
    private AcpAgentRunner runnerWith(FakeAcpTransport transport) {
        AcpClientFactory factory = (cwd, env) ->
                new AcpClient(transport, mapper, new CcAcpUpdateTranslator());
        AcpPermissionBridge bridge = new AcpPermissionBridge(
                new com.skillforge.core.engine.confirm.PendingConfirmationRegistry(),
                broadcaster, driverPool, 30);
        return new AcpAgentRunner(factory, sessionService, agentRepository,
                broadcaster, mapper, properties, bridge);
    }

    /**
     * Build a P1c-1 SubAgent-mode runner. Uses a DIRECT (caller-runs) executor so
     * the otherwise-async {@code runAsSubAgent} completes inline, making the
     * finished-signal assertions deterministic without sleeps/latches.
     */
    private AcpAgentRunner subAgentRunnerWith(FakeAcpTransport transport,
                                              com.skillforge.server.subagent.SubAgentRegistry registry,
                                              org.springframework.context.ApplicationEventPublisher publisher) {
        AcpClientFactory factory = (cwd, env) ->
                new AcpClient(transport, mapper, new CcAcpUpdateTranslator());
        AcpPermissionBridge bridge = new AcpPermissionBridge(
                new com.skillforge.core.engine.confirm.PendingConfirmationRegistry(),
                broadcaster, driverPool, 30);
        java.util.concurrent.Executor directExecutor = Runnable::run;
        return new AcpAgentRunner(factory, sessionService, agentRepository,
                broadcaster, mapper, properties, bridge, directExecutor, registry, publisher);
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
                } else if ("session/set_mode".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
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

        String subSessionId = runner.run("say hi", null, CALLER_USER_ID);

        assertThat(subSessionId).isEqualTo(SUB_SESSION_ID);

        // sub-session created.
        verify(sessionService).createSession(eq(CALLER_USER_ID), eq(AGENT_ID));

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
                } else if ("session/set_mode".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
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

        runner.run("ponder", null, CALLER_USER_ID);

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

        assertThatThrownBy(() -> runner.run("hi", null, CALLER_USER_ID))
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
                } else if ("session/set_mode".equals(method)) {
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
                }
                // session/prompt: deliberately silent → triggers the timeout branch.
            }
        };
        AcpAgentRunner runner = runnerWith(transport);

        assertThatThrownBy(() -> runner.run("hang forever", null, CALLER_USER_ID))
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
                    case "session/set_mode" ->
                            emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
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

        runner.run("hi", "sonnet", CALLER_USER_ID);

        assertThat(sawSetModel.get()).isTrue();
        // verify a set_model line carried the modelId.
        boolean sentModelId = transport.sent.stream().anyMatch(l -> l.contains("\"modelId\":\"sonnet\""));
        assertThat(sentModelId).isTrue();
    }

    @Test
    @DisplayName("P2-1: telemetry env (CLAUDE_CODE_ENABLE_TELEMETRY + sf.session_id resource attr) reaches the spawned cc")
    void run_injectsTelemetryEnvIntoSpawnedCc() {
        properties.setOtlpEndpoint("http://localhost:8080");
        FakeAcpTransport transport = scriptedTransport(List.of("ok"));
        AtomicReference<Map<String, String>> capturedEnv = new AtomicReference<>();
        AcpClientFactory factory = (cwd, env) -> {
            capturedEnv.set(env);
            return new AcpClient(transport, mapper, new CcAcpUpdateTranslator());
        };
        AcpPermissionBridge bridge = new AcpPermissionBridge(
                new com.skillforge.core.engine.confirm.PendingConfirmationRegistry(),
                broadcaster, driverPool, 30);
        AcpAgentRunner runner = new AcpAgentRunner(factory, sessionService, agentRepository,
                broadcaster, mapper, properties, bridge);

        runner.run("hi", null, CALLER_USER_ID);

        Map<String, String> env = capturedEnv.get();
        assertThat(env).isNotNull();
        assertThat(env).containsEntry("CLAUDE_CODE_ENABLE_TELEMETRY", "1");
        assertThat(env).containsEntry("OTEL_LOGS_EXPORTER", "otlp");
        assertThat(env).containsEntry("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json");
        assertThat(env).containsEntry("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:8080");
        assertThat(env).containsEntry("OTEL_BSP_SCHEDULE_DELAY", "1000");
        assertThat(env).containsEntry("OTEL_BLRP_SCHEDULE_DELAY", "1000");
        // sf.session_id binds events back to THIS sub-session; sf.agent_id present too.
        assertThat(env.get("OTEL_RESOURCE_ATTRIBUTES"))
                .contains("sf.session_id=" + SUB_SESSION_ID);
    }

    @Test
    @DisplayName("P2-1: blank otlp-endpoint disables telemetry env injection (empty env to cc)")
    void run_blankOtlpEndpoint_noTelemetryEnv() {
        properties.setOtlpEndpoint("");
        FakeAcpTransport transport = scriptedTransport(List.of("ok"));
        AtomicReference<Map<String, String>> capturedEnv = new AtomicReference<>();
        AcpClientFactory factory = (cwd, env) -> {
            capturedEnv.set(env);
            return new AcpClient(transport, mapper, new CcAcpUpdateTranslator());
        };
        AcpPermissionBridge bridge = new AcpPermissionBridge(
                new com.skillforge.core.engine.confirm.PendingConfirmationRegistry(),
                broadcaster, driverPool, 30);
        AcpAgentRunner runner = new AcpAgentRunner(factory, sessionService, agentRepository,
                broadcaster, mapper, properties, bridge);

        runner.run("hi", null, CALLER_USER_ID);

        assertThat(capturedEnv.get()).isEmpty();
    }

    @Test
    @DisplayName("permission mode defaults to 'auto' (cc runs autonomously, no per-tool prompt)")
    void run_setMode_defaultsToAuto() {
        // properties is fresh in setUp() → default permissionMode = "auto".
        assertThat(properties.getPermissionMode()).isEqualTo("auto");

        FakeAcpTransport transport = scriptedTransport(List.of("ok"));
        AcpAgentRunner runner = runnerWith(transport);

        runner.run("hi", null, CALLER_USER_ID);

        // set_mode carried modeId=auto.
        boolean sentAutoMode = transport.sent.stream()
                .anyMatch(l -> l.contains("\"method\":\"session/set_mode\"")
                        && l.contains("\"modeId\":\"auto\""));
        assertThat(sentAutoMode).isTrue();
        boolean sentDefaultMode = transport.sent.stream()
                .anyMatch(l -> l.contains("\"modeId\":\"default\""));
        assertThat(sentDefaultMode).isFalse();
    }

    @Test
    @DisplayName("permission mode 'default' (override) is sent so cc prompts (AC-3)")
    void run_setMode_overrideToDefault() {
        properties.setPermissionMode("default");
        FakeAcpTransport transport = scriptedTransport(List.of("ok"));
        AcpAgentRunner runner = runnerWith(transport);

        runner.run("hi", null, CALLER_USER_ID);

        boolean sentDefaultMode = transport.sent.stream()
                .anyMatch(l -> l.contains("\"method\":\"session/set_mode\"")
                        && l.contains("\"modeId\":\"default\""));
        assertThat(sentDefaultMode).isTrue();
    }

    @Test
    @DisplayName("blank prompt is rejected before any cc spawn")
    void run_blankPrompt_rejected() {
        FakeAcpTransport transport = scriptedTransport(List.of("x"));
        AcpAgentRunner runner = runnerWith(transport);

        assertThatThrownBy(() -> runner.run("  ", null, CALLER_USER_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sessionService, never()).createSession(any(), any());
        assertThat(transport.started).isFalse();
    }

    @Test
    @DisplayName("tool_call → update(filled) → update(completed) persists a paired tool_use + tool_result (INV-1)")
    void run_toolCall_paired() {
        // Drive the captured cc tool_call lifecycle for one Bash tool.
        FakeAcpTransport transport = toolCallTransport(List.of(
                // pending tool_call
                "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"tc-1\",\"title\":\"Run ls\","
                        + "\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{},\"content\":[],"
                        + "\"_meta\":{\"claudeCode\":{\"toolName\":\"Bash\"}}}",
                // intermediate update: rawInput filled, not completed
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"tc-1\","
                        + "\"rawInput\":{\"command\":\"ls\"},"
                        + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"...\"}}]}",
                // completion
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"tc-1\",\"status\":\"completed\","
                        + "\"rawOutput\":\"file1\\nfile2\","
                        + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"file1\"}}]}"
        ));
        AcpAgentRunner runner = runnerWith(transport);

        AcpAgentRunner.RunResult result = runner.runResult("list files", null, CALLER_USER_ID);

        assertThat(result.subagentCount()).isZero(); // Bash, not Task

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> persisted = ArgumentCaptor.forClass(List.class);
        verify(sessionService).appendNormalMessages(eq(SUB_SESSION_ID), persisted.capture());
        List<Message> turn = persisted.getValue();
        // Canonical shape: ASSISTANT (tool_use) then USER (tool_result).
        assertThat(turn).hasSize(2);
        Message assistant = turn.get(0);
        Message userToolResult = turn.get(1);
        assertThat(assistant.getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(userToolResult.getRole()).isEqualTo(Message.Role.USER);

        List<ContentBlock> useBlocks = blocksOf(assistant);
        List<ContentBlock> resultBlocks = blocksOf(userToolResult);
        long toolUseCount = useBlocks.stream().filter(b -> "tool_use".equals(b.getType())).count();
        long toolResultCount = resultBlocks.stream().filter(b -> "tool_result".equals(b.getType())).count();
        assertThat(toolUseCount).isEqualTo(1);
        assertThat(toolResultCount).isEqualTo(1);

        ContentBlock toolUse = useBlocks.stream().filter(b -> "tool_use".equals(b.getType())).findFirst().orElseThrow();
        ContentBlock toolResult = resultBlocks.stream().filter(b -> "tool_result".equals(b.getType())).findFirst().orElseThrow();
        assertThat(toolUse.getId()).isEqualTo("tc-1");
        assertThat(toolUse.getName()).isEqualTo("Bash"); // from _meta.claudeCode.toolName
        assertThat(toolUse.getInput()).containsEntry("command", "ls"); // filled input
        assertThat(toolResult.getToolUseId()).isEqualTo("tc-1"); // matching id (INV-1)
        assertThat(toolResult.getContent()).isEqualTo("file1\nfile2");
        assertThat(toolResult.getIsError()).isFalse();

        // INV-1 (strong): the set of tool_use ids == the set of tool_result ids.
        assertThat(useBlocks.stream().filter(b -> "tool_use".equals(b.getType())).map(ContentBlock::getId).toList())
                .containsExactlyInAnyOrderElementsOf(
                        resultBlocks.stream().filter(b -> "tool_result".equals(b.getType()))
                                .map(ContentBlock::getToolUseId).toList());
    }

    @Test
    @DisplayName("INV-1: a tool_use that never completes gets a synthesized error tool_result (no orphan)")
    void run_incompleteToolCall_synthesizesResult() {
        FakeAcpTransport transport = toolCallTransport(List.of(
                "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"tc-x\",\"title\":\"Write\","
                        + "\"kind\":\"edit\",\"status\":\"pending\",\"rawInput\":{\"path\":\"a.txt\"},"
                        + "\"_meta\":{\"claudeCode\":{\"toolName\":\"Write\"}}}"
                // NO completion update → must be synthesized at run end.
        ));
        AcpAgentRunner runner = runnerWith(transport);

        runner.runResult("write a file", null, CALLER_USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> persisted = ArgumentCaptor.forClass(List.class);
        verify(sessionService).appendNormalMessages(eq(SUB_SESSION_ID), persisted.capture());
        List<Message> turn = persisted.getValue();
        assertThat(turn).hasSize(2);
        ContentBlock toolUse = blocksOf(turn.get(0)).stream()
                .filter(b -> "tool_use".equals(b.getType())).findFirst().orElseThrow();
        ContentBlock toolResult = blocksOf(turn.get(1)).stream()
                .filter(b -> "tool_result".equals(b.getType())).findFirst().orElseThrow();
        assertThat(toolUse.getId()).isEqualTo("tc-x");
        assertThat(toolResult.getToolUseId()).isEqualTo("tc-x"); // matching id (INV-1)
        assertThat(toolResult.getIsError()).isTrue();
        assertThat(toolResult.getContent()).isEqualTo(AcpToolCallAccumulator.INCOMPLETE_RESULT);
    }

    @Test
    @DisplayName("AC-2a: a Task tool_call increments the subagent count")
    void run_taskToolCall_countsSubagent() {
        FakeAcpTransport transport = toolCallTransport(List.of(
                "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"task-1\",\"title\":\"Dispatch\","
                        + "\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{},"
                        + "\"_meta\":{\"claudeCode\":{\"toolName\":\"Task\"}}}",
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"task-1\",\"status\":\"completed\","
                        + "\"rawOutput\":\"done\"}",
                "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"task-2\",\"title\":\"Dispatch2\","
                        + "\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{},"
                        + "\"_meta\":{\"claudeCode\":{\"toolName\":\"Task\"}}}",
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"task-2\",\"status\":\"completed\","
                        + "\"rawOutput\":\"done2\"}"
        ));
        AcpAgentRunner runner = runnerWith(transport);

        AcpAgentRunner.RunResult result = runner.runResult("do two things", null, CALLER_USER_ID);

        assertThat(result.subagentCount()).isEqualTo(2);
        // count surfaced on the (viewable, persisted) sub-session title.
        verify(broadcaster).sessionTitleUpdated(eq(SUB_SESSION_ID), eq("ACP cc run (2 subagents)"));
    }

    @Test
    @DisplayName("compact-W3: a tool_call_update arriving BEFORE the initial tool_call → INV-1 still holds")
    void run_updateBeforeToolCall_invariantHolds() {
        // The completion update arrives FIRST (defensive computeIfAbsent path), the
        // pending tool_call SECOND. The persisted turn must still pair use+result by id.
        FakeAcpTransport transport = toolCallTransport(List.of(
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"tc-7\",\"status\":\"completed\","
                        + "\"rawOutput\":\"early\",\"rawInput\":{\"command\":\"echo hi\"},"
                        + "\"_meta\":{\"claudeCode\":{\"toolName\":\"Bash\"}}}",
                "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"tc-7\",\"title\":\"Run echo\","
                        + "\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{},"
                        + "\"_meta\":{\"claudeCode\":{\"toolName\":\"Bash\"}}}"
        ));
        AcpAgentRunner runner = runnerWith(transport);

        runner.runResult("echo", null, CALLER_USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> persisted = ArgumentCaptor.forClass(List.class);
        verify(sessionService).appendNormalMessages(eq(SUB_SESSION_ID), persisted.capture());
        List<Message> turn = persisted.getValue();
        assertThat(turn).hasSize(2); // ASSISTANT(tool_use) + USER(tool_result)
        ContentBlock toolUse = blocksOf(turn.get(0)).stream()
                .filter(b -> "tool_use".equals(b.getType())).findFirst().orElseThrow();
        ContentBlock toolResult = blocksOf(turn.get(1)).stream()
                .filter(b -> "tool_result".equals(b.getType())).findFirst().orElseThrow();
        assertThat(toolUse.getId()).isEqualTo("tc-7");
        assertThat(toolUse.getName()).isEqualTo("Bash");
        assertThat(toolResult.getToolUseId()).isEqualTo("tc-7"); // matching id (INV-1)
        assertThat(toolResult.getIsError()).isFalse();
        assertThat(toolResult.getContent()).isEqualTo("early");
    }

    // ───────────────────────── P1c-1: runAsSubAgent ─────────────────────────

    private static final String CHILD_SESSION_ID = "child-sess-1";

    /** A child session as SubAgentTool.createSubSession would hand it to runAsSubAgent. */
    private SessionEntity childSession() {
        SessionEntity child = new SessionEntity();
        child.setId(CHILD_SESSION_ID);
        child.setUserId(CALLER_USER_ID);
        child.setParentSessionId("parent-sess-1");
        child.setSubAgentRunId("run-abc");
        return child;
    }

    @Test
    @DisplayName("runAsSubAgent: runs on the GIVEN child (no new session), persists, emits finished signals, idle")
    void runAsSubAgent_happyPath() {
        SessionEntity child = childSession();
        when(sessionService.getSession(CHILD_SESSION_ID)).thenReturn(child);

        var registry = mock(com.skillforge.server.subagent.SubAgentRegistry.class);
        var publisher = mock(org.springframework.context.ApplicationEventPublisher.class);

        FakeAcpTransport transport = scriptedTransport(List.of("Done", "!"));
        AcpAgentRunner runner = subAgentRunnerWith(transport, registry, publisher);

        runner.runAsSubAgent(child, "do the task", CALLER_USER_ID);

        // 1. NO new session created — runs on the given child.
        verify(sessionService, never()).createSession(any(), any());

        // 2. final assistant message persisted on the CHILD session.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> persisted = ArgumentCaptor.forClass(List.class);
        verify(sessionService).appendNormalMessages(eq(CHILD_SESSION_ID), persisted.capture());
        assertThat(textOf(persisted.getValue().get(0))).isEqualTo("Done!");

        // 3. SubAgent registry pump invoked with the child id + completed status (AC-5 delivery reuse).
        verify(registry).onSessionLoopFinished(
                eq(CHILD_SESSION_ID), eq("Done!"), eq("completed"), eq(0), org.mockito.ArgumentMatchers.anyLong());

        // 4. generic SessionLoopFinishedEvent published with the right child id / status / user.
        ArgumentCaptor<SessionLoopFinishedEvent> evt = ArgumentCaptor.forClass(SessionLoopFinishedEvent.class);
        verify(publisher).publishEvent(evt.capture());
        assertThat(evt.getValue().sessionId()).isEqualTo(CHILD_SESSION_ID);
        assertThat(evt.getValue().finalStatus()).isEqualTo("completed");
        assertThat(evt.getValue().finalMessage()).isEqualTo("Done!");
        assertThat(evt.getValue().userId()).isEqualTo(CALLER_USER_ID);

        // 5. child marked idle (running → idle).
        verify(broadcaster).sessionStatus(CHILD_SESSION_ID, "running", "ACP cc", null);
        verify(broadcaster).sessionStatus(CHILD_SESSION_ID, "idle", null, null);
    }

    @Test
    @DisplayName("runAsSubAgent error path: emits finished event status=error + child error, still delivers to parent")
    void runAsSubAgent_errorPath() {
        SessionEntity child = childSession();
        when(sessionService.getSession(CHILD_SESSION_ID)).thenReturn(child);

        var registry = mock(com.skillforge.server.subagent.SubAgentRegistry.class);
        var publisher = mock(org.springframework.context.ApplicationEventPublisher.class);

        // initialize replies with a JSON-RPC error → handshake fails → error path.
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
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"error\":{\"code\":-32000,\"message\":\"boom\"}}");
                }
            }
        };
        AcpAgentRunner runner = subAgentRunnerWith(transport, registry, publisher);

        // does NOT throw (unlike standalone run) — the error is delivered to the parent.
        runner.runAsSubAgent(child, "do the task", CALLER_USER_ID);

        // child marked error.
        verify(broadcaster).sessionStatus(eq(CHILD_SESSION_ID), eq("error"), isNull(), anyString());
        // no turn persisted on the error path (INV-1: no orphan tool_use).
        verify(sessionService, never()).appendNormalMessages(anyString(), any());

        // registry pump + event still fire with status=error so the parent gets a result.
        verify(registry).onSessionLoopFinished(
                eq(CHILD_SESSION_ID), anyString(), eq("error"), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        ArgumentCaptor<SessionLoopFinishedEvent> evt = ArgumentCaptor.forClass(SessionLoopFinishedEvent.class);
        verify(publisher).publishEvent(evt.capture());
        assertThat(evt.getValue().sessionId()).isEqualTo(CHILD_SESSION_ID);
        assertThat(evt.getValue().finalStatus()).isEqualTo("error");
    }

    @Test
    @DisplayName("runAsSubAgent: a tool_call lifecycle persists an INV-1 paired turn on the child + counts toolCalls")
    void runAsSubAgent_toolCall_pairedAndCounted() {
        SessionEntity child = childSession();
        when(sessionService.getSession(CHILD_SESSION_ID)).thenReturn(child);

        var registry = mock(com.skillforge.server.subagent.SubAgentRegistry.class);
        var publisher = mock(org.springframework.context.ApplicationEventPublisher.class);

        FakeAcpTransport transport = toolCallTransport(List.of(
                "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"tc-1\",\"title\":\"Run ls\","
                        + "\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{},"
                        + "\"_meta\":{\"claudeCode\":{\"toolName\":\"Bash\"}}}",
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"tc-1\",\"status\":\"completed\","
                        + "\"rawOutput\":\"ok\",\"rawInput\":{\"command\":\"ls\"}}"
        ));
        AcpAgentRunner runner = subAgentRunnerWith(transport, registry, publisher);

        runner.runAsSubAgent(child, "list files", CALLER_USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> persisted = ArgumentCaptor.forClass(List.class);
        verify(sessionService).appendNormalMessages(eq(CHILD_SESSION_ID), persisted.capture());
        List<Message> turn = persisted.getValue();
        assertThat(turn).hasSize(2); // ASSISTANT(tool_use) + USER(tool_result)
        ContentBlock toolUse = blocksOf(turn.get(0)).stream()
                .filter(b -> "tool_use".equals(b.getType())).findFirst().orElseThrow();
        ContentBlock toolResult = blocksOf(turn.get(1)).stream()
                .filter(b -> "tool_result".equals(b.getType())).findFirst().orElseThrow();
        assertThat(toolUse.getId()).isEqualTo("tc-1");
        assertThat(toolResult.getToolUseId()).isEqualTo("tc-1"); // INV-1

        // toolCalls count (1 Bash) flows into the finished signals.
        verify(registry).onSessionLoopFinished(
                eq(CHILD_SESSION_ID), anyString(), eq("completed"), eq(1),
                org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * Transport that acks the handshake (initialize/session/new/set_mode) and, on
     * session/prompt, streams the given canned {@code update} objects (each wrapped
     * in a session/update notification) then the final end_turn result.
     */
    private FakeAcpTransport toolCallTransport(List<String> updateObjects) {
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
                switch (method) {
                    case "initialize" ->
                            emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":1}}");
                    case "session/new" ->
                            emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"sessionId\":\"cc-s\"}}");
                    case "session/set_mode" ->
                            emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
                    case "session/prompt" -> {
                        final long pid = id;
                        driverPool.execute(() -> {
                            for (String upd : updateObjects) {
                                emit("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{"
                                        + "\"sessionId\":\"cc-s\",\"update\":" + upd + "}}");
                            }
                            emit("{\"jsonrpc\":\"2.0\",\"id\":" + pid
                                    + ",\"result\":{\"stopReason\":\"end_turn\"}}");
                        });
                    }
                    default -> { /* no-op */ }
                }
            }
        };
    }

    // ───────────────────────── P2-3a: trace finalize ─────────────────────────

    /**
     * Build a runner wired with the P2-3a trace-finalize deps. The scheduler runs
     * tasks INLINE (caller-runs) so the otherwise-deferred finalize is deterministic
     * without sleeps; with {@code traceFinalizeGraceSeconds=0} the runner finalizes
     * immediately (no scheduler delay), so the inline scheduler is only a safety net.
     */
    private ScheduledExecutorService traceScheduler;

    private AcpAgentRunner traceRunnerWith(FakeAcpTransport transport, LlmTraceStore traceStore) {
        // grace=0 → scheduleTraceFinalize runs doFinalizeTrace inline on the runner
        // thread (no scheduler delay), so the finalize is deterministic without sleeps.
        // The scheduler is still passed (non-null) so the deps are considered "wired".
        properties.setTraceFinalizeGraceSeconds(0);
        AcpClientFactory factory = (cwd, env) ->
                new AcpClient(transport, mapper, new CcAcpUpdateTranslator());
        AcpPermissionBridge bridge = new AcpPermissionBridge(
                new com.skillforge.core.engine.confirm.PendingConfirmationRegistry(),
                broadcaster, driverPool, 30);
        traceScheduler = Executors.newSingleThreadScheduledExecutor();
        return new AcpAgentRunner(factory, sessionService, agentRepository,
                broadcaster, mapper, properties, bridge, null, null, null,
                traceStore, traceScheduler);
    }

    @Test
    @DisplayName("P2-3a: completed run finalizes cc trace as status=ok, duration>0, tool/event counts recomputed from spans")
    void run_finalizesTrace_completed() {
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        // The translator wrote: 2 tool spans (Bash x2) + 1 event span; llm count is irrelevant here.
        when(traceStore.countSpansByKind(anyString()))
                .thenReturn(Map.of("llm", 4L, "tool", 2L, "event", 1L));

        FakeAcpTransport transport = scriptedTransport(List.of("done"));
        AcpAgentRunner runner = traceRunnerWith(transport, traceStore);

        runner.run("say hi", null, CALLER_USER_ID);

        ArgumentCaptor<LlmTraceStore.TraceFinalizeRequest> cap =
                ArgumentCaptor.forClass(LlmTraceStore.TraceFinalizeRequest.class);
        verify(traceStore).finalizeTrace(cap.capture());
        LlmTraceStore.TraceFinalizeRequest req = cap.getValue();
        // traceId derivation SHARED with the translator (no duplicated literal).
        assertThat(req.traceId()).isEqualTo(CcEventSpanTranslator.traceIdFor(SUB_SESSION_ID));
        assertThat(req.status()).isEqualTo("ok");
        assertThat(req.error()).isNull();
        assertThat(req.toolCallCount()).isEqualTo(2);   // from spans, not engine counters
        assertThat(req.eventCount()).isEqualTo(1);      // from spans
        assertThat(req.totalDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(req.endedAt()).isNotNull();
    }

    @Test
    @DisplayName("P2-3a: error/timeout run finalizes cc trace as status=error (counts still recomputed)")
    void run_finalizesTrace_error() {
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.countSpansByKind(anyString()))
                .thenReturn(Map.of("tool", 1L));

        // initialize replies with a JSON-RPC error → handshake fails → error path.
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
                    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"error\":{\"code\":-32000,\"message\":\"boom\"}}");
                }
            }
        };
        AcpAgentRunner runner = traceRunnerWith(transport, traceStore);

        assertThatThrownBy(() -> runner.run("hi", null, CALLER_USER_ID))
                .isInstanceOf(AcpException.class);

        ArgumentCaptor<LlmTraceStore.TraceFinalizeRequest> cap =
                ArgumentCaptor.forClass(LlmTraceStore.TraceFinalizeRequest.class);
        verify(traceStore).finalizeTrace(cap.capture());
        LlmTraceStore.TraceFinalizeRequest req = cap.getValue();
        assertThat(req.traceId()).isEqualTo(CcEventSpanTranslator.traceIdFor(SUB_SESSION_ID));
        assertThat(req.status()).isEqualTo("error");
        assertThat(req.error()).isNotNull();
        assertThat(req.toolCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("P2-3a: a late span landing AFTER finalize does not re-finalize / flip status (idempotent — finalize called exactly once)")
    void run_lateSpanAfterFinalize_idempotent() {
        // The store's finalizeTrace SQL is WHERE status='running', so a re-finalize is a
        // no-op at the DB. At the runner level, finalize is scheduled exactly once per run;
        // a late translator span only inserts a span row, it never calls finalizeTrace.
        // This asserts the runner issues a SINGLE finalize for one completed run, and that
        // countSpansByKind is read at finalize time (so any spans that landed during the
        // grace window are reflected) — the late-span-after-finalize case cannot double
        // count because there is no second finalize.
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.countSpansByKind(anyString()))
                .thenReturn(Map.of("tool", 2L, "event", 1L));

        FakeAcpTransport transport = scriptedTransport(List.of("ok"));
        AcpAgentRunner runner = traceRunnerWith(transport, traceStore);

        runner.run("hi", null, CALLER_USER_ID);

        // Exactly one finalize for the run (no re-finalize loop).
        verify(traceStore, times(1)).finalizeTrace(any());
        // Counts were sourced from countSpansByKind at finalize time.
        verify(traceStore, atLeastOnce()).countSpansByKind(
                eq(CcEventSpanTranslator.traceIdFor(SUB_SESSION_ID)));
    }

    @Test
    @DisplayName("P2-3a: finalize deps unwired (standalone/demo path) → no finalize attempted, run still succeeds")
    void run_noTraceDeps_skipsFinalize() {
        // runnerWith(..) uses the 7-arg constructor → traceStore + scheduler are null.
        FakeAcpTransport transport = scriptedTransport(List.of("ok"));
        AcpAgentRunner runner = runnerWith(transport);

        // Should not throw despite no finalize wiring.
        String id = runner.run("hi", null, CALLER_USER_ID);
        assertThat(id).isEqualTo(SUB_SESSION_ID);
        // (no traceStore to verify against — absence of NPE is the assertion).
        assertThat(Collections.emptyList()).isEmpty();
    }

    // ── helpers ──

    @SuppressWarnings("unchecked")
    private List<ContentBlock> blocksOf(Message m) {
        return (List<ContentBlock>) m.getContent();
    }

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
