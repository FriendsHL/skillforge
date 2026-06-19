package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AcpClient} driven by {@link FakeAcpTransport} — NO real
 * subprocess, NO network. Canned JSON-RPC lines mirror the 2026-06-19 spike.
 */
class AcpClientTest {

    private ObjectMapper mapper;
    private FakeAcpTransport transport;
    private AcpClient client;

    @BeforeEach
    void setUp() {
        // Test-only plain ObjectMapper: ACP JSON-RPC payloads here have NO time-typed
        // fields, so the JavaTimeModule footgun (#1) does not apply. Production code must
        // inject the Spring-managed ObjectMapper instead of `new ObjectMapper()`.
        mapper = new ObjectMapper();
        transport = new FakeAcpTransport();
        client = new AcpClient(transport, mapper, new CcAcpUpdateTranslator());
        client.start();
    }

    private JsonNode lastSentNode() {
        try {
            return mapper.readTree(transport.lastSent());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long lastSentId() {
        return lastSentNode().get("id").asLong();
    }

    @Test
    @DisplayName("start() wires the listener and starts the transport")
    void start_wiresTransport() {
        assertThat(transport.started).isTrue();
    }

    @Test
    @DisplayName("initialize result is correlated by id and resolves the future")
    void initialize_correlatesById() throws Exception {
        CompletableFuture<JsonNode> fut = client.initialize();
        JsonNode req = lastSentNode();
        assertThat(req.get("method").asText()).isEqualTo("initialize");
        assertThat(req.get("params").get("protocolVersion").asInt()).isEqualTo(1);

        long id = req.get("id").asLong();
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"result\":{\"protocolVersion\":1,\"agentInfo\":{\"name\":\"cc\"}}}");

        JsonNode result = fut.get(1, TimeUnit.SECONDS);
        assertThat(result.get("protocolVersion").asInt()).isEqualTo(1);
        assertThat(result.get("agentInfo").get("name").asText()).isEqualTo("cc");
    }

    @Test
    @DisplayName("session/new result (models + modes) resolves the right future")
    void newSession_resolvesWithModelsAndModes() throws Exception {
        CompletableFuture<JsonNode> fut = client.newSession("/tmp/x", List.of());
        JsonNode req = lastSentNode();
        assertThat(req.get("method").asText()).isEqualTo("session/new");
        assertThat(req.get("params").get("cwd").asText()).isEqualTo("/tmp/x");
        assertThat(req.get("params").get("mcpServers").isArray()).isTrue();

        long id = req.get("id").asLong();
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{"
                + "\"sessionId\":\"sess-1\","
                + "\"models\":{\"availableModels\":[{\"modelId\":\"opus\",\"name\":\"Opus\"}],"
                + "\"currentModelId\":\"opus\"},"
                + "\"modes\":{\"currentModeId\":\"default\",\"availableModes\":[]}}}");

        JsonNode result = fut.get(1, TimeUnit.SECONDS);
        assertThat(result.get("sessionId").asText()).isEqualTo("sess-1");
        assertThat(result.get("models").get("currentModelId").asText()).isEqualTo("opus");
        assertThat(result.get("modes").get("currentModeId").asText()).isEqualTo("default");
    }

    @Test
    @DisplayName("ids correlate independently: out-of-order responses resolve correct futures")
    void multipleRequests_correlateIndependently() throws Exception {
        CompletableFuture<JsonNode> f1 = client.initialize();
        long id1 = lastSentId();
        CompletableFuture<JsonNode> f2 = client.newSession("/tmp", List.of());
        long id2 = lastSentId();
        assertThat(id2).isNotEqualTo(id1);

        // Respond to the SECOND request first.
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":" + id2 + ",\"result\":{\"sessionId\":\"s2\"}}");
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":" + id1 + ",\"result\":{\"protocolVersion\":1}}");

