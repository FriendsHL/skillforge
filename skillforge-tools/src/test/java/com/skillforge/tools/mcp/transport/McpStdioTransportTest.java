package com.skillforge.tools.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpRequest;
import com.skillforge.tools.mcp.protocol.McpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link McpStdioTransport}.
 *
 * <p>Strategy: avoid real {@code Process} — use the package-private test-seam
 * constructor that accepts pre-attached streams. Stdin = ByteArrayOutputStream
 * we read back. Stdout = PipedInputStream that we feed responses through.
 * No mockito needed.
 */
class McpStdioTransportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpStdioTransport transport;
    private PipedOutputStream serverWritesStdout; // server end of stdout pipe → client reads it
    private ByteArrayOutputStream clientWritesStdin;

    /** Construct transport with pre-attached streams (test-seam ctor). */
    private void buildTransport() throws Exception {
        // Pipe: server writes to PipedOutputStream → client (transport) reads PipedInputStream.
        PipedInputStream stdoutForClient = new PipedInputStream(64 * 1024);
        serverWritesStdout = new PipedOutputStream(stdoutForClient);
        clientWritesStdin = new ByteArrayOutputStream();
        InputStream emptyStderr = new ByteArrayInputStream(new byte[0]);

        Constructor<McpStdioTransport> ctor = McpStdioTransport.class.getDeclaredConstructor(
                String.class, InputStream.class, OutputStream.class, InputStream.class, ObjectMapper.class);
        ctor.setAccessible(true);
        transport = ctor.newInstance("test", stdoutForClient, clientWritesStdin, emptyStderr, objectMapper);
        transport.start();
    }

    /** Push a single JSON line as if the MCP server emitted it on stdout. */
    private void pushServerLine(String json) throws IOException {
        serverWritesStdout.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        serverWritesStdout.flush();
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.close();
    }

    @Test
    @DisplayName("sendRequest correlates response by id and returns the McpResponse")
    void sendRequest_matchedById() throws Exception {
        buildTransport();
        // Run sendRequest on a worker thread; reply asynchronously from the test thread.
        CompletableFuture<McpResponse> future = CompletableFuture.supplyAsync(() ->
                transport.sendRequest(McpRequest.of(42, "tools/list", Map.of()), 5_000L));
        // Give the writer a beat to put the request on the pipe before we reply.
        Thread.sleep(50);
        pushServerLine("{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"tools\":[]}}");
        McpResponse resp = future.get(2, TimeUnit.SECONDS);

        assertThat(resp.isError()).isFalse();
        assertThat(resp.id()).isEqualTo(42);
        // verify the wire format we wrote — single-line JSON terminated by '\n'
        String written = clientWritesStdin.toString(StandardCharsets.UTF_8);
        assertThat(written).startsWith("{").endsWith("\n");
        assertThat(written.lines().count()).isEqualTo(1);
        assertThat(written).contains("\"method\":\"tools/list\"");
        assertThat(written).contains("\"id\":42");
    }

    @Test
    @DisplayName("Garbage non-JSON line is dropped; subsequent valid line still routes")
    void readLoop_dropsGarbageButRoutesValid() throws Exception {
        buildTransport();
        CompletableFuture<McpResponse> future = CompletableFuture.supplyAsync(() ->
                transport.sendRequest(McpRequest.of(7, "ping", Map.of()), 5_000L));
        Thread.sleep(50);
        pushServerLine("not valid json {");
        pushServerLine("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":\"pong\"}");
        McpResponse resp = future.get(2, TimeUnit.SECONDS);
        assertThat(resp.result()).isEqualTo("pong");
    }

    @Test
    @DisplayName("Notifications without id are dropped, do not block other requests")
    void notification_dropped() throws Exception {
        buildTransport();
        CompletableFuture<McpResponse> future = CompletableFuture.supplyAsync(() ->
                transport.sendRequest(McpRequest.of(1, "x", Map.of()), 5_000L));
        Thread.sleep(50);
        pushServerLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}");
        pushServerLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"done\"}");
        McpResponse resp = future.get(2, TimeUnit.SECONDS);
        assertThat(resp.result()).isEqualTo("done");
    }

    @Test
    @DisplayName("sendRequest times out cleanly when no response arrives")
    void sendRequest_timeoutThrows() throws Exception {
        buildTransport();
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> transport.sendRequest(
                McpRequest.of(99, "slow", Map.of()), 200L))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("timeout");
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isBetween(150L, 1500L);
    }

    @Test
    @DisplayName("EOF on stdout unblocks pending requests with synthetic error")
    void serverEOF_unblocksPending() throws Exception {
        buildTransport();
        CompletableFuture<McpResponse> future = CompletableFuture.supplyAsync(() ->
                transport.sendRequest(McpRequest.of(33, "stuck", Map.of()), 10_000L));
        Thread.sleep(80);
        // Close server-side stdout → client reader hits EOF.
        serverWritesStdout.close();

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(McpClientException.class);
        assertThat(transport.isAlive()).isFalse();
    }

    @Test
    @DisplayName("close() is idempotent and safe to call without start()")
    void close_idempotent() {
        McpStdioTransport t = new McpStdioTransport("noop", List.of("nonexistent-cmd-xyz"),
                Map.of(), objectMapper);
        // start would actually spawn a process — skip it; just verify close is safe.
        t.close();
        t.close(); // second call: no NPE / no exception
    }

    @Test
    @DisplayName("sendNotification writes single line with no id; no mailbox installed")
    void sendNotification_writesAndReturns() throws Exception {
        buildTransport();
        transport.sendNotification(McpRequest.of(null, "notifications/initialized", Map.of()));
        // Give writer thread a chance — it's synchronous on writeLock so should be done.
        String written = clientWritesStdin.toString(StandardCharsets.UTF_8);
        assertThat(written).contains("\"method\":\"notifications/initialized\"");
        assertThat(written).doesNotContain("\"id\"");
    }

    @Test
    @DisplayName("Numeric id returned as Integer or Long matches by canonical string")
    void canonicalId_matchesNumericWidths() throws Exception {
        buildTransport();
        CompletableFuture<McpResponse> future = CompletableFuture.supplyAsync(() ->
                transport.sendRequest(McpRequest.of(100L, "x", Map.of()), 3_000L));
        Thread.sleep(50);
        // Reply uses int 100 (Jackson may parse as Integer); transport should still match.
        pushServerLine("{\"jsonrpc\":\"2.0\",\"id\":100,\"result\":{}}");
        McpResponse resp = future.get(2, TimeUnit.SECONDS);
        assertThat(resp.result()).isNotNull();
    }
}
