package com.skillforge.server.service;

import com.skillforge.core.llm.LlmResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionTitleService#cleanTitle(String)} —
 * defends against reasoning-model thinking-chain leakage into session title.
 *
 * <p>Background: smart-rename calls a (possibly reasoning) LLM; even with
 * {@code thinkingMode=DISABLED} + {@code mimo} routed to XIAOMI_MIMO (with thinking.type:disabled dialect),
 * upstream may still emit {@code reasoning_content} delta which
 * {@code OpenAiProvider} forwards via {@code handler.onText} for FE streaming.
 * The new {@code doSmartRename} path uses {@code LlmResponse.getContent()} so
 * the cleaned content path is reasoning-free, but {@code cleanTitle} retains a
 * defense-in-depth fallback for naive reasoning text that lacks {@code <think>}
 * wrapping (some models output bare推理 then "标题：XX" tail).
 *
 * <p>cleanTitle is package-private only so this test can exercise it; the
 * production seam is {@code SessionTitleService.doSmartRename}.
 */
class SessionTitleServiceCleanTitleTest {

    private SessionTitleService service;

    @BeforeEach
    void setUp() {
        // cleanTitle is pure (no dependencies touched), so passing all-null
        // collaborators is safe and avoids Mockito ceremony.
        service = new SessionTitleService(null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        // ctor spins up a daemon renamerExecutor (Executors.newFixedThreadPool(2)).
        // Without shutdown(), every @Test leaks 2 idle threads (5 tests × 2 = 10 threads
        // surviving until JVM exit). public shutdown() (=@PreDestroy) is the right seam.
        service.shutdown();
    }

    @Test
    @DisplayName("cleanTitle strips <think>...</think> wrapped reasoning blocks")
    void cleanTitle_thinkTagWrapped_stripsTags() {
        String raw = "<think>分析中...</think>\n会话标题";
        assertThat(service.cleanTitle(raw)).isEqualTo("会话标题");
    }

    @Test
    @DisplayName("cleanTitle keeps last segment when raw starts with naive reasoning prefix")
    void cleanTitle_naiveReasoningPrefix_keepsLastSegment() {
        String raw = "首先，用户要求我生成一个标题。因此标题是：代码评测任务";
        assertThat(service.cleanTitle(raw)).isEqualTo("代码评测任务");
    }

    @Test
    @DisplayName("cleanTitle strips nested reasoning prefixes even when raw has no final title segment")
    void cleanTitle_reasoningWithoutFinalTitle_stripsPrefix() {
        // No '。' / '.' / ':' / '\n' / '：' break char — but ALL nested prefixes MUST be removed.
        // "首先让我..." = "首先" + "让我" (both in prefix list); while-loop must strip both.
        String raw = "首先让我分析这段对话内容看起来是关于一个前端组件的实现细节涉及很多边界条件";
        String cleaned = service.cleanTitle(raw);
        assertThat(cleaned).doesNotStartWith("首先");
        assertThat(cleaned).doesNotStartWith("让我");
        assertThat(cleaned.length()).isLessThanOrEqualTo(20);
        assertThat(cleaned).isNotBlank();
    }

    @Test
    @DisplayName("cleanTitle leaves a normal short title untouched")
    void cleanTitle_normalShortTitle_unchanged() {
        assertThat(service.cleanTitle("代码评测任务")).isEqualTo("代码评测任务");
    }

    @Test
    @DisplayName("cleanTitle strips wrapping CJK / quote symbols")
    void cleanTitle_wrappingQuotes_stripped() {
        assertThat(service.cleanTitle("《会话主题》")).isEqualTo("会话主题");
    }

    // --- pickRawForTitle: content-first + reasoning fallback for mimo-style reasoning models ---

    @Test
    @DisplayName("pickRawForTitle returns content when content is present")
    void pickRawForTitle_contentPresent_returnsContent() {
        LlmResponse resp = new LlmResponse();
        resp.setContent("代码评测任务");
        resp.setReasoningContent("首先，让我分析…");
        assertThat(service.pickRawForTitle(resp)).isEqualTo("代码评测任务");
    }

    @Test
    @DisplayName("pickRawForTitle falls back to reasoning_content when content is empty (mimo case)")
    void pickRawForTitle_contentEmpty_returnsReasoning() {
        LlmResponse resp = new LlmResponse();
        resp.setContent("");
        resp.setReasoningContent("首先，用户要求我生成一个标题。因此标题是：会话主题");
        assertThat(service.pickRawForTitle(resp))
                .isEqualTo("首先，用户要求我生成一个标题。因此标题是：会话主题");
    }

    @Test
    @DisplayName("pickRawForTitle falls back to reasoning_content when content is blank (whitespace only)")
    void pickRawForTitle_contentBlank_returnsReasoning() {
        LlmResponse resp = new LlmResponse();
        resp.setContent("   \n  ");
        resp.setReasoningContent("分析: 会话主题");
        assertThat(service.pickRawForTitle(resp)).isEqualTo("分析: 会话主题");
    }

    @Test
    @DisplayName("pickRawForTitle returns empty string when both content and reasoning are null")
    void pickRawForTitle_bothNull_returnsEmpty() {
        LlmResponse resp = new LlmResponse();
        assertThat(service.pickRawForTitle(resp)).isEmpty();
    }

    @Test
    @DisplayName("pickRawForTitle returns empty string when LlmResponse itself is null")
    void pickRawForTitle_respNull_returnsEmpty() {
        assertThat(service.pickRawForTitle(null)).isEmpty();
    }

    @Test
    @DisplayName("mimo end-to-end: reasoning_content with '首先' prefix → cleanTitle yields non-empty non-prefix title")
    void mimoStyle_reasoningOnly_cleanTitleSalvagesTitle() {
        LlmResponse resp = new LlmResponse();
        resp.setContent("");
        resp.setReasoningContent("首先，用户要求我生成一个标题。因此标题是：代码评测任务");
        String raw = service.pickRawForTitle(resp);
        String cleaned = service.cleanTitle(raw);
        assertThat(cleaned).isEqualTo("代码评测任务");
    }
}
