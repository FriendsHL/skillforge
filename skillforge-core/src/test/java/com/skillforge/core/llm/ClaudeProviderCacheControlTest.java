package com.skillforge.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.cache.CacheBoundary;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROMPT-CACHE-MVP Phase 2 — INV-4 / INV-6 / INV-12 verification of
 * {@link ClaudeProvider#buildRequestBody}. We invoke the (private) method via reflection
 * so we can keep the production API tight and still assert breakpoint placement.
 *
 * <p>Three breakpoints expected:
 * <ol>
 *   <li>system stable section (text block before BOUNDARY marker)</li>
 *   <li>last tool in the sorted tools array</li>
 *   <li>last block of the last user message (when blocks-shaped)</li>
 * </ol>
 */
@DisplayName("ClaudeProvider — 3 cache_control breakpoints (Phase 2 / INV-4)")
class ClaudeProviderCacheControlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ClaudeProvider provider =
            new ClaudeProvider("dummy-key", "https://api.anthropic.com",
                    "claude-sonnet-4-20250514");

    private static String invokeBuild(ClaudeProvider provider, LlmRequest request,
                                       String model, boolean stream) throws Exception {
        Method m = ClaudeProvider.class.getDeclaredMethod(
                "buildRequestBody", LlmRequest.class, String.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, request, model, stream);
    }

    @Test
    @DisplayName("system: stable text gets cache_control, dynamic does not")
    void systemArrayHasCacheControlOnStableOnly() throws Exception {
        LlmRequest req = new LlmRequest();
        req.setSystemPrompt("Be helpful." + CacheBoundary.MARKER_WITH_NEWLINES + "today is Monday");
        req.setMessages(List.of(userMessage("hi")));
        req.setModel("claude-sonnet-4-20250514");

        JsonNode body = MAPPER.readTree(invokeBuild(provider, req, "claude-sonnet-4-20250514", false));

        JsonNode systemArr = body.path("system");
        assertThat(systemArr.isArray()).isTrue();
        assertThat(systemArr).hasSize(2);
        assertThat(systemArr.get(0).path("type").asText()).isEqualTo("text");
        assertThat(systemArr.get(0).path("text").asText()).contains("Be helpful");
        assertThat(systemArr.get(0).path("cache_control").path("type").asText())
                .isEqualTo("ephemeral");
        // Dynamic block has NO cache_control.
        assertThat(systemArr.get(1).path("text").asText()).contains("Monday");
        assertThat(systemArr.get(1).has("cache_control")).isFalse();
    }

    @Test
    @DisplayName("system: prompt without boundary marker still tags whole text as cache-eligible")
    void systemArrayWithoutMarker() throws Exception {
        LlmRequest req = new LlmRequest();
        req.setSystemPrompt("Plain prompt without marker");
        req.setMessages(List.of(userMessage("hi")));
        req.setModel("claude-sonnet-4-20250514");

        JsonNode body = MAPPER.readTree(invokeBuild(provider, req, "claude-sonnet-4-20250514", false));

        JsonNode systemArr = body.path("system");
        assertThat(systemArr.isArray()).isTrue();
        assertThat(systemArr).hasSize(1);
        assertThat(systemArr.get(0).path("cache_control").path("type").asText())
                .isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("tools: array sorted by name; last tool gets cache_control")
    void toolsCacheControlOnLastSorted() throws Exception {
        ToolSchema z = new ToolSchema("Zoo", "Zoo desc", Map.of());
        ToolSchema a = new ToolSchema("Apple", "Apple desc", Map.of());

        LlmRequest req = new LlmRequest();
        req.setSystemPrompt("hi");
        req.setMessages(List.of(userMessage("hello")));
        req.setTools(List.of(z, a));
        req.setModel("claude-sonnet-4-20250514");

        JsonNode body = MAPPER.readTree(invokeBuild(provider, req, "claude-sonnet-4-20250514", false));

        JsonNode tools = body.path("tools");
        assertThat(tools).hasSize(2);
        // Sorted: Apple, Zoo
        assertThat(tools.get(0).path("name").asText()).isEqualTo("Apple");
        assertThat(tools.get(1).path("name").asText()).isEqualTo("Zoo");
        // Cache_control only on the LAST tool (Zoo).
        assertThat(tools.get(0).has("cache_control")).isFalse();
        assertThat(tools.get(1).path("cache_control").path("type").asText())
                .isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("messages: last block of last user message gets cache_control (block-shaped content)")
    void lastUserMessageBlockGetsCacheControl() throws Exception {
        Message userMsg = new Message();
        userMsg.setRole(Message.Role.USER);
        userMsg.setContent(List.of(
                ContentBlock.text("first"),
                ContentBlock.text("second")));

        LlmRequest req = new LlmRequest();
        req.setSystemPrompt("hi");
        req.setMessages(List.of(userMsg));
        req.setModel("claude-sonnet-4-20250514");

        JsonNode body = MAPPER.readTree(invokeBuild(provider, req, "claude-sonnet-4-20250514", false));

        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(1);
        JsonNode content = messages.get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(2);
        // Only the LAST content block carries cache_control (INV-4 breakpoint #3).
        assertThat(content.get(0).has("cache_control")).isFalse();
        assertThat(content.get(1).path("cache_control").path("type").asText())
                .isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("INV-6: total cache_control markers <= 4 (MVP uses 3)")
    void totalBreakpointsAtMostFour() throws Exception {
        LlmRequest req = new LlmRequest();
        req.setSystemPrompt("Stable" + CacheBoundary.MARKER_WITH_NEWLINES + "Dynamic");
        Message userMsg = new Message();
        userMsg.setRole(Message.Role.USER);
        userMsg.setContent(List.of(ContentBlock.text("only block")));
        req.setMessages(List.of(userMsg));
        req.setTools(List.of(new ToolSchema("Read", "Read", Map.of())));
        req.setModel("claude-sonnet-4-20250514");

        String json = invokeBuild(provider, req, "claude-sonnet-4-20250514", false);
        long markerCount = json.split("\"cache_control\"", -1).length - 1L;
        assertThat(markerCount).isLessThanOrEqualTo(4L);
        assertThat(markerCount).isEqualTo(3L); // exactly the 3 breakpoints we emit
    }

    private static Message userMessage(String text) {
        Message m = new Message();
        m.setRole(Message.Role.USER);
        m.setContent(text);
        return m;
    }
}