        assertThat(f2.get(1, TimeUnit.SECONDS).get("sessionId").asText()).isEqualTo("s2");
        assertThat(f1.get(1, TimeUnit.SECONDS).get("protocolVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("session/update notifications are translated + delivered to the listener in order")
    void sessionUpdates_translatedAndDeliveredInOrder() throws Exception {
        List<AcpUpdate> received = new ArrayList<>();
        client.setUpdateListener(u -> received.add(u.update()));

        CompletableFuture<JsonNode> fut = client.prompt("sess-1",
                List.of(mapper.createObjectNode().put("type", "text").put("text", "hi")));
        long id = lastSentId();

        // Stream of notifications mirroring the spike, then the final prompt result.
        transport.emit("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{"
                + "\"sessionId\":\"sess-1\",\"update\":{"
                + "\"sessionUpdate\":\"available_commands_update\",\"availableCommands\":[]}}}");
        transport.emit("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{"
                + "\"sessionId\":\"sess-1\",\"update\":{"
                + "\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"po\"}}}}");
        transport.emit("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{"
                + "\"sessionId\":\"sess-1\",\"update\":{"
                + "\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"ng\"}}}}");
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"stopReason\":\"end_turn\"}}");

        assertThat(fut.get(1, TimeUnit.SECONDS).get("stopReason").asText()).isEqualTo("end_turn");
        assertThat(received).hasSize(3);
        assertThat(received.get(0)).isInstanceOf(AcpUpdate.AvailableCommands.class);
        assertThat(received.get(1)).isInstanceOf(AcpUpdate.TextChunk.class);
        assertThat(received.get(2)).isInstanceOf(AcpUpdate.TextChunk.class);
        assertThat(((AcpUpdate.TextChunk) received.get(1)).text()).isEqualTo("po");
        assertThat(((AcpUpdate.TextChunk) received.get(2)).text()).isEqualTo("ng");
    }

    @Test
    @DisplayName("incoming permission request is forwarded to the handler")
    void serverRequest_forwardedToHandler() {
        AtomicReference<AcpServerRequest> captured = new AtomicReference<>();
        client.setServerRequestHandler(captured::set);

        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"session/request_permission\","
                + "\"params\":{\"sessionId\":\"sess-1\",\"toolCall\":{\"toolCallId\":\"tc-1\"}}}");

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().method()).isEqualTo("session/request_permission");
        assertThat(captured.get().id().asInt()).isEqualTo(99);
        assertThat(captured.get().params().get("sessionId").asText()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("default handler DENIES an incoming permission request (sends cancelled outcome)")
    void serverRequest_defaultDenies() {
        // No custom handler set → default deny.
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"session/request_permission\","
                + "\"params\":{\"sessionId\":\"sess-1\"}}");

        JsonNode reply = lastSentNode();
        assertThat(reply.get("id").asInt()).isEqualTo(99);
        assertThat(reply.get("result").get("outcome").get("outcome").asText())
                .isEqualTo("cancelled");
    }

    @Test
    @DisplayName("default handler rejects a NON-permission server request with a JSON-RPC error (D-W2)")
    void serverRequest_nonPermission_defaultRejectsWithError() {
        // No custom handler → default. A non-permission method must NOT get a permission outcome.
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"fs/read_text_file\","
                + "\"params\":{\"path\":\"/etc/passwd\"}}");

        JsonNode reply = lastSentNode();
        assertThat(reply.get("id").asInt()).isEqualTo(42);
        assertThat(reply.has("result")).isFalse();
        assertThat(reply.get("error").get("code").asInt()).isEqualTo(-32601);
        assertThat(reply.get("error").get("message").asText()).contains("fs/read_text_file");
    }

    @Test
    @DisplayName("JSON-RPC error response completes the future exceptionally with code")
    void errorResponse_completesExceptionally() {
        CompletableFuture<JsonNode> fut = client.initialize();
        long id = lastSentId();
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");

        assertThatThrownBy(() -> fut.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AcpException.class)
                .hasMessageContaining("Method not found");
        assertThat(((AcpException) extractCause(fut)).getRpcCode()).isEqualTo(-32601);
    }

    @Test
    @DisplayName("cancel sends a notification (no id, not correlated)")
    void cancel_sendsNotification() {
        client.cancel("sess-1");
        JsonNode msg = lastSentNode();
        assertThat(msg.has("id")).isFalse();
        assertThat(msg.get("method").asText()).isEqualTo("session/cancel");
        assertThat(msg.get("params").get("sessionId").asText()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("setModel sends session/set_model with modelId and correlates")
    void setModel_sendsAndCorrelates() throws Exception {
        CompletableFuture<JsonNode> fut = client.setModel("sess-1", "sonnet");
        JsonNode req = lastSentNode();
        assertThat(req.get("method").asText()).isEqualTo("session/set_model");
        assertThat(req.get("params").get("modelId").asText()).isEqualTo("sonnet");

        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id").asLong()
                + ",\"result\":{\"ok\":true}}");
        assertThat(fut.get(1, TimeUnit.SECONDS).get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("close() fails in-flight requests instead of hanging")
    void close_failsInFlightRequests() {
        CompletableFuture<JsonNode> fut = client.initialize();
        client.close();
        assertThat(transport.closed).isTrue();
        assertThatThrownBy(fut::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AcpException.class);
    }

    @Test
    @DisplayName("a response for an unknown id is ignored (no crash)")
    void unknownResponseId_ignored() {
        transport.emit("{\"jsonrpc\":\"2.0\",\"id\":12345,\"result\":{\"x\":1}}");
        // No exception, nothing pending — just verify the client still works.
        CompletableFuture<JsonNode> fut = client.initialize();
        assertThat(fut).isNotDone();
    }

    private static Throwable extractCause(CompletableFuture<?> fut) {
        try {
            fut.get(1, TimeUnit.SECONDS);
            return null;
        } catch (Exception e) {
            return e.getCause();
        }
    }
}
