package com.skillforge.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ReasoningEffort;
import com.skillforge.core.model.ThinkingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the exact JSON shape of {@link OpenAiProvider#buildRequestBody} under the
 * full family × thinkingMode × reasoningEffort × tool-call replay matrix (plan §7.1).
 *
 * <p>Uses reflection to invoke the private body builder — keeps the public API clean and
 * avoids pulling in MockWebServer as a new test-scope dependency.</p>
 */
class OpenAiProviderThinkingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiProvider provider;
    private Method buildRequestBody;

    @BeforeEach
    void setUp() throws Exception {
        provider = new OpenAiProvider("test-key", "http://localhost:1", "gpt-4o",
                "bailian", "DASHSCOPE_API_KEY", 60, 1);
        buildRequestBody = OpenAiProvider.class.getDeclaredMethod(
                "buildRequestBody", LlmRequest.class, String.class, boolean.class);
        buildRequestBody.setAccessible(true);
    }

    private JsonNode body(LlmRequest request, String model) throws Exception {
        String raw = (String) buildRequestBody.invoke(provider, request, model, false);
        return JSON.readTree(raw);
    }

    // ---------- QWEN_DASHSCOPE ----------

    @Test
    @DisplayName("qwen auto / no effort → no thinking fields emitted")
    void qwen_auto_noFieldsEmitted() throws Exception {
        LlmRequest req = simpleRequest();
        JsonNode b = body(req, "qwen3.5-plus");
        assertThat(b.has("enable_thinking")).isFalse();
        assertThat(b.has("thinking")).isFalse();
        assertThat(b.has("reasoning_effort")).isFalse();
    }

    @Test
    @DisplayName("qwen enabled → top-level enable_thinking=true")
    void qwen_enabled_topLevelTrue() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.ENABLED);
        JsonNode b = body(req, "qwen3.5-plus");
        assertThat(b.get("enable_thinking").isBoolean()).isTrue();
        assertThat(b.get("enable_thinking").asBoolean()).isTrue();
        assertThat(b.has("thinking")).isFalse();
    }

    @Test
    @DisplayName("qwen disabled → top-level enable_thinking=false")
    void qwen_disabled_topLevelFalse() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.DISABLED);
        JsonNode b = body(req, "qwen3.5-plus");
        assertThat(b.get("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("qwen with reasoning_effort=high → effort not emitted (family doesn't support it)")
    void qwen_reasoningEffortIgnored() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.DISABLED);
        req.setReasoningEffort(ReasoningEffort.HIGH);
        JsonNode b = body(req, "qwen3.5-plus");
        assertThat(b.has("reasoning_effort")).isFalse();
        assertThat(b.get("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("qwen tool-call replay with null reasoning_content → emit \"\" fallback")
    void qwen_toolCallReplay_nullReasoning_emitsEmptyString() throws Exception {
        LlmRequest req = toolCallHistoryRequest(null);
        req.setThinkingMode(ThinkingMode.ENABLED);
        JsonNode b = body(req, "qwen3.5-plus");
        JsonNode assistant = findAssistantWithToolCalls(b);
        assertThat(assistant).isNotNull();
        assertThat(assistant.has("reasoning_content")).isTrue();
        assertThat(assistant.get("reasoning_content").asText()).isEmpty();
    }

    @Test
    @DisplayName("qwen tool-call replay with stored reasoning → emit stored verbatim")
    void qwen_toolCallReplay_storedReasoning_emitsStored() throws Exception {
        LlmRequest req = toolCallHistoryRequest("real thought");
        req.setThinkingMode(ThinkingMode.ENABLED);
        JsonNode b = body(req, "qwen3.5-plus");
        JsonNode assistant = findAssistantWithToolCalls(b);
        assertThat(assistant.get("reasoning_content").asText()).isEqualTo("real thought");
    }

    // ---------- DEEPSEEK_V4 ----------

    @Test
    @DisplayName("deepseek-v4 enabled + high → top-level thinking.enabled + reasoning_effort")
    void deepseekV4_enabled_withEffort() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.ENABLED);
        req.setReasoningEffort(ReasoningEffort.HIGH);
        JsonNode b = body(req, "deepseek-v4-pro");
        assertThat(b.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(b.get("reasoning_effort").asText()).isEqualTo("high");
        assertThat(b.has("enable_thinking")).isFalse();
    }

    @Test
    @DisplayName("deepseek-v4 disabled / no effort → thinking.disabled, no reasoning_effort")
    void deepseekV4_disabled_noEffort() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.DISABLED);
        JsonNode b = body(req, "deepseek-v4-pro");
        assertThat(b.get("thinking").get("type").asText()).isEqualTo("disabled");
        assertThat(b.has("reasoning_effort")).isFalse();
    }

    @Test
    @DisplayName("deepseek-v4 tool-call replay with null reasoning → emit \"\" (fix for 400)")
    void deepseekV4_toolCallReplay_nullReasoning_emitsEmptyString() throws Exception {
        LlmRequest req = toolCallHistoryRequest(null);
        req.setThinkingMode(ThinkingMode.ENABLED);
        JsonNode b = body(req, "deepseek-v4-pro");
        JsonNode assistant = findAssistantWithToolCalls(b);
        assertThat(assistant.has("reasoning_content")).isTrue();
        assertThat(assistant.get("reasoning_content").asText()).isEmpty();
    }

    @Test
    @DisplayName("deepseek-v4 tool-call replay with stored reasoning → emit stored")
    void deepseekV4_toolCallReplay_storedReasoning_emitsStored() throws Exception {
        LlmRequest req = toolCallHistoryRequest("analysis");
        req.setThinkingMode(ThinkingMode.ENABLED);
        JsonNode b = body(req, "deepseek-v4-pro");
        JsonNode assistant = findAssistantWithToolCalls(b);
        assertThat(assistant.get("reasoning_content").asText()).isEqualTo("analysis");
    }

    @Test
    @DisplayName("deepseek-v4 auto → no thinking / no reasoning_effort (preserves provider default)")
    void deepseekV4_auto_noFields() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.AUTO);
        JsonNode b = body(req, "deepseek-v4-pro");
        assertThat(b.has("thinking")).isFalse();
        assertThat(b.has("reasoning_effort")).isFalse();
    }

    // ---------- DEEPSEEK_REASONER_LEGACY ----------

    @Test
    @DisplayName("deepseek-reasoner replay drops reasoning_content even when stored")
    void deepseekReasoner_droppedOnReplay() throws Exception {
        LlmRequest req = toolCallHistoryRequest("should-be-dropped");
        JsonNode b = body(req, "deepseek-reasoner");
        JsonNode assistant = findAssistantWithToolCalls(b);
        assertThat(assistant).isNotNull();
        assertThat(assistant.has("reasoning_content")).isFalse();
    }

    // ---------- DEEPSEEK_CHAT_LEGACY ----------

    @Test
    @DisplayName("deepseek-chat ignores thinking / reasoning_effort")
    void deepseekChat_ignoresThinking() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.ENABLED);
        req.setReasoningEffort(ReasoningEffort.HIGH);
        JsonNode b = body(req, "deepseek-chat");
        assertThat(b.has("thinking")).isFalse();
        assertThat(b.has("enable_thinking")).isFalse();
        assertThat(b.has("reasoning_effort")).isFalse();
    }

    // ---------- OPENAI_REASONING ----------

    @Test
    @DisplayName("o3 reasoning_effort emitted; no thinking toggle")
    void o3_reasoningEffortOnly() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.AUTO);
        req.setReasoningEffort(ReasoningEffort.HIGH);
        JsonNode b = body(req, "o3-mini");
        assertThat(b.get("reasoning_effort").asText()).isEqualTo("high");
        assertThat(b.has("thinking")).isFalse();
        assertThat(b.has("enable_thinking")).isFalse();
    }

    // ---------- GENERIC_OPENAI ----------

    @Test
    @DisplayName("generic model ignores thinking fields entirely")
    void generic_openAI_ignoresAll() throws Exception {
        LlmRequest req = simpleRequest();
        req.setThinkingMode(ThinkingMode.ENABLED);
        req.setReasoningEffort(ReasoningEffort.HIGH);
        JsonNode b = body(req, "gpt-4o");
        assertThat(b.has("thinking")).isFalse();
        assertThat(b.has("enable_thinking")).isFalse();
        assertThat(b.has("reasoning_effort")).isFalse();
    }

    // ---------- V22 regression guard: simple-text assistant with stored reasoning ----------

    @Test
    @DisplayName("V22 regression guard: simple-text assistant with stored reasoning still emits")
    void v22_regressionGuard_simpleTextAssistant_emitsStored() throws Exception {
        LlmRequest req = new LlmRequest();
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user("hello"));
        Message asst = Message.assistant("response");
        asst.setReasoningContent("historic thought");
        msgs.add(asst);
        msgs.add(Message.user("continue"));
        req.setMessages(msgs);
        // Any family that isn't LEGACY / doesn't require replay — GENERIC covers the
        // V22 path for non-qwen/deepseek users.
        JsonNode b = body(req, "qwen3.5-plus");
        // Find the assistant message (simple text, no tool_calls) and check reasoning_content
        JsonNode messages = b.get("messages");
        JsonNode assistantMsg = null;
        for (JsonNode m : messages) {
            if ("assistant".equals(m.path("role").asText())) {
                assistantMsg = m;
                break;
            }
        }
        assertThat(assistantMsg).isNotNull();
        assertThat(assistantMsg.get("reasoning_content").asText()).isEqualTo("historic thought");
    }

    @Test
    @DisplayName("simple-text assistant with null reasoning on generic family → no emit")
    void simpleTextAssistant_nullReasoning_genericFamily_noEmit() throws Exception {
        LlmRequest req = new LlmRequest();
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user("hello"));
        msgs.add(Message.assistant("response"));
        msgs.add(Message.user("continue"));
        req.setMessages(msgs);
        JsonNode b = body(req, "gpt-4o");
        for (JsonNode m : b.get("messages")) {
            assertThat(m.has("reasoning_content")).isFalse();
        }
    }

    // ---------- Helpers ----------

    private LlmRequest simpleRequest() {
        LlmRequest req = new LlmRequest();
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user("hi"));
        req.setMessages(msgs);
        return req;
    }

    /**
     * Builds a 2-turn tool_call history: user question → assistant with tool_calls
     * (reasoning stored per arg) → user tool_result → user follow-up.
     */
    private LlmRequest toolCallHistoryRequest(String storedReasoning) {
        LlmRequest req = new LlmRequest();
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user("weather?"));

        Message asst = new Message();
        asst.setRole(Message.Role.ASSISTANT);
        List<Object> blocks = new ArrayList<>();
        // Must use ContentBlock.toolUse(...) so Message.getToolUseBlocks() can find them;
        // a bare ToolUseBlock instance is not recognised by that helper.
        blocks.add(ContentBlock.toolUse("call_1", "get_weather", Map.of("city", "Beijing")));
        asst.setContent(blocks);
        if (storedReasoning != null) {
            asst.setReasoningContent(storedReasoning);
        }
        msgs.add(asst);

        // Tool result
        Message toolResult = new Message();
        toolResult.setRole(Message.Role.USER);
        List<Object> resultBlocks = new ArrayList<>();
        ContentBlock trBlock = new ContentBlock();
        trBlock.setType("tool_result");
        trBlock.setToolUseId("call_1");
        trBlock.setContent("sunny, 22C");
        resultBlocks.add(trBlock);
        toolResult.setContent(resultBlocks);
        msgs.add(toolResult);

        msgs.add(Message.user("thanks"));
        req.setMessages(msgs);
        return req;
    }

    /** Locate the assistant message that carries tool_calls. */
    private JsonNode findAssistantWithToolCalls(JsonNode body) {
        JsonNode messages = body.get("messages");
        for (JsonNode m : messages) {
            if ("assistant".equals(m.path("role").asText()) && m.has("tool_calls")) {
                return m;
            }
        }
        return null;
    }
}
