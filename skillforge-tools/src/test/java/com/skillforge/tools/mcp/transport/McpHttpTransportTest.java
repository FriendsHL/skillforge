package com.skillforge.tools.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpRequest;
import com.skillforge.tools.mcp.protocol.McpResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link McpHttpTransport} using OkHttp's {@link MockWebServer}.
 * Covers: JSON response parsing, single-frame SSE parsing, Mcp-Session-Id capture
 * + echo, non-2xx error mapping, custom-header (Authorization) pass-through, and
 * concurrent {@code sendRequest} safety.
 */
class McpHttpTransportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private McpHttpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (transport != null) transport.close();
        if (server != null) server.shutdown();
    }

    private McpHttpTransport build(Map<String, String> headers) {
        McpHttpTransport t = new McpHttpTransport(
                "anysearch",
                server.url("/mcp").toString(),
                headers,
                objectMapper,
                new OkHttpClient());
        t.start();
        return t;
    }

    @Test
    @DisplayName("application/json response is parsed directly into McpResponse")
    void jsonResponse_parsed() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}"));
        transport = build(Map.of());

        McpResponse resp = transport.sendRequest(McpRequest.of(1, "tools/list", Map.of()), 5_000L);

        assertThat(resp.isError()).isFalse();
        assertThat(resp.id()).isEqualTo(1);
        assertThat(resp.result()).isNotNull();

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Accept")).contains("text/event-stream");
        assertThat(recorded.getBody().readUtf8()).contains("\"method\":\"tools/list\"");
    }

    @Test
    @DisplayName("text/event-stream single frame is parsed: data: line decoded as JSON-RPC")
    void sseSingleFrame_parsed() throws Exception {
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":\"hi\"}}\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse));
        transport = build(Map.of());

        McpResponse resp = transport.sendRequest(McpRequest.of(7, "tools/call", Map.of()), 5_000L);

        assertThat(resp.isError()).isFalse();
        assertThat(resp.id()).isEqualTo(7);
        assertThat(resp.result()).isNotNull();
    }

    @Test
    @DisplayName("Mcp-Session-Id from first response is captured and echoed on later requests")
    void sessionId_capturedAndResent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "sess-xyz")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}"));
        transport = build(Map.of());

        transport.sendRequest(McpRequest.of(1, "initialize", Map.of()), 5_000L);
        transport.sendRequest(McpRequest.of(2, "tools/list", Map.of()), 5_000L);

        RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(first.getHeader("Mcp-Session-Id")).isNull();
        assertThat(second.getHeader("Mcp-Session-Id")).isEqualTo("sess-xyz");
    }

    @Test
    @DisplayName("non-2xx response maps to McpClientException without leaking headers/body")
    void non2xx_throws() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream boom"));
        transport = build(Map.of("Authorization", "Bearer super-secret-token"));

        assertThatThrownBy(() ->
                transport.sendRequest(McpRequest.of(1, "tools/list", Map.of()), 5_000L))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("HTTP 500")
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain("super-secret-token");
                    assertThat(ex.getMessage()).doesNotContain("Authorization");
                });
    }

    @Test
    @DisplayName("custom headers (Authorization) are sent on the request")
    void headers_passedThrough() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        transport = build(Map.of("Authorization", "Bearer tok-123", "X-Custom", "v"));

        transport.sendRequest(McpRequest.of(1, "initialize", Map.of()), 5_000L);

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer tok-123");
        assertThat(recorded.getHeader("X-Custom")).isEqualTo("v");
        assertThat(recorded.getHeader("Content-Type")).contains("application/json");
    }

    @Test
    @DisplayName("concurrent sendRequest calls are all served without error")
    void concurrentRequests_safe() throws Exception {
        int n = 8;
        for (int i = 0; i < n; i++) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"));
        }
        transport = build(Map.of("Authorization", "Bearer tok"));

        AtomicInteger ok = new AtomicInteger();
        CompletableFuture<?>[] futures = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            final int id = i + 1;
            futures[i] = CompletableFuture.runAsync(() -> {
                McpResponse resp = transport.sendRequest(
                        McpRequest.of(id, "tools/call", Map.of("k", "v")), 5_000L);
                if (!resp.isError() && resp.result() != null) ok.incrementAndGet();
            });
        }
        CompletableFuture.allOf(futures).get(15, TimeUnit.SECONDS);

        assertThat(ok.get()).isEqualTo(n);
        assertThat(server.getRequestCount()).isEqualTo(n);
    }

    @Test
    @DisplayName("sendRequest after close fails fast")
    void sendAfterClose_throws() {
        transport = build(Map.of());
        transport.close();
        assertThatThrownBy(() ->
                transport.sendRequest(McpRequest.of(1, "x", Map.of()), 1_000L))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("multi-event SSE returns the frame whose id matches the request id")
    void sseMultiEvent_matchesById() throws Exception {
        // A keep-alive/progress frame, then a non-matching id, then the real answer.
        String sse = ": keep-alive comment\n\n"
                + "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{\"stale\":true}}\n\n"
                + "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"real\":true}}\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse));
        transport = build(Map.of());

        McpResponse resp = transport.sendRequest(McpRequest.of(42, "tools/call", Map.of()), 5_000L);

        assertThat(resp.id()).isEqualTo(42);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        assertThat(result).containsEntry("real", true);
    }

    @Test
    @DisplayName("constructor rejects blank url / blank serverName")
    void constructor_validatesArgs() {
        assertThatThrownBy(() -> new McpHttpTransport("s", "  ", Map.of(), objectMapper, new OkHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpHttpTransport(" ", "http://x", Map.of(), objectMapper, new OkHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpHttpTransport("s", "not a url", Map.of(), objectMapper, new OkHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sendNotification posts and swallows non-2xx (best-effort)")
    void sendNotification_bestEffort() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202));
        transport = build(Map.of("Authorization", "Bearer tok"));

        // Must not throw even on a non-id notification.
        transport.sendNotification(McpRequest.of(null, "notifications/initialized", Map.of()));

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getBody().readUtf8()).contains("notifications/initialized");
    }

    @Test
    @DisplayName("over-limit response body is rejected with McpClientException (no OOM)")
    void oversizeBody_throws() {
        // 17 MiB > MAX_BODY_BYTES (16 MiB). setBody(Buffer) sets a declared Content-Length,
        // so the transport rejects on the contentLength guard without buffering the body.
        int big = 17 * 1024 * 1024;
        okio.Buffer body = new okio.Buffer();
        body.write(new byte[big]);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));
        transport = build(Map.of("Authorization", "Bearer secret-tok"));

        assertThatThrownBy(() ->
                transport.sendRequest(McpRequest.of(1, "tools/list", Map.of()), 10_000L))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("exceeds")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("secret-tok"));
    }
}
