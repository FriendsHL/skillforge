package com.skillforge.core.compact;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.skillforge.core.model.Message;

import java.util.Objects;

/**
 * Transient model-view message carrying server-origin provenance for a compact summary.
 *
 * <p>The trusted carrier is ignored by JSON, so provider and persistence wire shape remains the
 * same as a plain {@link Message#user(String)}. Deserialization cannot manufacture this subtype;
 * reload reconstructs it only from an authoritative summary row.
 */
public final class CompactSummaryMessage extends Message {

    private final CompactSummaryEnvelope.TrustedSummary trustedSummary;

    public CompactSummaryMessage(CompactSummaryEnvelope.TrustedSummary trustedSummary) {
        this.trustedSummary = Objects.requireNonNull(trustedSummary, "trustedSummary");
        setRole(Role.USER);
        setContent(CompactSummaryEnvelope.render(trustedSummary));
    }

    @JsonIgnore
    public CompactSummaryEnvelope.TrustedSummary trustedSummary() {
        return trustedSummary;
    }

    boolean matchesCanonical(CompactSummaryEnvelope.TrustedSummary expected) {
        return trustedSummary.equals(expected)
                && getContent() instanceof String text
                && CompactSummaryEnvelope.render(expected).equals(text);
    }
}
