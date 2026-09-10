package com.skillforge.core.compact;

import com.skillforge.core.model.Message;

import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic model-view envelope for a persisted range summary.
 *
 * <p>The envelope is never persisted as the summary body. Callers must construct
 * {@link TrustedSummary} from the authoritative summary row and use
 * {@link #parseTrusted(Message, TrustedSummary)} when recognizing an envelope against a known row,
 * or {@link #parseTrustedCarrier(Message)} when the consumer only needs to recognize the transient
 * server-created carrier. Both paths reject arbitrary conversation text which merely resembles the
 * platform tag.
 */
public final class CompactSummaryEnvelope {

    public static final String RECOVERY_CUE =
            "If exact prior facts are missing, task continuity breaks, or the user reports drift, "
                    + "use SessionHistorySearch to locate evidence, then SessionHistoryRead to "
                    + "inspect it before acting.";

    private CompactSummaryEnvelope() {
    }

    /** Trusted metadata and raw body loaded from one persisted summary row. */
    public record TrustedSummary(long summaryId, long startSeq, long endSeq, String rawSummary) {
        public TrustedSummary {
            if (summaryId <= 0) {
                throw new IllegalArgumentException("summaryId must be positive");
            }
            if (startSeq < 0 || endSeq < startSeq) {
                throw new IllegalArgumentException("summary range is invalid");
            }
            Objects.requireNonNull(rawSummary, "rawSummary");
        }
    }

    /** Render the one canonical LF-delimited schema-1 representation. */
    public static String render(TrustedSummary trusted) {
        Objects.requireNonNull(trusted, "trusted");
        return "<compact-checkpoint schema=\"1\" summary-id=\"" + trusted.summaryId()
                + "\" start-seq=\"" + trusted.startSeq()
                + "\" end-seq=\"" + trusted.endSeq() + "\">\n"
                + RECOVERY_CUE + "\n"
                + "</compact-checkpoint>\n"
                + trusted.rawSummary();
    }

    /**
     * Parse only when the complete candidate equals the canonical representation derived from the
     * authoritative row. This deliberately has no untrusted-text-only overload.
     */
    public static Optional<TrustedSummary> parseTrusted(Message candidate, TrustedSummary expected) {
        if (expected == null) {
            return Optional.empty();
        }
        return parseTrustedCarrier(candidate)
                .filter(expected::equals)
                .map(ignored -> expected);
    }

    /**
     * Recognize only a canonical server-created carrier and return its embedded authoritative
     * metadata. This overload is safe for consumers such as the engine which do not retain a
     * separate summary-row object: a plain {@link Message}, even with byte-identical text, can never
     * satisfy the subtype/provenance check.
     */
    public static Optional<TrustedSummary> parseTrustedCarrier(Message candidate) {
        if (!(candidate instanceof CompactSummaryMessage compactSummary)
                || candidate.getRole() != Message.Role.USER
                || candidate.getReasoningContent() != null) {
            return Optional.empty();
        }
        TrustedSummary trusted = compactSummary.trustedSummary();
        return compactSummary.matchesCanonical(trusted)
                ? Optional.of(trusted)
                : Optional.empty();
    }
}
