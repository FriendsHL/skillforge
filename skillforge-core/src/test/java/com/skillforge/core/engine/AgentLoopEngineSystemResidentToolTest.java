package com.skillforge.core.engine;

import com.skillforge.core.context.LowTrustContextBoundary;
import com.skillforge.core.context.PromptSourceType;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.PreWrappedLowTrustTool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.SystemResidentTool;
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

class AgentLoopEngineSystemResidentToolTest {

    private SkillRegistry registry;
    private AgentLoopEngine engine;
    private StubSystemTool search;
    private StubSystemTool read;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        engine = new AgentLoopEngine(
                new LlmProviderFactory(), "unused", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        search = new StubSystemTool("SessionHistorySearch");
        read = new StubSystemTool("SessionHistoryRead");
        registry.registerTool(search);
        registry.registerTool(read);
        registry.registerTool(new StubTool("GetSessionMessages"));
        registry.registerTool(new StubTool("Bash"));
    }

    @Test
    void availableSystemPairBypassesAgentAllowAndExcludeFiltersAndSuppressesLegacy() throws Exception {
        search.status = SystemResidentTool.Status.AVAILABLE;
        read.status = SystemResidentTool.Status.AVAILABLE;
        engine.setToolCatalogEnabled(true);
        engine.setDeferredToolSchemasEnabled(true);

        List<String> names = names(collectTools(
                context(),
                Set.of("SessionHistorySearch", "SessionHistoryRead"),
                Set.of("Bash", "GetSessionMessages")));

        assertThat(names).contains("SessionHistorySearch", "SessionHistoryRead", "Bash")
                .doesNotContain("GetSessionMessages");
    }

    @Test
    void disabledPairIsHiddenAndLeavesConfiguredLegacyToolVisible() throws Exception {
        search.status = SystemResidentTool.Status.DISABLED;
        read.status = SystemResidentTool.Status.DISABLED;

        List<String> names = names(collectTools(
                context(), null, Set.of("GetSessionMessages")));

        assertThat(names).containsExactly("GetSessionMessages");
    }

    @Test
    void enabledButUnavailablePairHidesBothPairAndLegacyFailClosed() throws Exception {
        search.status = SystemResidentTool.Status.UNAVAILABLE;
        read.status = SystemResidentTool.Status.UNAVAILABLE;

        List<String> names = names(collectTools(
                context(), null, Set.of("GetSessionMessages")));

        assertThat(names).isEmpty();
    }

    @Test
    void mixedPairStatusFailsClosedAtomically() throws Exception {
        search.status = SystemResidentTool.Status.AVAILABLE;
        read.status = SystemResidentTool.Status.UNAVAILABLE;

        assertThat(names(collectTools(context(), null, Set.of("GetSessionMessages"))))
                .isEmpty();
    }

    @Test
    void supersededLegacyDirectDispatchIsRejectedBeforeExecution() {
        search.status = SystemResidentTool.Status.AVAILABLE;
        read.status = SystemResidentTool.Status.AVAILABLE;

        Message result = engine.executeToolCall(
                new ToolUseBlock("legacy-call", "GetSessionMessages", Map.of()),
                context(), new ArrayList<>(), null);

        assertThat(result.getTextContent()).contains("not available");
    }

    @Test
    void preWrappedHistoryResultReceivesExactlyOneLowTrustBoundary() {
        SkillRegistry isolated = new SkillRegistry();
        isolated.registerTool(new PreWrappedHistoryTool());
        AgentLoopEngine isolatedEngine = new AgentLoopEngine(
                new LlmProviderFactory(), "unused", isolated,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        isolatedEngine.setContextAssemblyEnabled(true);

        Message result = isolatedEngine.executeToolCall(
                new ToolUseBlock("history-call", "SessionHistorySearch", Map.of()),
                context(), new ArrayList<>(), null);

        assertThat(occurrences(result.getTextContent(), "<context-data source=\"history\""))
                .isEqualTo(1);
        assertThat(occurrences(result.getTextContent(), "</context-data>"))
                .isEqualTo(1);
        assertThat(result.getTextContent()).doesNotContain(
                "&lt;context-data", "&lt;/context-data&gt;");
    }

    @SuppressWarnings("unchecked")
    private List<ToolSchema> collectTools(
            LoopContext context,
            Set<String> excluded,
            Set<String> allowed) throws Exception {
        Method method = AgentLoopEngine.class.getDeclaredMethod(
                "collectTools", LoopContext.class, String.class, Set.class, Set.class);
        method.setAccessible(true);
        return (List<ToolSchema>) method.invoke(engine, context, "auto", excluded, allowed);
    }

    private static LoopContext context() {
        LoopContext context = new LoopContext();
        context.setSessionId("session-1");
        context.setUserId(41L);
        context.setMessages(new ArrayList<>());
        context.setSkillView(new SessionSkillView(
                new LinkedHashMap<>(), Collections.emptySet(), Collections.emptySet()));
        return context;
    }

    private static List<String> names(Collection<ToolSchema> tools) {
        return tools.stream().map(ToolSchema::getName).toList();
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static class StubTool implements Tool {
        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "stub"; }
        @Override public ToolSchema getToolSchema() {
            return new ToolSchema(name, "stub", Map.of(
                    "type", "object", "properties", Map.of(), "additionalProperties", false));
        }
        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            return SkillResult.success("executed:" + name);
        }
    }

    private static class StubSystemTool extends StubTool implements SystemResidentTool {
        protected Status status = Status.DISABLED;

        private StubSystemTool(String name) {
            super(name);
        }

        @Override public Status getSystemToolStatus(SkillContext context) {
            return status;
        }

        @Override public Set<String> getSupersededToolNames() {
            return Set.of("GetSessionMessages");
        }

        @Override public String getSystemToolGroup() {
            return "session-history";
        }
    }

    private static final class PreWrappedHistoryTool extends StubSystemTool
            implements PreWrappedLowTrustTool {

        private PreWrappedHistoryTool() {
            super("SessionHistorySearch");
            status = Status.AVAILABLE;
        }

        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            return SkillResult.success(LowTrustContextBoundary.wrap(
                    PromptSourceType.HISTORY, "{\"schemaVersion\":1}"));
        }
    }
}
