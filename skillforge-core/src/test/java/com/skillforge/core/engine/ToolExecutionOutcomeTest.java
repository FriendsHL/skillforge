package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.PublishedArtifact;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionOutcomeTest {

    @Test
    void executeToolCallOutcome_successRetainsArtifacts() {
        ToolExecutionOutcome outcome = execute(new ResultTool(SkillResult.success("ok", List.of(artifact()))));

        assertThat(outcome.toolResult().getTextContent()).isEqualTo("ok");
        assertThat(outcome.artifacts()).containsExactly(artifact());
    }

    @Test
    void executeToolCallOutcome_errorDiscardsArtifacts() {
        SkillResult error = SkillResult.error("failed");
        error.setArtifacts(List.of(artifact()));

        ToolExecutionOutcome outcome = execute(new ResultTool(error));

        assertThat(outcome.toolResult().getTextContent()).isEqualTo("failed");
        assertThat(outcome.artifacts()).isEmpty();
    }

    @Test
    void executeToolCallOutcome_truncatedOutputDiscardsArtifacts() {
        ToolExecutionOutcome outcome = execute(new ResultTool(
                SkillResult.success("x".repeat(120_000), List.of(artifact()))));

        assertThat(outcome.toolResult().getTextContent()).hasSizeLessThan(120_000);
        assertThat(outcome.artifacts()).isEmpty();
    }

    @Test
    void orderedOutcome_timedOutIndexDiscardsLateSuccessfulArtifacts() {
        ToolExecutionOutcome lateSuccess = new ToolExecutionOutcome(
                com.skillforge.core.model.Message.toolResult("call-1", "late", false),
                List.of(artifact()));

        ToolExecutionOutcome selected = AgentLoopEngine.orderedOutcome(
                0, new ToolUseBlock("call-1", "Publish", Map.of()),
                Map.of(0, lateSuccess), java.util.Set.of(0));

        assertThat(selected.toolResult().getTextContent()).contains("timed out");
        assertThat(selected.artifacts()).isEmpty();
    }

    private static ToolExecutionOutcome execute(Tool tool) {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = new AgentLoopEngine(new LlmProviderFactory(), "none", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        LoopContext context = new LoopContext();
        context.setSessionId("sid");
        return engine.executeToolCallOutcome(
                new ToolUseBlock("call-1", tool.getName(), Map.of()), context,
                new java.util.concurrent.CopyOnWriteArrayList<>(), null);
    }

    private static PublishedArtifact artifact() {
        return new PublishedArtifact("att-1", "pdf_ref", "report.pdf",
                "application/pdf", 2, null, "Report");
    }

    private static final class ResultTool implements Tool {
        private final SkillResult result;
        private ResultTool(SkillResult result) { this.result = result; }
        @Override public String getName() { return "Publish"; }
        @Override public String getDescription() { return "test"; }
        @Override public ToolSchema getToolSchema() {
            ToolSchema schema = new ToolSchema();
            schema.setName(getName());
            schema.setDescription(getDescription());
            schema.setInputSchema(Map.of("type", "object", "properties", Map.of()));
            return schema;
        }
        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) { return result; }
    }
}
