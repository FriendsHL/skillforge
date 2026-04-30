package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
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

class AgentLoopEngineSkillLoaderToolTest {

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
    }

    @Test
    void collectTools_exposesOneSkillLoaderInsteadOfOneToolPerSkill() throws Exception {
        SkillDefinition github = skill("github", "Work with GitHub repositories", "# GitHub skill");
        SkillDefinition browser = skill("browser", "Inspect web pages", "# Browser skill");
        LoopContext ctx = contextWithSkills(github, browser);

        List<ToolSchema> tools = collectTools(ctx);

        assertThat(names(tools)).containsExactly("Skill");
        ToolSchema loader = tools.get(0);
        assertThat(loader.getDescription())
                .contains("2 skill")
                .contains("github")
                .contains("Work with GitHub repositories")
                .contains("browser")
                .contains("Inspect web pages");
        assertThat(loader.getInputSchema()).containsEntry("type", "object");
        assertThat(loader.getInputSchema().toString()).contains("name");
    }

    @Test
    void collectTools_reservesSkillNameForTheLoaderTool() throws Exception {
        registry.registerTool(new StubTool("Skill"));
        LoopContext ctx = contextWithSkills(skill("github", "Work with GitHub repositories", "# GitHub skill"));

        List<ToolSchema> tools = collectTools(ctx);

        assertThat(names(tools)).containsExactly("Skill");
        assertThat(tools.get(0).getDescription()).contains("github");
    }

    @Test
    void executeToolCall_loadsAllowedSkillThroughSkillLoaderAndRecordsSkillTelemetry() {
        CapturingRecorder recorder = new CapturingRecorder();
        engine.setSkillTelemetryRecorder(recorder);
        LoopContext ctx = contextWithSkills(skill("github", "Work with GitHub repositories", "# GitHub skill"));

        Message result = engine.executeToolCall(
                new ToolUseBlock("tu-skill", "Skill", Map.of("name", "github")),
                ctx,
                new ArrayList<>(),
                null);

        ContentBlock block = onlyContentBlock(result);
        assertThat(block.getContent()).isEqualTo("# GitHub skill");
        assertThat(block.getIsError()).isFalse();
        assertThat(recorder.calls).containsExactly("github:true:null");
    }

    @Test
    void executeToolCall_rejectsUnknownSkillNameThroughSkillLoader() {
        CapturingRecorder recorder = new CapturingRecorder();
        engine.setSkillTelemetryRecorder(recorder);
        LoopContext ctx = contextWithSkills(skill("github", "Work with GitHub repositories", "# GitHub skill"));

        Message result = engine.executeToolCall(
                new ToolUseBlock("tu-skill", "Skill", Map.of("name", "browser")),
                ctx,
                new ArrayList<>(),
                null);

        ContentBlock block = onlyContentBlock(result);
        assertThat(block.getIsError()).isTrue();
        assertThat(block.getContent()).contains("browser").contains("not available");
        assertThat(recorder.calls).containsExactly("browser:false:NOT_ALLOWED");
    }

    @SuppressWarnings("unchecked")
    private List<ToolSchema> collectTools(LoopContext ctx) throws Exception {
        Method method = AgentLoopEngine.class.getDeclaredMethod(
                "collectTools", LoopContext.class, String.class, Set.class, Set.class);
        method.setAccessible(true);
        return (List<ToolSchema>) method.invoke(engine, ctx, "auto", null, null);
    }

    private static List<String> names(Collection<ToolSchema> tools) {
        return tools.stream().map(ToolSchema::getName).toList();
    }

    private static LoopContext contextWithSkills(SkillDefinition... skills) {
        Map<String, SkillDefinition> allowed = new LinkedHashMap<>();
        Set<String> userSkillNames = new java.util.LinkedHashSet<>();
        for (SkillDefinition skill : skills) {
            allowed.put(skill.getName(), skill);
            userSkillNames.add(skill.getName());
        }
        LoopContext ctx = new LoopContext();
        ctx.setSessionId("s1");
        ctx.setMessages(new ArrayList<>());
        ctx.setSkillView(new SessionSkillView(allowed, Collections.emptySet(), userSkillNames));
        return ctx;
    }

    private static SkillDefinition skill(String name, String description, String promptContent) {
        SkillDefinition skill = new SkillDefinition();
        skill.setName(name);
        skill.setDescription(description);
        skill.setPromptContent(promptContent);
        return skill;
    }

    private static ContentBlock onlyContentBlock(Message message) {
        assertThat(message.getContent()).isInstanceOf(List.class);
        List<?> blocks = (List<?>) message.getContent();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ContentBlock.class);
        return (ContentBlock) blocks.get(0);
    }

    private static class CapturingRecorder implements SkillTelemetryRecorder {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void record(String skillName, boolean success, String errorType) {
            calls.add(skillName + ":" + success + ":" + errorType);
        }
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
