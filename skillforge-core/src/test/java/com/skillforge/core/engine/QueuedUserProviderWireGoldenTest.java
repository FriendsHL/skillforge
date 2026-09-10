package com.skillforge.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.ClaudeProvider;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.OpenAiProvider;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider wire goldens for provider-only ordered-inbox USER materialization. */
class QueuedUserProviderWireGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIRST = "queued-wire-first";
    private static final String SECOND = "queued-wire-second";

    @Test
    void claudeWire_containsEachQueuedTextOnceAndKeepsToolResultBarrier() throws Exception {
        List<Message> providerMessages = providerMessages();
        LlmRequest request = request(providerMessages);
        ClaudeProvider provider = new ClaudeProvider(
                "test-key", "http://localhost:1", "claude-sonnet-4-20250514");
        Method builder = ClaudeProvider.class.getDeclaredMethod(
                "buildRequestBody", LlmRequest.class, String.class, boolean.class);
        builder.setAccessible(true);

        String raw = (String) builder.invoke(
                provider, request, "claude-sonnet-4-20250514", false);
        JsonNode messages = JSON.readTree(raw).path("messages");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("content").get(0).path("type").asText())
                .isEqualTo("tool_result");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertExactlyOnce(raw, FIRST);
        assertExactlyOnce(raw, SECOND);
    }

    @Test
    void openAiCompatibleWire_containsEachQueuedTextOnceAndKeepsToolResultBarrier()
            throws Exception {
        assertOpenAiFamilyGolden("gpt-4o");
    }

    @Test
    void deepSeekWire_containsEachQueuedTextOnceAndKeepsToolResultBarrier()
            throws Exception {
        assertOpenAiFamilyGolden("deepseek-chat");
    }

    private static void assertOpenAiFamilyGolden(String model) throws Exception {
        OpenAiProvider provider = new OpenAiProvider(
                "test-key", "http://localhost:1", model,
                "golden", "GOLDEN_API_KEY", 60, 1);
        Method builder = OpenAiProvider.class.getDeclaredMethod(
                "buildRequestBody", LlmRequest.class, String.class, boolean.class);
        builder.setAccessible(true);
        String raw = (String) builder.invoke(
                provider, request(providerMessages()), model, false);
        JsonNode messages = JSON.readTree(raw).path("messages");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(0).path("tool_call_id").asText()).isEqualTo("tool-1");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertExactlyOnce(raw, FIRST);
        assertExactlyOnce(raw, SECOND);
    }

    private static List<Message> providerMessages() {
        LoopContext context = new LoopContext();
        Message first = Message.user(FIRST);
        Message second = Message.user(SECOND);
        context.markProviderMergeEligibleUser(first);
        context.markProviderMergeEligibleUser(second);
        return AgentLoopEngine.applyMaterializer(
                context,
                List.of(
                        Message.toolResult("tool-1", "tool evidence", false),
                        first,
                        second));
    }

    private static LlmRequest request(List<Message> messages) {
        LlmRequest request = new LlmRequest();
        request.setMessages(messages);
        request.setModel("golden");
        return request;
    }

    private static void assertExactlyOnce(String value, String token) {
        assertThat(value.indexOf(token)).isGreaterThanOrEqualTo(0);
        assertThat(value.indexOf(token)).isEqualTo(value.lastIndexOf(token));
    }
}
