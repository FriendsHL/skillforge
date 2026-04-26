package com.skillforge.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-F-3: defensive type filter in OpenAiProvider.convertMessages.
 *
 * <p>When a user message contains mixed blocks (text + tool_result + ...), only the
 * tool_result blocks must be emitted as {@code role:tool} messages. Non-tool_result
 * blocks (text, image, tool_use, raw maps with other types) are silently dropped to
 * avoid producing {@code role:tool, tool_call_id:"null"} payloads that DeepSeek
 * rejects with HTTP 400.
 *
 * <p>The fix is the defensive layer for legacy {@code acbced3f}-class sessions whose
 * stored content was created by the now-deleted {@code mergeSummaryIntoUser} branch.
 * Root cause is fixed by BUG-F-1; this test pins the recovery behavior.
 */
class OpenAiProviderConvertMessagesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiProvider provider;
    private Method buildRequestBody;

    @BeforeEach
    void setUp() throws Exception {
        provider = new OpenAiProvider("test-key", "http://localhost:1", "gpt-4o",
                "deepseek", "DEEPSEEK_API_KEY", 60, 1);
        buildRequestBody = OpenAiProvider.class.getDeclaredMethod(
                "buildRequestBody", LlmRequest.class, String.class, boolean.class);
        buildRequestBody.setAccessible(true);
    }

    private JsonNode body(LlmRequest req) throws Exception {
        String raw = (String) buildRequestBody.invoke(provider, req, "deepseek-chat", false);
        return JSON.readTree(raw);
    }

    private LlmRequest reqWith(Message... messages) {
        LlmRequest r = new LlmRequest();
        List<Message> list = new ArrayList<>();
        for (Message m : messages) list.add(m);
        r.setMessages(list);
        return r;
    }

    private Message userBlocks(Object... blocks) {
        Message m = new Message();
        m.setRole(Message.Role.USER);
        List<Object> list = new ArrayList<>();
        for (Object b : blocks) list.add(b);
        m.setContent(list);
        return m;
    }

    private List<JsonNode> nonSystemMessages(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode m : body.get("messages")) {
            if (!"system".equals(m.path("role").asText())) {
                out.add(m);
            }
        }
        return out;
    }

    // ---------- Tests ----------

    @Test
    @DisplayName("pure tool_result-form user message → emits one role:tool per block")
    void pureToolResultForm_emitsOneToolPerBlock() throws Exception {
        Message msg = userBlocks(
                ContentBlock.toolResult("A", "result-A", false),
                ContentBlock.toolResult("B", "result-B", false));
        JsonNode b = body(reqWith(msg));

        List<JsonNode> ms = nonSystemMessages(b);
        assertThat(ms).hasSize(2);
        assertThat(ms.get(0).path("role").asText()).isEqualTo("tool");
        assertThat(ms.get(0).path("tool_call_id").asText()).isEqualTo("A");
        assertThat(ms.get(0).path("content").asText()).isEqualTo("result-A");
        assertThat(ms.get(1).path("role").asText()).isEqualTo("tool");
        assertThat(ms.get(1).path("tool_call_id").asText()).isEqualTo("B");
        assertThat(ms.get(1).path("content").asText()).isEqualTo("result-B");
    }

    @Test
    @DisplayName("mixed user message (text + tool_result) → text dropped, only tool emitted")
    void mixedUserMessage_dropsTextEmitsOnlyToolBlocks() throws Exception {
        // The acbced3f-class shape: a single user message whose blocks are
        // [text("...summary..."), tool_result(A), tool_result(B)].
        Message msg = userBlocks(
                ContentBlock.text("[Context summary from old session]\nblah blah"),
                ContentBlock.toolResult("A", "result-A", false),
                ContentBlock.toolResult("B", "result-B", false));
        JsonNode b = body(reqWith(msg));

        List<JsonNode> ms = nonSystemMessages(b);
        // Two role:tool messages, no role:user from the dropped text block, no
        // duplicate "tool_call_id":"null" payloads.
        assertThat(ms).hasSize(2);
        for (JsonNode m : ms) {
            assertThat(m.path("role").asText()).isEqualTo("tool");
            assertThat(m.path("tool_call_id").asText()).isNotEqualTo("null");
        }
        assertThat(ms.get(0).path("tool_call_id").asText()).isEqualTo("A");
        assertThat(ms.get(1).path("tool_call_id").asText()).isEqualTo("B");

        // Whole-body string scan: no `"tool_call_id":"null"` literal should ever
        // be emitted (this was the root of the DeepSeek 400).
        assertThat(b.toString()).doesNotContain("\"tool_call_id\":\"null\"");
    }

    @Test
    @DisplayName("pure text user message (no tool_result) → emits role:user with text")
    void pureTextUserMessage_emitsRoleUser() throws Exception {
        Message msg = userBlocks(ContentBlock.text("hello"));
        JsonNode b = body(reqWith(msg));

        List<JsonNode> ms = nonSystemMessages(b);
        assertThat(ms).hasSize(1);
        assertThat(ms.get(0).path("role").asText()).isEqualTo("user");
        assertThat(ms.get(0).path("content").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("String-content user message → simple-text branch unchanged")
    void stringContentUserMessage_simpleTextBranch() throws Exception {
        JsonNode b = body(reqWith(Message.user("hi")));

        List<JsonNode> ms = nonSystemMessages(b);
        assertThat(ms).hasSize(1);
        assertThat(ms.get(0).path("role").asText()).isEqualTo("user");
        assertThat(ms.get(0).path("content").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("Map-shaped mixed user message (DB-deserialised form) → text dropped")
    void mapShapedMixedMessage_textDroppedToolEmitted() throws Exception {
        // Jackson reads messagesJson back as List<LinkedHashMap> when Message.content
        // is Object — the OpenAi provider must handle both ContentBlock and Map forms.
        Map<String, Object> textMap = new LinkedHashMap<>();
        textMap.put("type", "text");
        textMap.put("text", "[Context summary]");

        Map<String, Object> trMap = new LinkedHashMap<>();
        trMap.put("type", "tool_result");
        trMap.put("tool_use_id", "X");
        trMap.put("content", "from-map");

        Message msg = userBlocks(textMap, trMap);
        JsonNode b = body(reqWith(msg));

        List<JsonNode> ms = nonSystemMessages(b);
        assertThat(ms).hasSize(1);
        assertThat(ms.get(0).path("role").asText()).isEqualTo("tool");
        assertThat(ms.get(0).path("tool_call_id").asText()).isEqualTo("X");
        assertThat(ms.get(0).path("content").asText()).isEqualTo("from-map");
        assertThat(b.toString()).doesNotContain("\"tool_call_id\":\"null\"");
    }

    @Test
    @DisplayName("user message with image block alongside tool_result → image dropped")
    void imageBlockMixedWithToolResult_imageDropped() throws Exception {
        // image and other unknown types must also be dropped — only "tool_result" passes.
        ContentBlock image = new ContentBlock();
        image.setType("image");
        Message msg = userBlocks(image, ContentBlock.toolResult("Y", "ok", false));

        JsonNode b = body(reqWith(msg));
        List<JsonNode> ms = nonSystemMessages(b);
        assertThat(ms).hasSize(1);
        assertThat(ms.get(0).path("role").asText()).isEqualTo("tool");
        assertThat(ms.get(0).path("tool_call_id").asText()).isEqualTo("Y");
    }
}
