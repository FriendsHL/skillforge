package com.skillforge.tools.mcp.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpError;
import com.skillforge.tools.mcp.protocol.McpRequest;
import com.skillforge.tools.mcp.protocol.McpResponse;
import com.skillforge.tools.mcp.protocol.McpToolDescriptor;
import com.skillforge.tools.mcp.transport.McpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link McpServerSession} using a hand-rolled fake transport. */
class McpServerSessionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Programmable in-memory transport — no process, no IO. */
    static class FakeTransport implements McpTransport {
        boolean started = false;
        boolean closed = false;
        boolean alive = true;
        final List<McpRequest> sentRequests = new ArrayList<>();
        final List<McpRequest> sentNotifications = new ArrayList<>();
        // Per-method canned response. Non-null returns the response; if null, throws.
        java.util.Map<String, McpResponse> canned = new java.util.HashMap<>();
        java.util.Map<String, RuntimeException> cannedFailures = new java.util.HashMap<>();

        @Override public void start() { started = true; }
        @Override public boolean isAlive() { return alive; }
        @Override public void close() { closed = true; alive = false; }

        @Override
        public McpResponse sendRequest(McpRequest req, long timeoutMillis) {
            sentRequests.add(req);
            if (cannedFailures.containsKey(req.method())) {
                throw cannedFailures.get(req.method());
            }
            McpResponse r = canned.get(req.method());
            if (r == null) {
                throw new McpClientException("FakeTransport: no canned response for " + req.method());
            }
            // echo the request id back (server contract)
            return new McpResponse(r.jsonrpc(), req.id(), r.result(), r.error());
        }

        @Override
        public void sendNotification(McpRequest n) {
            sentNotifications.add(n);
        }
    }

    private FakeTransport happyTransport() {
        FakeTransport t = new FakeTransport();
        t.canned.put("initialize", new McpResponse("2.0", null,
                Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of()), null));
        t.canned.put("tools/list", new McpResponse("2.0", null, Map.of("tools", List.of(
                Map.of("name", "get_current_time", "description", "Returns now",
                        "inputSchema", Map.of("type", "object", "properties", Map.of())),
                Map.of("name", "convert_time", "description", "Convert tz",
                        "inputSchema", Map.of("type", "object"))
        )), null));
        return t;
    }

    @Test
    @DisplayName("connect() runs initialize then tools/list and caches descriptors")
    void connect_happyPath() {
        FakeTransport t = happyTransport();
        McpServerSession s = new McpServerSession("time", t, mapper);
        s.connect();
        assertThat(s.isConnected()).isTrue();
        assertThat(t.started).isTrue();
        assertThat(t.sentRequests).extracting(McpRequest::method)
                .containsExactly("initialize", "tools/list");
        assertThat(t.sentNotifications).extracting(McpRequest::method)
                .containsExactly("notifications/initialized");
        assertThat(s.cachedTools()).extracting(McpToolDescriptor::name)
                .containsExactly("get_current_time", "convert_time");
    }

    @Test
    @DisplayName("connect() is idempotent when already connected")
    void connect_idempotent() {
        FakeTransport t = happyTransport();
        McpServerSession s = new McpServerSession("time", t, mapper);
        s.connect();
        s.connect();
        // Second connect should NOT re-issue initialize / tools/list
        assertThat(t.sentRequests).hasSize(2);
    }

    @Test
    @DisplayName("initialize JSON-RPC error throws + transport closed")
    void initialize_errorClosesTransport() {
        FakeTransport t = new FakeTransport();
        t.canned.put("initialize", new McpResponse("2.0", null, null,
                new McpError(-32601, "method not found", null)));
        McpServerSession s = new McpServerSession("bad", t, mapper);
        assertThatThrownBy(s::connect)
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("initialize failed");
        assertThat(t.closed).isTrue();
        assertThat(s.isConnected()).isFalse();
    }

    @Test
    @DisplayName("tools/list error throws + transport closed (cleanup on partial handshake)")
    void toolsList_errorClosesTransport() {
        FakeTransport t = new FakeTransport();
        t.canned.put("initialize", new McpResponse("2.0", null, Map.of(), null));
        t.canned.put("tools/list", new McpResponse("2.0", null, null,
                new McpError(-32603, "internal", null)));
        McpServerSession s = new McpServerSession("x", t, mapper);
        assertThatThrownBy(s::connect)
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("tools/list failed");
        assertThat(t.closed).isTrue();
    }

    @Test
    @DisplayName("disconnect() invalidates cachedTools (INV-8 reconnect re-queries)")
    void disconnect_invalidatesCache() {
        FakeTransport t = happyTransport();
        McpServerSession s = new McpServerSession("time", t, mapper);
        s.connect();
        assertThat(s.cachedTools()).isNotEmpty();
        s.disconnect();
        assertThat(s.isConnected()).isFalse();
        assertThat(s.cachedTools()).isEmpty();
        assertThat(t.closed).isTrue();
    }

    @Test
    @DisplayName("callTool routes to tools/call with name + arguments")
    void callTool_sendsToolsCall() {
        FakeTransport t = happyTransport();
        t.canned.put("tools/call", new McpResponse("2.0", null,
                Map.of("content", List.of(Map.of("type", "text", "text", "ok")), "isError", false), null));
        McpServerSession s = new McpServerSession("time", t, mapper);
        s.connect();
        Object result = s.callTool("get_current_time", Map.of("timezone", "Asia/Shanghai"));
        assertThat(result).isNotNull();
        // last sent request must be tools/call with our payload
        McpRequest last = t.sentRequests.get(t.sentRequests.size() - 1);
        assertThat(last.method()).isEqualTo("tools/call");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) last.params();
        assertThat(params).containsEntry("name", "get_current_time");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) params.get("arguments");
        assertThat(args).containsEntry("timezone", "Asia/Shanghai");
    }

    @Test
    @DisplayName("callTool throws if not connected")
    void callTool_notConnected() {
        FakeTransport t = new FakeTransport();
        McpServerSession s = new McpServerSession("x", t, mapper);
        assertThatThrownBy(() -> s.callTool("foo", Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("not connected");
    }

    @Test
    @DisplayName("callTool wraps tools/call JSON-RPC error into McpClientException")
    void callTool_jsonRpcError() {
        FakeTransport t = happyTransport();
        t.canned.put("tools/call", new McpResponse("2.0", null, null,
                new McpError(-32602, "invalid params", null)));
        McpServerSession s = new McpServerSession("x", t, mapper);
        s.connect();
        assertThatThrownBy(() -> s.callTool("bad_tool", Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("invalid params");
    }
}
