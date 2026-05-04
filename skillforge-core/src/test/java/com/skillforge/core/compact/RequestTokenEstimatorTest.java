package com.skillforge.core.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link RequestTokenEstimator} (CTX-1, AC-1 / AC-2).
 *
 * <p>Goals:
 * <ul>
 *   <li>Each of the four buckets (system / messages / tools / maxTokens) actually adds up</li>
 *   <li>Null/empty inputs are tolerated without throwing</li>
 *   <li>Tool-schema serialisation falls back to name+description when mapper is null</li>
 *   <li>Result is deterministic across calls (relies on jtokkit which is stateless)</li>
 * </ul>
 */
class RequestTokenEstimatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("estimate returns 0 when all inputs null/empty")
    void estimate_allNullOrEmpty_returnsZero() {
        int n = RequestTokenEstimator.estimate(null, null, null, 0, mapper);
        assertThat(n).isZero();

        n = RequestTokenEstimator.estimate("", List.of(), List.of(), 0, mapper);
        assertThat(n).isZero();
    }

    @Test
    @DisplayName("maxTokens output reservation is included even with no system / messages / tools")
    void estimate_maxTokensOnly_addsReservation() {
        int n = RequestTokenEstimator.estimate(null, null, null, 4096, mapper);
        assertThat(n).isEqualTo(4096);
    }

    @Test
    @DisplayName("negative maxTokens is clamped to 0")
    void estimate_negativeMaxTokens_clampedToZero() {
        int n = RequestTokenEstimator.estimate(null, null, null, -100, mapper);
        assertThat(n).isZero();
    }

    @Test
    @DisplayName("system prompt tokens are counted via TokenEstimator.estimateString")
    void estimate_systemPromptOnly_matchesTokenEstimator() {
        String prompt = "You are a helpful assistant. Answer concisely.";
        int expectedSys = TokenEstimator.estimateString(prompt);

        int n = RequestTokenEstimator.estimate(prompt, null, null, 0, mapper);
        assertThat(n).isEqualTo(expectedSys);
    }

    @Test
    @DisplayName("messages contribute via TokenEstimator.estimate")
    void estimate_messagesOnly_matchesTokenEstimator() {
        List<Message> messages = List.of(
                Message.user("hello world"),
                Message.assistant("hi back")
        );
        int expectedMsg = TokenEstimator.estimate(messages);

        int n = RequestTokenEstimator.estimate(null, messages, null, 0, mapper);
        assertThat(n).isEqualTo(expectedMsg);
    }

    @Test
    @DisplayName("tools contribute by JSON-serialised size when mapper provided")
    void estimate_toolSchemas_jsonBased() {
        List<ToolSchema> tools = sampleTools();
        int viaShared = RequestTokenEstimator.estimateToolSchemas(tools, mapper);
        assertThat(viaShared).isPositive();

        int n = RequestTokenEstimator.estimate(null, null, tools, 0, mapper);
        assertThat(n).isEqualTo(viaShared);
    }

    @Test
    @DisplayName("tool schemas fall back to name+description when mapper is null")
    void estimateToolSchemas_nullMapper_fallback() {
        List<ToolSchema> tools = sampleTools();
        int n = RequestTokenEstimator.estimateToolSchemas(tools, null);

        // Fallback at minimum captures name + description characters.
        int fallbackLowerBound = 0;
        for (ToolSchema t : tools) {
            fallbackLowerBound += TokenEstimator.estimateString(safe(t.getName()))
                                + TokenEstimator.estimateString(safe(t.getDescription()));
        }
        assertThat(n).isEqualTo(fallbackLowerBound);
    }

    @Test
    @DisplayName("all four buckets sum without double-counting")
    void estimate_allFourBuckets_sumIsAdditive() {
        String sys = "system";
        List<Message> msgs = List.of(Message.user("hello"));
        List<ToolSchema> tools = sampleTools();
        int maxTokens = 1024;

        int sysOnly = RequestTokenEstimator.estimate(sys, null, null, 0, mapper);
        int msgsOnly = RequestTokenEstimator.estimate(null, msgs, null, 0, mapper);
        int toolsOnly = RequestTokenEstimator.estimate(null, null, tools, 0, mapper);
        int maxOnly = RequestTokenEstimator.estimate(null, null, null, maxTokens, mapper);
        int total = RequestTokenEstimator.estimate(sys, msgs, tools, maxTokens, mapper);

        assertThat(total).isEqualTo(sysOnly + msgsOnly + toolsOnly + maxOnly);
    }

    @Test
    @DisplayName("estimate is deterministic across repeated calls")
    void estimate_repeatable() {
        String sys = "System prompt content";
        List<Message> msgs = List.of(Message.user("test"));
        List<ToolSchema> tools = sampleTools();

        int a = RequestTokenEstimator.estimate(sys, msgs, tools, 2048, mapper);
        int b = RequestTokenEstimator.estimate(sys, msgs, tools, 2048, mapper);
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("null entries inside tool list are skipped silently")
    void estimateToolSchemas_skipsNullEntries() {
        List<ToolSchema> tools = new java.util.ArrayList<>();
        tools.add(null);
        tools.add(new ToolSchema("foo", "bar", Map.of("type", "object")));
        tools.add(null);

        int n = RequestTokenEstimator.estimateToolSchemas(tools, mapper);
        assertThat(n).isPositive();
    }

    private static List<ToolSchema> sampleTools() {
        Map<String, Object> schema1 = new HashMap<>();
        schema1.put("type", "object");
        schema1.put("properties", Map.of("path", Map.of("type", "string")));
        Map<String, Object> schema2 = new HashMap<>();
        schema2.put("type", "object");
        return List.of(
                new ToolSchema("Read", "Read a file from disk", schema1),
                new ToolSchema("Glob", "Find files matching a pattern", schema2)
        );
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
