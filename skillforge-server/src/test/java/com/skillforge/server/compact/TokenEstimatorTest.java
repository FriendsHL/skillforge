package com.skillforge.server.compact;

import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenEstimator 已切换到 jtokkit cl100k_base 底盘，测试锁定
 * jtokkit 1.1.0 实测的 token 数（baseline 来自 {@code countTokensOrdinary}）。
 *
 * <p>baseline 跑测可重现：
 * <pre>
 *   Encoding e = Encodings.newDefaultEncodingRegistry()
 *                  .getEncoding(EncodingType.CL100K_BASE);
 *   e.countTokensOrdinary("Hello, world!");  // 4
 *   e.countTokensOrdinary("你好世界");          // 5
 * </pre>
 */
class TokenEstimatorTest {

    /** 每条消息额外 overhead（与 TokenEstimator.PER_MESSAGE_OVERHEAD 保持一致）。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    @Test
    @DisplayName("estimate(null) 返回 0")
    void estimate_null_returnsZero() {
        assertThat(TokenEstimator.estimate(null)).isEqualTo(0);
    }

    @Test
    @DisplayName("estimate(empty list) 返回 0")
    void estimate_emptyList_returnsZero() {
        assertThat(TokenEstimator.estimate(List.of())).isEqualTo(0);
    }

    @Test
    @DisplayName("estimateString(null) 返回 0（防 NPE）")
    void estimateString_null_returnsZero() {
        assertThat(TokenEstimator.estimateString(null)).isEqualTo(0);
    }

    @Test
    @DisplayName("estimateString(\"\") 返回 0")
    void estimateString_empty_returnsZero() {
        assertThat(TokenEstimator.estimateString("")).isEqualTo(0);
    }

    @Test
    @DisplayName("纯英文 \"Hello, world!\" jtokkit cl100k baseline = 4 tokens")
    void estimateString_englishHelloWorld_returnsFour() {
        // jtokkit 1.1.0 cl100k_base ordinary
        assertThat(TokenEstimator.estimateString("Hello, world!")).isEqualTo(4);
    }

    @Test
    @DisplayName("纯中文 \"你好世界\" jtokkit cl100k baseline = 5 tokens")
    void estimateString_chineseGreeting_returnsFive() {
        // jtokkit 1.1.0 cl100k_base ordinary
        assertThat(TokenEstimator.estimateString("你好世界")).isEqualTo(5);
    }

    @Test
    @DisplayName("中英混排 \"Hello 你好 world 世界\" baseline = 9 tokens")
    void estimateString_mixedCjkAscii_returnsBaseline() {
        // jtokkit 1.1.0 cl100k_base ordinary
        assertThat(TokenEstimator.estimateString("Hello 你好 world 世界")).isEqualTo(9);
    }

    @Test
    @DisplayName("estimate 单条 user 消息 = content tokens + 4 overhead")
    void estimate_singleUserMessage_addsOverhead() {
        int est = TokenEstimator.estimate(List.of(Message.user("Hello, world!")));
        assertThat(est).isEqualTo(4 + PER_MESSAGE_OVERHEAD);
    }

    @Test
    @DisplayName("estimate 多条消息 = 各条 content + N×4 overhead")
    void estimate_multipleMessages_addsPerMessageOverhead() {
        List<Message> msgs = List.of(
                Message.user("hello world"),       // 2 tokens
                Message.assistant("hello world"),  // 2 tokens
                Message.user("hello world")        // 2 tokens
        );
        int est = TokenEstimator.estimate(msgs);
        assertThat(est).isEqualTo((2 + PER_MESSAGE_OVERHEAD) * 3);
    }

    @Test
    @DisplayName("ContentBlock text 块走 estimateString 路径")
    void estimate_contentBlockText_matchesEstimateString() {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(ContentBlock.text("Hello, world!")));
        // 4 (content) + 4 overhead
        assertThat(TokenEstimator.estimate(List.of(m))).isEqualTo(4 + PER_MESSAGE_OVERHEAD);
    }

    @Test
    @DisplayName("ContentBlock tool_use 块统计 name + input.toString()")
    void estimate_contentBlockToolUse_countsNameAndInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "README.md");
        ContentBlock toolUse = ContentBlock.toolUse("call_1", "Read", input);
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(toolUse));

        int expected = TokenEstimator.estimateString("Read")
                + TokenEstimator.estimateString(input.toString());
        assertThat(TokenEstimator.estimate(List.of(m)))
                .isEqualTo(expected + PER_MESSAGE_OVERHEAD);
    }

    @Test
    @DisplayName("ContentBlock tool_result 块统计 content 文本")
    void estimate_contentBlockToolResult_countsContent() {
        ContentBlock result = ContentBlock.toolResult("call_1", "Hello, world!", false);
        Message m = new Message();
        m.setRole(Message.Role.USER);
        m.setContent(List.of(result));
        // content "Hello, world!" = 4
        assertThat(TokenEstimator.estimate(List.of(m))).isEqualTo(4 + PER_MESSAGE_OVERHEAD);
    }

    @Test
    @DisplayName("List<Map<String,Object>> 形态 content 同时统计 text/content/input 三种 key")
    void estimate_mapBlocks_countsAllRelevantKeys() {
        Map<String, Object> textBlock = new LinkedHashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", "Hello, world!");          // 4

        Map<String, Object> resultBlock = new LinkedHashMap<>();
        resultBlock.put("type", "tool_result");
        resultBlock.put("content", "你好世界");            // 5

        Map<String, Object> inputBlock = new LinkedHashMap<>();
        inputBlock.put("type", "tool_use");
        inputBlock.put("input", "hello world");          // 2

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(textBlock);
        blocks.add(resultBlock);
        blocks.add(inputBlock);

        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(blocks);
        assertThat(TokenEstimator.estimate(List.of(m)))
                .isEqualTo(4 + 5 + 2 + PER_MESSAGE_OVERHEAD);
    }

    @Test
    @DisplayName("PER_MESSAGE_OVERHEAD 对 ContentBlock list 形态消息也只加一次")
    void estimate_contentBlockList_overheadAddedExactlyOnce() {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(
                ContentBlock.text("hello world"),    // 2
                ContentBlock.text("hello world"),    // 2
                ContentBlock.text("hello world")     // 2
        ));
        // 三条 ContentBlock 是同一条 Message 的多个块 → overhead 只加一次
        assertThat(TokenEstimator.estimate(List.of(m))).isEqualTo(2 + 2 + 2 + PER_MESSAGE_OVERHEAD);
    }

    @Test
    @DisplayName("identity-cache：同一 Message 引用第二次返回缓存值（即使 content 被 mutate）")
    void estimate_sameMessageReferenceAfterMutation_returnsCachedTokens() {
        // 这是 identity-based 缓存的"用法语义"测试：生产代码里 Message 视为不可变，
        // 这里通过故意 mutate content 来证明 cache 命中（若未命中，第二次会基于新 content 重算）。
        Message m = Message.user("Hello, world!");
        int first = TokenEstimator.estimate(List.of(m));
        assertThat(first).isEqualTo(4 + PER_MESSAGE_OVERHEAD);

        m.setContent("a much longer string that would have many more tokens than the original");
        int second = TokenEstimator.estimate(List.of(m));
        assertThat(second)
                .as("identity-cached result should be returned, not recomputed against mutated content")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("不同 Message 实例（content 相同）不命中缓存，按内容重算")
    void estimate_differentMessageInstances_independentCache() {
        Message a = Message.user("Hello, world!");
        Message b = Message.user("Hello, world!");
        int aEst = TokenEstimator.estimate(List.of(a));
        // mutate b after creation — different reference, must recompute against current content
        b.setContent("hello world"); // 2 tokens
        int bEst = TokenEstimator.estimate(List.of(b));
        assertThat(aEst).isEqualTo(4 + PER_MESSAGE_OVERHEAD);
        assertThat(bEst).isEqualTo(2 + PER_MESSAGE_OVERHEAD);
    }

    @Test
    @DisplayName("estimate 跳过列表中的 null 元素，不抛 NPE")
    void estimate_listWithNullElement_skipsGracefully() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user("hello world")); // 2
        msgs.add(null);
        msgs.add(Message.user("hello world")); // 2
        // 只有两条非 null → 2 × (2 + 4)
        assertThat(TokenEstimator.estimate(msgs)).isEqualTo((2 + PER_MESSAGE_OVERHEAD) * 2);
    }
}
