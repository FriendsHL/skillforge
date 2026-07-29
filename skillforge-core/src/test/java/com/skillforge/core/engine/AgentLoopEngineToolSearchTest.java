package com.skillforge.core.engine;

import com.skillforge.core.capability.ToolSearchCapability;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.view.SessionSkillView;
import org.junit.jupiter.api.BeforeEach;
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

class AgentLoopEngineToolSearchTest {

    private SkillRegistry registry;
    private AgentLoopEngine engine;
    private LoopContext context;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        registry.registerTool(new StubTool("Bash", "Run shell commands"));
        registry.registerTool(new StubTool("mcp_web_search", "Search the public web"));
        engine = new AgentLoopEngine(
                new LlmProviderFactory(), "unused", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        engine.setToolCatalogEnabled(true);
        engine.setToolSearchEnabled(true);
        engine.setDeferredToolSchemasEnabled(true);
        context = new LoopContext();
        context.setSessionId("s1");
        context.setMessages(new ArrayList<>());
        context.setSkillView(new SessionSkillView(
                new LinkedHashMap<>(), Set.of(), Set.of()));
        context.setAllowedMcpServerNames(Set.of("web"));
    }

    @Test
    void toolSearch_discoversAuthorizedDeferredSchemaForNextCollection() throws Exception {
        assertThat(names(collectTools()))
                .containsExactly("Bash", ToolSearchCapability.NAME);

        Message result = engine.executeToolCall(
                new ToolUseBlock(
                        "tu-search", ToolSearchCapability.NAME,
                        Map.of("query", "public web search")),
                context, new ArrayList<>(), null);

        assertThat(onlyBlock(result).getIsError()).isFalse();
        assertThat(onlyBlock(result).getContent()).contains("mcp_web_search");
        assertThat(names(collectTools()))
                .containsExactly("Bash", ToolSearchCapability.NAME, "mcp_web_search");
        assertThat(context.getToolDiscoveryState().discoveredToolIds())
                .containsExactly("tool:mcp_web_search");
    }

    @Test
    void guessedDeferredToolName_isRejectedUntilDiscovered() throws Exception {
        collectTools();

        Message result = engine.executeToolCall(
                new ToolUseBlock(
                        "tu-guessed", "mcp_web_search", Map.of("query", "secret")),
                context, new ArrayList<>(), null);

        assertThat(onlyBlock(result).getIsError()).isTrue();
        assertThat(onlyBlock(result).getContent())
                .contains("NOT DISCOVERED")
                .contains("ToolSearch");
    }

    @SuppressWarnings("unchecked")
    private List<ToolSchema> collectTools() throws Exception {
        Method method = AgentLoopEngine.class.getDeclaredMethod(
                "collectTools", LoopContext.class, String.class, Set.class, Set.class);
        method.setAccessible(true);
        return (List<ToolSchema>) method.invoke(engine, context, "auto", null, null);
    }

    private static List<String> names(Collection<ToolSchema> tools) {
        return tools.stream().map(ToolSchema::getName).toList();
    }

    private static ContentBlock onlyBlock(Message message) {
        assertThat(message.getContent()).isInstanceOf(List.class);
        List<?> blocks = (List<?>) message.getContent();
        assertThat(blocks).hasSize(1);
        return (ContentBlock) blocks.get(0);
    }

    private static final class StubTool implements Tool {
        private final String name;
        private final String description;

        private StubTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return description; }
        @Override public ToolSchema getToolSchema() {
            return new ToolSchema(name, description, Map.of(
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string"))));
        }
        @Override public SkillResult execute(
                Map<String, Object> input, SkillContext context) {
            return SkillResult.success("executed");
        }
    }
}
