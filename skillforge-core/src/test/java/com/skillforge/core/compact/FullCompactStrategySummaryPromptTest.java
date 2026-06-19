package com.skillforge.core.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the structured CC-aligned full-compaction summary prompt.
 *
 * <p>No LLM call is needed: these assertions pin the {@code SUMMARY_SYSTEM_PROMPT}
 * template shape (10 numbered sections, INV-1 verbatim Pending-Tool-Calls
 * instruction, identity-preservation line) and the raised {@link
 * FullCompactStrategy#MAX_SUMMARY_TOKENS} output cap so an accidental
 * regression (dropping section 10 / weakening the verbatim rule / reverting the
 * cap) is caught at build time.
 */
class FullCompactStrategySummaryPromptTest {

    private static String readSummarySystemPrompt() throws Exception {
        Field f = FullCompactStrategy.class.getDeclaredField("SUMMARY_SYSTEM_PROMPT");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    @DisplayName("prompt contains all 10 structured section headers in order")
    void prompt_containsAllTenSectionHeaders() throws Exception {
        String prompt = readSummarySystemPrompt();

        String[] headers = {
                "## 1. Primary Request and Intent",
                "## 2. Key Concepts and Facts",
                "## 3. Files and Resources",
                "## 4. Errors and Fixes",
                "## 5. Problem Solving",
                "## 6. All User Messages",
                "## 7. Pending Tasks",
                "## 8. Current Work",
                "## 9. Next Step",
                "## 10. Pending Tool Calls"
        };

        int prev = -1;
        for (String header : headers) {
            int idx = prompt.indexOf(header);
            assertThat(idx)
                    .as("header present: %s", header)
                    .isGreaterThanOrEqualTo(0);
            assertThat(idx)
                    .as("header appears after the previous one (ordering): %s", header)
                    .isGreaterThan(prev);
            prev = idx;
        }
    }

    @Test
    @DisplayName("INV-1: section 10 keeps the verbatim Pending-Tool-Calls instruction")
    void prompt_keepsPendingToolCallsVerbatimInstruction() throws Exception {
        String prompt = readSummarySystemPrompt();

        // The INV-1-critical instruction: pending tool_use args must survive byte-for-byte
        // so the agent can retry the call after compaction.
        assertThat(prompt).contains("VERBATIM");
        assertThat(prompt).contains("byte-for-byte");
        assertThat(prompt).contains("did NOT yet return a tool_result");
        assertThat(prompt).contains("retry the call after compaction");
    }

    @Test
    @DisplayName("prompt keeps the identity-preservation line for opaque identifiers")
    void prompt_keepsIdentityPreservationLine() throws Exception {
        String prompt = readSummarySystemPrompt();

        assertThat(prompt).contains("Identity preservation:");
        assertThat(prompt).contains("opaque identifiers exactly as written");
        assertThat(prompt).contains("UUIDs, hashes, IDs, tokens, API keys, hostnames, IPs, ports, URLs, file");
    }

    @Test
    @DisplayName("prompt keeps empty-section placeholder, soft target, and priority instruction")
    void prompt_keepsPlaceholderTargetAndPriorityInstructions() throws Exception {
        String prompt = readSummarySystemPrompt();

        // Empty-section placeholder rule: keep the header followed by the "—" sentinel.
        assertThat(prompt).contains("—");
        assertThat(prompt).contains("keep the header followed by \"—\"");

        // Soft target wording (the SOFT bound; HARD bound is the API maxTokens).
        assertThat(prompt).contains("Target under ~1800 tokens");

        // Priority instruction: when space is tight, keep recent context + sections 6-10 (the
        // INV-1 Pending-Tool-Calls section sits in that range, so this is the truncation guard).
        assertThat(prompt).contains("prioritize recent context and sections 6–10");
    }

    @Test
    @DisplayName("MAX_SUMMARY_TOKENS raised to 2000 (output cap for structured template)")
    void maxSummaryTokens_isTwoThousand() {
        assertThat(FullCompactStrategy.MAX_SUMMARY_TOKENS).isEqualTo(2000);
    }

    @Test
    @DisplayName("SUMMARY_INPUT_RESERVE_TOKENS raised to 5500 (input-budget headroom)")
    void summaryInputReserveTokens_isFiveThousandFiveHundred() throws Exception {
        Field f = FullCompactStrategy.class.getDeclaredField("SUMMARY_INPUT_RESERVE_TOKENS");
        f.setAccessible(true);
        assertThat(f.getInt(null)).isEqualTo(5_500);
    }
}
