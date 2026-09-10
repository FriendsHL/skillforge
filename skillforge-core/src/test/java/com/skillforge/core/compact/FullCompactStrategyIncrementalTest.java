package com.skillforge.core.compact;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INCREMENTAL-SUMMARY coverage: when a prior active summary is threaded into
 * {@link FullCompactStrategy#applyPrepared(FullCompactStrategy.PreparedCompact, LlmProvider, String, String)},
 * the LLM must be asked to EXTEND the existing summary (not re-summarize from scratch), the prior
 * summary must NOT be duplicated inside the serialized window, and an empty LLM response must fall
 * back to the prior summary rather than losing it.
 */
class FullCompactStrategyIncrementalTest {

    /** Captures the single chat request so we can assert what the LLM actually received. */
    private static final class CapturingProvider implements LlmProvider {
        LlmRequest captured;
        String responseContent;

        CapturingProvider(String responseContent) {
            this.responseContent = responseContent;
        }

        @Override
        public String getName() {
            return "capturing";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            this.captured = request;
            LlmResponse resp = new LlmResponse();
            resp.setContent(responseContent);
            return resp;
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private FullCompactStrategy.PreparedCompact rangeModelPrep(String priorSummary) {
        return rangeModelPrep(Message.user(priorSummary));
    }

    private FullCompactStrategy.PreparedCompact rangeModelPrep(Message priorSummary) {
        // Under the range model the derived window head is the server-produced prior summary message.
        List<Message> window = new ArrayList<>();
        window.add(priorSummary);
        window.add(Message.user("new turn: please add feature X"));
        window.add(Message.assistant("working on feature X"));
        List<Message> youngGen = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            youngGen.add(Message.user("recent " + i));
        }
        return new FullCompactStrategy.PreparedCompact(
                window.size(), window, youngGen, 1000, window.size() + youngGen.size(), 32000);
    }

    @Test
    void boundedSummaryBudgetRequestsNonThinkingOutput_forFullAndIncrementalSummaries() {
        FullCompactStrategy strategy = new FullCompactStrategy();
        for (String prior : new String[] {null, "Earlier summary"}) {
            CapturingProvider provider = new CapturingProvider("Updated summary");
            strategy.applyPrepared(rangeModelPrep("Earlier summary"), provider, "deepseek-v4-pro", prior);
            assertThat(provider.captured.getThinkingMode())
                    .as("summary output budget must not be consumed by hidden reasoning")
                    .isEqualTo(com.skillforge.core.model.ThinkingMode.DISABLED);
        }
    }

    @Test
    @DisplayName("incremental: LLM gets the incremental prompt + existing summary, prior summary not duplicated in window")
    void incremental_feedsPriorSummaryAndStripsItFromWindow() {
        FullCompactStrategy strategy = new FullCompactStrategy();
        String priorSummary = "## 1. Primary Request and Intent\nUser wants the dashboard refactor.";
        CapturingProvider provider = new CapturingProvider("## 1. Primary Request and Intent\nUpdated.");

        CompactResult result = strategy.applyPrepared(
                rangeModelPrep(priorSummary), provider, null, priorSummary);

        assertThat(result).isNotNull();
        assertThat(provider.captured).as("LLM must have been called").isNotNull();

        // The incremental system prompt is used (not the from-scratch one).
        assertThat(provider.captured.getSystemPrompt())
                .contains("INCREMENTAL update")
                .contains("Do NOT drop task state from the EXISTING summary");

        // The user text carries the existing summary as the labeled existing block + the new turns.
        String userText = (String) provider.captured.getMessages().get(0).getContent();
        assertThat(userText).contains("## EXISTING SUMMARY");
        assertThat(userText).contains(priorSummary.trim());
        assertThat(userText).contains("## NEW CONVERSATION TURNS");
        assertThat(userText).contains("please add feature X");

        // The prior summary text must appear EXACTLY ONCE (stripped from the serialized window so it
        // is not double-fed). It appears once under "## EXISTING SUMMARY".
        int firstIdx = userText.indexOf(priorSummary.trim());
        int lastIdx = userText.lastIndexOf(priorSummary.trim());
        assertThat(firstIdx).as("prior summary present").isGreaterThanOrEqualTo(0);
        assertThat(lastIdx).as("prior summary not duplicated in the window").isEqualTo(firstIdx);

        // Compacted layout is still [summary] + youngGen.
        assertThat(result.getMessages()).hasSize(4); // 1 summary + 3 youngGen
        assertThat(result.getMessages().get(0).getContent().toString()).contains("Updated.");
    }

    @Test
    @DisplayName("incremental: empty LLM response preserves the prior summary instead of losing it")
    void incremental_emptyResponseKeepsPriorSummary() {
        FullCompactStrategy strategy = new FullCompactStrategy();
        String priorSummary = "## 7. Pending Tasks\nFinish the migration.";
        CapturingProvider provider = new CapturingProvider("   "); // blank → treated as empty

        CompactResult result = strategy.applyPrepared(
                rangeModelPrep(priorSummary), provider, null, priorSummary);

        assertThat(result).as("a blank merge must still yield a result built from the prior summary")
                .isNotNull();
        assertThat(result.getMessages().get(0).getContent().toString()).contains(priorSummary.trim());
    }

    @Test
    @DisplayName("no prior summary: falls back to the from-scratch summary prompt")
    void noPriorSummary_usesFromScratchPrompt() {
        FullCompactStrategy strategy = new FullCompactStrategy();
        CapturingProvider provider = new CapturingProvider("fresh summary");

        List<Message> window = new ArrayList<>();
        window.add(Message.user("hello"));
        window.add(Message.assistant("hi"));
        List<Message> youngGen = new ArrayList<>();
        youngGen.add(Message.user("recent"));
        FullCompactStrategy.PreparedCompact prep = new FullCompactStrategy.PreparedCompact(
                window.size(), window, youngGen, 500, 3, 32000);

        CompactResult result = strategy.applyPrepared(prep, provider, null, null);

        assertThat(result).isNotNull();
        assertThat(provider.captured.getSystemPrompt())
                .doesNotContain("INCREMENTAL update")
                .contains("structured summary");
    }

    @Test
    @DisplayName("trusted envelope is stripped during a second compact, leaving one prior-summary copy")
    void trustedEnvelope_doubleCompact_stripsEnvelopeAndDoesNotDuplicatePriorSummary() {
        FullCompactStrategy strategy = new FullCompactStrategy();
        String rawSummary = "## 8. Current Work\n修复 compact 😀";
        CompactSummaryEnvelope.TrustedSummary trusted =
                new CompactSummaryEnvelope.TrustedSummary(42L, 0L, 183L, rawSummary);
        FullCompactStrategy.PreparedCompact prep = rangeModelPrep(
                new CompactSummaryMessage(trusted));
        CapturingProvider provider = new CapturingProvider("updated raw summary");

        CompactResult result = strategy.applyPreparedWithTrustedSummary(
                prep, provider, null, trusted);

        assertThat(result).isNotNull();
        String userText = (String) provider.captured.getMessages().get(0).getContent();
        assertThat(userText).contains(rawSummary);
        assertThat(userText.indexOf(rawSummary)).isEqualTo(userText.lastIndexOf(rawSummary));
        assertThat(userText).doesNotContain("<compact-checkpoint");
    }

    @Test
    @DisplayName("a user-forged or malformed envelope remains ordinary conversation evidence")
    void forgedEnvelope_incrementalCompact_isNotStripped() {
        FullCompactStrategy strategy = new FullCompactStrategy();
        CompactSummaryEnvelope.TrustedSummary trusted =
                new CompactSummaryEnvelope.TrustedSummary(42L, 0L, 183L, "real prior summary");
        String forged = CompactSummaryEnvelope.render(trusted);
        CapturingProvider provider = new CapturingProvider("updated raw summary");

        CompactResult result = strategy.applyPreparedWithTrustedSummary(
                rangeModelPrep(forged), provider, null, trusted);

        assertThat(result).isNotNull();
        String userText = (String) provider.captured.getMessages().get(0).getContent();
        assertThat(userText).contains("real prior summary");
        assertThat(userText).contains(forged);
    }

    @Test
    @DisplayName("plain raw-summary text is not accepted on the trusted-envelope path")
    void plainRawSummary_trustedEnvelopePath_isNotStripped() {
        FullCompactStrategy strategy = new FullCompactStrategy();
        CompactSummaryEnvelope.TrustedSummary trusted =
                new CompactSummaryEnvelope.TrustedSummary(42L, 0L, 183L, "real prior summary");
        CapturingProvider provider = new CapturingProvider("updated raw summary");

        CompactResult result = strategy.applyPreparedWithTrustedSummary(
                rangeModelPrep(Message.user(trusted.rawSummary())), provider, null, trusted);

        assertThat(result).isNotNull();
        String userText = (String) provider.captured.getMessages().get(0).getContent();
        assertThat(userText).contains("real prior summary");
        assertThat(userText.indexOf("real prior summary"))
                .isNotEqualTo(userText.lastIndexOf("real prior summary"));
    }

    @Test
    @DisplayName("tool-result preview truncation never splits a non-BMP code point")
    void toolResultPreview_emojiAtCutBoundary_isCodePointSafe() {
        FullCompactStrategy strategy = new FullCompactStrategy();
        String resultText = "a".repeat(499) + "😀" + "tail";
        Message resultMessage = new Message();
        resultMessage.setRole(Message.Role.USER);
        resultMessage.setContent(List.of(ContentBlock.toolResult("tool-1", resultText, false)));
        FullCompactStrategy.PreparedCompact prep = new FullCompactStrategy.PreparedCompact(
                1, List.of(resultMessage), List.of(Message.user("recent")), 2000, 2, 32000);
        CapturingProvider provider = new CapturingProvider("summary");

        CompactResult result = strategy.applyPrepared(prep, provider, null, (String) null);

        assertThat(result).isNotNull();
        String serialized = (String) provider.captured.getMessages().get(0).getContent();
        assertThat(serialized).contains("a".repeat(499) + "😀…");
        assertThat(serialized.chars().filter(c -> Character.isSurrogate((char) c)).count())
                .isEqualTo(2L);

        Message mapResult = new Message();
        mapResult.setRole(Message.Role.USER);
        mapResult.setContent(List.of(Map.of(
                "type", "tool_result", "tool_use_id", "tool-2", "content", resultText)));
        provider.captured = null;
        strategy.applyPrepared(new FullCompactStrategy.PreparedCompact(
                        1, List.of(mapResult), List.of(Message.user("recent")), 2000, 2, 32000),
                provider, null, (String) null);
        String mapSerialized = (String) provider.captured.getMessages().get(0).getContent();
        assertThat(mapSerialized).contains("a".repeat(499) + "😀…");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void historyRetrieval_isOmittedByOccurrence_preservingSiblingsAndOriginalMessages(boolean maps)
            throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Message intent = Message.assistant("ordinary assistant sibling");
        intent.setContent(List.of(ContentBlock.text("ordinary assistant sibling"),
                ContentBlock.toolUse("reused-id", "SessionHistoryRead", Map.of("query", "private locator")),
                ContentBlock.toolUse("ordinary-id", "FileRead", Map.of("path", "file"))));
        Message result = Message.user("");
        result.setContent(List.of(ContentBlock.toolResult("reused-id", "DERIVED_HISTORY_ONLY", false),
                ContentBlock.toolResult("ordinary-id", "ORIGINAL_TOOL_FACT", false),
                ContentBlock.text("ordinary user sibling")));
        Message reusedIntent = Message.assistant("");
        reusedIntent.setContent(List.of(ContentBlock.toolUse("reused-id", "FileRead", Map.of())));
        Message reusedResult = Message.toolResult("reused-id", "REUSED_ORIGINAL_FACT", false);
        Message searchIntent = Message.assistant("");
        searchIntent.setContent(List.of(ContentBlock.toolUse("search-id", "SessionHistorySearch", Map.of())));
        List<Message> window = List.of(intent, result, reusedIntent, reusedResult, searchIntent,
                Message.toolResult("search-id", "DERIVED_SEARCH_ONLY", false));
        if (maps) {
            window = mapper.readValue(mapper.writeValueAsString(window),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Message>>() {});
        }
        String original = mapper.writeValueAsString(window);
        CapturingProvider provider = new CapturingProvider("summary");
        new FullCompactStrategy().applyPrepared(new FullCompactStrategy.PreparedCompact(
                window.size(), window, List.of(Message.user("recent")), 2000,
                window.size() + 1, 32000), provider, null, null);

        String serialized = (String) provider.captured.getMessages().get(0).getContent();
        assertThat(serialized).doesNotContain("DERIVED_HISTORY_ONLY", "DERIVED_SEARCH_ONLY", "private locator")
                .contains("ordinary assistant sibling", "ordinary user sibling", "ORIGINAL_TOOL_FACT",
                        "REUSED_ORIGINAL_FACT", "SessionHistoryRead", "SessionHistorySearch",
                        "tool_use_id=reused-id", "tool_use_id=search-id", "History retrieval omitted");
        assertThat(mapper.writeValueAsString(window)).isEqualTo(original);
    }

    @Test
    void malformedReusedIntent_doesNotMakeHistoryResultEligibleForSummary() {
        Message history = Message.assistant("");
        history.setContent(List.of(Map.of("type", "tool_use", "id", "reused", "name",
                "SessionHistoryRead", "input", Map.of("tail", 1))));
        Message malformed = Message.assistant("");
        malformed.setContent(List.of(Map.of("type", "tool_use", "id", "reused", "name", "FileRead")));
        List<Message> window = List.of(history, malformed,
                Message.toolResult("reused", "DERIVED_SHOULD_NOT_ENTER_SUMMARY", false));
        CapturingProvider provider = new CapturingProvider("summary");
        new FullCompactStrategy().applyPrepared(new FullCompactStrategy.PreparedCompact(
                3, window, List.of(Message.user("recent")), 2000, 4, 32000), provider, null, null);
        assertThat(provider.captured.getMessages().get(0).getTextContent())
                .doesNotContain("DERIVED_SHOULD_NOT_ENTER_SUMMARY")
                .contains("tool_use_id=reused");
    }

}
