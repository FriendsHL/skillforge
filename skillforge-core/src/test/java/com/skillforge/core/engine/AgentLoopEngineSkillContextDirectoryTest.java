package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopEngineSkillContextDirectoryTest {

    @Test
    void executeToolCall_copiesArtifactOutputDirectoryWithoutReplacingWorkingDirectory() {
        AtomicReference<SkillContext> observedContext = new AtomicReference<>();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new CapturingTool(observedContext));
        AgentLoopEngine engine = new AgentLoopEngine(new LlmProviderFactory(), "none", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        LoopContext loopContext = new LoopContext();
        loopContext.setSessionId("sid");
        loopContext.setWorkingDirectory("/workspace/repository");
        loopContext.setArtifactOutputDirectory("/managed/artifacts/trace-1");

        engine.executeToolCallOutcome(
                new ToolUseBlock("call-1", "CaptureContext", Map.of()),
                loopContext, new CopyOnWriteArrayList<>(), null);

        assertThat(loopContext.getWorkingDirectory()).isEqualTo("/workspace/repository");
        assertThat(observedContext.get().getWorkingDirectory()).isEqualTo("/workspace/repository");
        assertThat(observedContext.get().getArtifactOutputDirectory())
                .isEqualTo("/managed/artifacts/trace-1");
    }

    private static final class CapturingTool implements Tool {
        private final AtomicReference<SkillContext> observedContext;

        private CapturingTool(AtomicReference<SkillContext> observedContext) {
            this.observedContext = observedContext;
        }

        @Override public String getName() { return "CaptureContext"; }
        @Override public String getDescription() { return "captures the skill context"; }
        @Override public ToolSchema getToolSchema() {
            ToolSchema schema = new ToolSchema();
            schema.setName(getName());
            schema.setDescription(getDescription());
            schema.setInputSchema(Map.of("type", "object", "properties", Map.of()));
            return schema;
        }
        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            observedContext.set(context);
            return SkillResult.success("ok");
        }
    }
}
