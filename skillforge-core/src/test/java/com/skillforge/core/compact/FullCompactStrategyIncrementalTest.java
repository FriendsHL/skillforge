package com.skillforge.core.compact;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.Message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
        // Under the range model the derived window head IS the prior summary as a String user message.
        List<Message> window = new ArrayList<>();
        window.add(Message.user(priorSummary));
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
}
