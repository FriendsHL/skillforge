package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.view.SessionSkillView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the surface/dispatch divergence bug: {@code collectTools} applied the
 * built-in {@code allowedToolNames} (agent.toolIds) whitelist to {@code mcp_*} tools,
 * so an agent with a tightened toolIds whitelist would never see its bound MCP server's
 * tools — even though the dispatch gate ({@code executeToolCall} / {@code isMcpToolAllowed})
 * correctly governs mcp tools solely by {@code allowedMcpServerNames}. The fix exempts
 * {@code mcp_}-prefixed names from the built-in whitelist so surface mirrors dispatch.
 */
class AgentLoopEngineMcpToolWhitelistTest {

    private SkillRegistry registry;
    private AgentLoopEngine engine;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        engine = new AgentLoopEngine(
                new LlmProviderFactory(),
                "unused",
                registry,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        registry.registerTool(new StubTool("Bash"));               // built-in, whitelisted
        registry.registerTool(new StubTool("Grep"));               // built-in, NOT whitelisted
        registry.registerTool(new StubTool("mcp_anysearch_search"));  // mcp, server bound
        registry.registerTool(new StubTool("mcp_anysearch_extract")); // mcp, server bound
        registry.registerTool(new StubTool("mcp_other_search"));   // mcp, server NOT bound
    }

    @Test
    @DisplayName("toolIds whitelist + bound mcp server: mcp tools surface; whitelist still gates built-ins; "
            + "unbound mcp server tools stay hidden")
    void whitelistedAgentStillSeesBoundMcpTools() throws Exception {
        LoopContext ctx = context(Set.of("anysearch"));
        // Tight built-in whitelist that contains NO mcp names (mirrors Research Agent id=5).
        Set<String> allowedToolNames = Set.of("Bash");

        List<String> surfaced = names(collectTools(ctx, allowedToolNames));

        // (1) bound mcp server's tools ARE surfaced despite the whitelist not listing them
        assertThat(surfaced).contains("mcp_anysearch_search", "mcp_anysearch_extract");
        // (2) the whitelist still filters built-in tools normally
        assertThat(surfaced).contains("Bash");
        assertThat(surfaced).doesNotContain("Grep");
        // (3) an mcp server the agent did NOT bind is still filtered by the mcp gate
        assertThat(surfaced).doesNotContain("mcp_other_search");
    }

    @Test
    @DisplayName("no mcp servers bound (allowedMcpServerNames empty): all mcp tools filtered even though "
            + "they bypass the built-in whitelist")
    void noBoundServersHidesAllMcpTools() throws Exception {
        LoopContext ctx = context(Set.of()); // empty = nothing bound
        Set<String> allowedToolNames = Set.of("Bash");

        List<String> surfaced = names(collectTools(ctx, allowedToolNames));

        assertThat(surfaced).contains("Bash");
        assertThat(surfaced).doesNotContain(
                "mcp_anysearch_search", "mcp_anysearch_extract", "mcp_other_search", "Grep");
    }

    @Test
    @DisplayName("allowedMcpServerNames null (mcp feature/legacy): mcp tools bypass the built-in whitelist "
            + "and are not gated")
    void nullMcpGateLeavesMcpToolsBypassingWhitelist() throws Exception {
        LoopContext ctx = context(null); // null = no mcp gate
        Set<String> allowedToolNames = Set.of("Bash");

        List<String> surfaced = names(collectTools(ctx, allowedToolNames));

        // mcp tools bypass the whitelist (fix) and the null gate doesn't filter them
        assertThat(surfaced).contains("Bash", "mcp_anysearch_search", "mcp_other_search");
        assertThat(surfaced).doesNotContain("Grep");
    }

    @SuppressWarnings("unchecked")
    private List<ToolSchema> collectTools(LoopContext ctx, Set<String> allowedToolNames) throws Exception {
        Method method = AgentLoopEngine.class.getDeclaredMethod(
                "collectTools", LoopContext.class, String.class, Set.class, Set.class);
        method.setAccessible(true);
        return (List<ToolSchema>) method.invoke(engine, ctx, "auto", null, allowedToolNames);
    }

    private static LoopContext context(Set<String> allowedMcpServerNames) {
        LoopContext ctx = new LoopContext();
        ctx.setSessionId("s1");
        ctx.setMessages(new ArrayList<>());
        // Empty skill view → no Skill loader tool in the surfaced list (keeps assertions focused).
        ctx.setSkillView(new SessionSkillView(
                new LinkedHashMap<>(), Collections.emptySet(), Collections.emptySet()));
        ctx.setAllowedMcpServerNames(allowedMcpServerNames);
        return ctx;
    }

    private static List<String> names(Collection<ToolSchema> tools) {
        return tools.stream().map(ToolSchema::getName).toList();
    }

    private static class StubTool implements Tool {
        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "stub";
        }

        @Override
        public ToolSchema getToolSchema() {
            return new ToolSchema(name, "stub", Map.of("type", "object"));
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            return SkillResult.success("stub");
        }
    }
}
