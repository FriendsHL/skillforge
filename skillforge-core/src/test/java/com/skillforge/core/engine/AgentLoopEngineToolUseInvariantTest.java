package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopEngineToolUseInvariantTest {

    @Test
    @DisplayName("toolUseBlocks execute even when provider stopReason is end_turn")
    void toolUseBlocksWithEndTurnStillExecuteAndAppendToolResult() {
        RecordingTool tool = new RecordingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);

        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", new QueueProvider(List.of(
                toolResponse("call-1", "Echo", Map.of("value", "hello"), "end_turn"),
                textResponse("done"))));

        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        AgentDefinition agent = new AgentDefinition();
        agent.setName("test-agent");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("You are a test agent.");
        agent.setConfig(Map.of("max_loops", 3));

        LoopResult result = engine.run(agent, "start", new ArrayList<>(), "sid", 1L);

        assertThat(tool.calls.get()).isEqualTo(1);
        assertThat(result.getFinalResponse()).isEqualTo("done");

        List<Message> messages = result.getMessages();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(1).getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(messages.get(1).getToolUseBlocks()).extracting(ToolUseBlock::getId)
                .containsExactly("call-1");

        assertThat(messages.get(2).getRole()).isEqualTo(Message.Role.USER);
        assertThat(messages.get(2).getContent()).isInstanceOf(List.class);
        Object block = ((List<?>) messages.get(2).getContent()).get(0);
        assertThat(block).isInstanceOf(ContentBlock.class);
        ContentBlock toolResult = (ContentBlock) block;
        assertThat(toolResult.getType()).isEqualTo("tool_result");
        assertThat(toolResult.getToolUseId()).isEqualTo("call-1");
        assertThat(toolResult.getContent()).isEqualTo("echo hello");
    }

    private static LlmResponse toolResponse(String id, String name, Map<String, Object> input, String stopReason) {
        LlmResponse response = new LlmResponse();
        response.setStopReason(stopReason);
        response.setToolUseBlocks(List.of(new ToolUseBlock(id, name, input)));
        return response;
    }

    private static LlmResponse textResponse(String content) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setContent(content);
        return response;
    }

    private static class QueueProvider implements LlmProvider {
        private final Queue<LlmResponse> responses;

        QueueProvider(List<LlmResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override public String getName() {
            return "fake";
        }

        @Override public LlmResponse chat(LlmRequest request) {
            return responses.remove();
        }

        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            handler.onComplete(responses.remove());
        }
    }

    private static class RecordingTool implements Tool {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public String getName() {
            return "Echo";
        }

        @Override public String getDescription() {
            return "test echo tool";
        }

        @Override public ToolSchema getToolSchema() {
            ToolSchema schema = new ToolSchema();
            schema.setName("Echo");
            schema.setDescription("test echo tool");
            schema.setInputSchema(Map.of(
                    "type", "object",
                    "properties", Map.of("value", Map.of("type", "string")),
                    "required", List.of("value")));
            return schema;
        }

        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            calls.incrementAndGet();
            return SkillResult.success("echo " + input.get("value"));
        }
    }
}
