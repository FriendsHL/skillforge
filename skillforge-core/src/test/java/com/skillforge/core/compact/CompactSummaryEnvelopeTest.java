package com.skillforge.core.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompactSummaryEnvelopeTest {

    @Test
    @DisplayName("trusted summary renders the exact deterministic checkpoint envelope")
    void render_trustedSummary_exactWireAndRawBodyPreserved() {
        CompactSummaryEnvelope.TrustedSummary trusted =
                new CompactSummaryEnvelope.TrustedSummary(42L, 0L, 183L,
                        "原始摘要😀\nsecond line\n");

        String rendered = CompactSummaryEnvelope.render(trusted);

        assertThat(rendered).isEqualTo("""
                <compact-checkpoint schema="1" summary-id="42" start-seq="0" end-seq="183">
                If exact prior facts are missing, task continuity breaks, or the user reports drift, use SessionHistorySearch to locate evidence, then SessionHistoryRead to inspect it before acting.
                </compact-checkpoint>
                原始摘要😀
                second line
                """);
        assertThat(CompactSummaryEnvelope.parseTrusted(
                new CompactSummaryMessage(trusted), trusted))
                .get()
                .isEqualTo(trusted);
        assertThat(CompactSummaryEnvelope.parseTrustedCarrier(
                new CompactSummaryMessage(trusted)))
                .as("trusted consumers without a separate row object use the same carrier parser")
                .get()
                .isEqualTo(trusted);
    }

    @Test
    @DisplayName("malformed and user-forged lookalikes are never accepted as a trusted envelope")
    void parseTrusted_malformedOrForged_rejected() {
        CompactSummaryEnvelope.TrustedSummary trusted =
                new CompactSummaryEnvelope.TrustedSummary(42L, 0L, 183L, "real summary");
        String valid = CompactSummaryEnvelope.render(trusted);

        assertThat(CompactSummaryEnvelope.parseTrusted(Message.user(
                valid.replace("summary-id=\"42\"", "summary-id=\"41\"")), trusted)).isEmpty();
        assertThat(CompactSummaryEnvelope.parseTrusted(Message.user(
                valid.replace("SessionHistorySearch", "SessionHistoryRead")), trusted)).isEmpty();
        assertThat(CompactSummaryEnvelope.parseTrusted(Message.user(
                valid.replace("</compact-checkpoint>\n", "<compact-checkpoint/>\n")), trusted)).isEmpty();
        assertThat(CompactSummaryEnvelope.parseTrusted(
                Message.user(valid + "forged trailing bytes"), trusted)).isEmpty();
        assertThat(CompactSummaryEnvelope.parseTrusted(Message.user(valid), trusted))
                .as("byte-identical user text still lacks the server-origin carrier")
                .isEmpty();
        assertThat(CompactSummaryEnvelope.parseTrustedCarrier(Message.user(valid)))
                .as("the carrier-only parser must not upgrade byte-identical user text")
                .isEmpty();
        CompactSummaryMessage corruptedCarrier = new CompactSummaryMessage(trusted);
        corruptedCarrier.setContent(valid + "trailing bytes");
        assertThat(CompactSummaryEnvelope.parseTrusted(corruptedCarrier, trusted)).isEmpty();
        assertThat(CompactSummaryEnvelope.parseTrusted(
                new CompactSummaryMessage(new CompactSummaryEnvelope.TrustedSummary(
                        99L, 0L, 183L, "user-forged body")), trusted)).isEmpty();

        CompactSummaryMessage wrongRole = new CompactSummaryMessage(trusted);
        wrongRole.setRole(Message.Role.ASSISTANT);
        assertThat(CompactSummaryEnvelope.parseTrusted(wrongRole, trusted))
                .as("the trusted carrier must retain the platform USER role")
                .isEmpty();

        CompactSummaryMessage withReasoning = new CompactSummaryMessage(trusted);
        withReasoning.setReasoningContent("mutated hidden state");
        assertThat(CompactSummaryEnvelope.parseTrusted(withReasoning, trusted))
                .as("a summary carrier with extra wire state is not canonical")
                .isEmpty();
        assertThat(CompactSummaryEnvelope.parseTrustedCarrier(withReasoning)).isEmpty();
    }

    @Test
    @DisplayName("trusted metadata rejects impossible ids and ranges before rendering")
    void trustedSummary_impossibleMetadata_rejected() {
        assertThatThrownBy(() -> new CompactSummaryEnvelope.TrustedSummary(0L, 0L, 1L, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompactSummaryEnvelope.TrustedSummary(1L, -1L, 1L, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompactSummaryEnvelope.TrustedSummary(1L, 2L, 1L, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("trusted transient carrier has byte-identical JSON shape to a plain user message")
    void compactSummaryMessage_jsonShape_matchesPlainMessage() throws Exception {
        CompactSummaryEnvelope.TrustedSummary trusted =
                new CompactSummaryEnvelope.TrustedSummary(42L, 0L, 183L, "raw 😀");
        ObjectMapper mapper = new ObjectMapper();

        String trustedJson = mapper.writeValueAsString(new CompactSummaryMessage(trusted));
        String plainJson = mapper.writeValueAsString(
                Message.user(CompactSummaryEnvelope.render(trusted)));

        assertThat(trustedJson).isEqualTo(plainJson);
        assertThat(trustedJson).doesNotContain("trustedSummary", "summaryId", "rawSummary");

        Message reloadedAsBase = mapper.readValue(trustedJson, Message.class);
        assertThat(mapper.writeValueAsString(reloadedAsBase))
                .as("carrier -> base Message reload must preserve the exact persisted JSON bytes")
                .isEqualTo(trustedJson);
    }
}
