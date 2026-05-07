package com.skillforge.server.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.tools.mcp.adapter.McpToolAdapter;
import com.skillforge.tools.mcp.protocol.McpToolDescriptor;
import com.skillforge.tools.mcp.session.McpServerSession;
import com.skillforge.tools.mcp.transport.McpTransport;
import com.skillforge.tools.mcp.protocol.McpRequest;
import com.skillforge.tools.mcp.protocol.McpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRegistrarTest {

    private SkillRegistry skillRegistry;
    private McpToolRegistrar registrar;
    private final ObjectMapper mapper = new ObjectMapper();

    /** No-op transport — test never calls into it. */
    static class StubTransport implements McpTransport {
        @Override public void start() {}
        @Override public McpResponse sendRequest(McpRequest r, long t) { throw new UnsupportedOperationException(); }
        @Override public void sendNotification(McpRequest n) {}
        @Override public boolean isAlive() { return true; }
        @Override public void close() {}
    }

    /** Test session that returns the cached tools we hand it. */
    static class StubSession extends McpServerSession {
        private final List<McpToolDescriptor> tools;
        StubSession(String name, List<McpToolDescriptor> tools, ObjectMapper m) {
            super(name, new StubTransport(), m);
            this.tools = tools;
        }
        @Override public List<McpToolDescriptor> cachedTools() { return tools; }
    }

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry();
        registrar = new McpToolRegistrar(skillRegistry, mapper);
    }

    @Test
    @DisplayName("registerAllForServer adds mcp_<server>_<tool> tools to SkillRegistry (INV-3)")
    void register_namespacesTools() {
        StubSession s = new StubSession("time", List.of(
                new McpToolDescriptor("get_current_time", "now", Map.of("type", "object")),
                new McpToolDescriptor("convert_time", "convert", Map.of("type", "object"))
        ), mapper);
        List<Map<String, Object>> meta = registrar.registerAllForServer(s);
        assertThat(meta).hasSize(2);
        assertThat(skillRegistry.getTool("mcp_time_get_current_time")).isPresent();
        assertThat(skillRegistry.getTool("mcp_time_convert_time")).isPresent();
        assertThat(registrar.installedNames("time"))
                .containsExactlyInAnyOrder("mcp_time_get_current_time", "mcp_time_convert_time");
    }

    @Test
    @DisplayName("Invalid server name (uppercase / dashes) is refused, no tools registered (INV-3)")
    void register_rejectsInvalidServerName() {
        StubSession s = new StubSession("Bad-Name", List.of(
                new McpToolDescriptor("x", "x", Map.of())
        ), mapper);
        List<Map<String, Object>> meta = registrar.registerAllForServer(s);
        assertThat(meta).isEmpty();
        assertThat(skillRegistry.getTool("mcp_Bad-Name_x")).isEmpty();
    }

    @Test
    @DisplayName("Conflict (existing tool name) refuses second registration, logs error")
    void register_collisionRefused() {
        // Pre-register a tool whose name collides with the namespaced MCP tool
        skillRegistry.registerTool(new com.skillforge.core.skill.Tool() {
            @Override public String getName() { return "mcp_x_y"; }
            @Override public String getDescription() { return ""; }
            @Override public com.skillforge.core.model.ToolSchema getToolSchema() {
                return new com.skillforge.core.model.ToolSchema("mcp_x_y", "", Map.of());
            }
            @Override public com.skillforge.core.skill.SkillResult execute(
                    Map<String, Object> input, com.skillforge.core.skill.SkillContext ctx) {
                return com.skillforge.core.skill.SkillResult.success("");
            }
        });
        StubSession s = new StubSession("x", List.of(
                new McpToolDescriptor("y", "tool y", Map.of())
        ), mapper);
        List<Map<String, Object>> meta = registrar.registerAllForServer(s);
        // No new MCP adapter registered; existing tool remains.
        assertThat(meta).isEmpty();
        assertThat(skillRegistry.getTool("mcp_x_y")).isPresent();
        // The pre-registered tool should still be the one we put there, not an McpToolAdapter
        assertThat(skillRegistry.getTool("mcp_x_y").get())
                .isNotInstanceOf(McpToolAdapter.class);
    }

    @Test
    @DisplayName("unregisterAllForServer removes only the names this registrar installed")
    void unregister_idempotentAndScoped() {
        StubSession s = new StubSession("time", List.of(
                new McpToolDescriptor("a", "", Map.of()),
                new McpToolDescriptor("b", "", Map.of())
        ), mapper);
        registrar.registerAllForServer(s);
        assertThat(skillRegistry.getTool("mcp_time_a")).isPresent();

        registrar.unregisterAllForServer("time");
        assertThat(skillRegistry.getTool("mcp_time_a")).isEmpty();
        assertThat(skillRegistry.getTool("mcp_time_b")).isEmpty();
        assertThat(registrar.installedNames("time")).isEmpty();

        // Calling again is a no-op.
        registrar.unregisterAllForServer("time");
        registrar.unregisterAllForServer("never-installed");
    }

    @Test
    @DisplayName("Re-registering same server replaces the prior install set")
    void register_reregisterReplacesPriorSet() {
        StubSession s1 = new StubSession("time", List.of(
                new McpToolDescriptor("old", "", Map.of())
        ), mapper);
        registrar.registerAllForServer(s1);
        assertThat(skillRegistry.getTool("mcp_time_old")).isPresent();

        StubSession s2 = new StubSession("time", List.of(
                new McpToolDescriptor("new", "", Map.of())
        ), mapper);
        registrar.registerAllForServer(s2);
        // old gone, new in
        assertThat(skillRegistry.getTool("mcp_time_old")).isEmpty();
        assertThat(skillRegistry.getTool("mcp_time_new")).isPresent();
    }
}
