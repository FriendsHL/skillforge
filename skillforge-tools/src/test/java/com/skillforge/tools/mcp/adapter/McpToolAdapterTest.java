package com.skillforge.tools.mcp.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpToolDescriptor;
import com.skillforge.tools.mcp.session.McpServerSession;
import com.skillforge.tools.mcp.transport.McpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Test stub for McpServerSession that bypasses the transport. */
    static class StubSession extends McpServerSession {
        Object cannedResult;
        RuntimeException cannedFailure;
        String lastTool;
        Map<String, Object> lastArgs;

        StubSession(String name, ObjectMapper mapper) {
            super(name, new NoOpTransport(), mapper);
        }

        @Override
        public Object callTool(String toolName, Map<String, Object> arguments) {
            this.lastTool = toolName;
            this.lastArgs = arguments;
            if (cannedFailure != null) throw cannedFailure;
            return cannedResult;
        }
    }

    /** Minimal transport that does nothing — Stub never calls into it. */
    static class NoOpTransport implements McpTransport {
        @Override public void start() {}
        @Override public com.skillforge.tools.mcp.protocol.McpResponse sendRequest(
                com.skillforge.tools.mcp.protocol.McpRequest r, long t) { throw new UnsupportedOperationException(); }
        @Override public void sendNotification(com.skillforge.tools.mcp.protocol.McpRequest n) {}
        @Override public boolean isAlive() { return true; }
        @Override public void close() {}
    }

    private McpToolAdapter adapter(String server, String toolName, Map<String, Object> inputSchema, StubSession sess) {
        McpToolDescriptor desc = new McpToolDescriptor(toolName, "the " + toolName + " tool", inputSchema);
        return new McpToolAdapter(server, sess, desc, mapper);
    }

    @Test
    @DisplayName("buildName produces mcp_<server>_<tool> namespace (INV-3)")
    void buildName_namespace() {
        assertThat(McpToolAdapter.buildName("time", "get_current_time"))
                .isEqualTo("mcp_time_get_current_time");
        StubSession sess = new StubSession("time", mapper);
        McpToolAdapter a = adapter("time", "convert_time",
                Map.of("type", "object"), sess);
        assertThat(a.getName()).isEqualTo("mcp_time_convert_time");
    }

    @Test
    @DisplayName("getToolSchema passes inputSchema through unchanged (INV-11)")
    void schemaPassthrough() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("timezone", Map.of("type", "string")));
        schema.put("required", List.of("timezone"));
        StubSession sess = new StubSession("time", mapper);
        McpToolAdapter a = adapter("time", "get_current_time", schema, sess);
        assertThat(a.getToolSchema().getInputSchema()).isEqualTo(schema);
        assertThat(a.getToolSchema().getName()).isEqualTo("mcp_time_get_current_time");
        assertThat(a.getToolSchema().getDescription()).contains("[mcp:time]");
    }

    @Test
    @DisplayName("execute() concatenates text content into success result")
    void execute_textContent() {
        StubSession sess = new StubSession("time", mapper);
        sess.cannedResult = Map.of(
                "content", List.of(
                        Map.of("type", "text", "text", "Hello "),
                        Map.of("type", "text", "text", "World")),
                "isError", false);
        McpToolAdapter a = adapter("time", "get_current_time",
                Map.of("type", "object"), sess);
        SkillResult result = a.execute(Map.of("timezone", "UTC"), new SkillContext("/", "s", 1L));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Hello World");
        assertThat(sess.lastTool).isEqualTo("get_current_time");
        assertThat(sess.lastArgs).containsEntry("timezone", "UTC");
    }

    @Test
    @DisplayName("execute() routes isError=true into SkillResult.error with text")
    void execute_isErrorTrue() {
        StubSession sess = new StubSession("time", mapper);
        sess.cannedResult = Map.of(
                "content", List.of(Map.of("type", "text", "text", "tz unknown")),
                "isError", true);
        McpToolAdapter a = adapter("time", "convert_time",
                Map.of("type", "object"), sess);
        SkillResult result = a.execute(Map.of(), new SkillContext("/", "s", 1L));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("tz unknown");
    }

    @Test
    @DisplayName("execute() wraps McpClientException into SkillResult.error (INV-1, INV-10)")
    void execute_wrapsClientException() {
        StubSession sess = new StubSession("time", mapper);
        sess.cannedFailure = new McpClientException("server crashed");
        McpToolAdapter a = adapter("time", "x", Map.of("type", "object"), sess);
        SkillResult result = a.execute(Map.of(), new SkillContext("/", "s", 1L));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("server crashed");
    }

    @Test
    @DisplayName("execute() handles non-text content (image / resource) with placeholder")
    void execute_imagePlaceholder() {
        StubSession sess = new StubSession("img", mapper);
        sess.cannedResult = Map.of(
                "content", List.of(
                        Map.of("type", "text", "text", "before "),
                        Map.of("type", "image", "data", "AAAA", "mimeType", "image/png"),
                        Map.of("type", "text", "text", " after")
                ),
                "isError", false);
        McpToolAdapter a = adapter("img", "render", Map.of("type", "object"), sess);
        SkillResult result = a.execute(Map.of(), new SkillContext("/", "s", 1L));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("before ").contains("[image: image/png").contains(" after");
    }

    @Test
    @DisplayName("Empty inputSchema falls back to {type:object,properties:{}} default")
    void schemaFallbackForEmpty() {
        StubSession sess = new StubSession("x", mapper);
        McpToolAdapter a = adapter("x", "noop", Map.of(), sess);
        assertThat(a.getToolSchema().getInputSchema()).containsEntry("type", "object");
    }
}
