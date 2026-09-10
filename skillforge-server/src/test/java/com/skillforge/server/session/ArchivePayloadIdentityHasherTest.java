package com.skillforge.server.session;

import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchivePayloadIdentityHasherTest {

    private final ArchivePayloadIdentityHasher hasher = new ArchivePayloadIdentityHasher();

    @Test
    void exactScalarFraming_isDeterministicAndDistinguishesNullFalseAndBoundaries() {
        PersistedBlockOccurrence base = occurrence("ab", "c", false, null);

        assertThat(hasher.hash(base))
                .matches("[0-9a-f]{64}")
                .isEqualTo(hasher.hash(occurrence("ab", "c", false, null)))
                .isNotEqualTo(hasher.hash(occurrence("a", "bc", false, null)))
                .isNotEqualTo(hasher.hash(occurrence("ab", "c", true, null)))
                .isNotEqualTo(hasher.hash(occurrence("ab", "c", false, "null")))
                .isNotEqualTo(hasher.hash(occurrence("ab", "c", false, "EXECUTION")));
    }

    @Test
    void unicodeAndJsonLookingContent_areHashedAsExactUtf8Scalars() {
        String exact = "你好🙂 {\"b\":2, \"a\":1}";

        assertThat(hasher.hash(occurrence("工具", exact, true, "执行")))
                .isNotEqualTo(hasher.hash(occurrence(
                        "工具", "你好🙂 {\"a\":1,\"b\":2}", true, "执行")));
    }

    @Test
    void scalarOverload_matchesThePersistedOccurrenceCarrier() {
        PersistedBlockOccurrence occurrence = occurrence("tool-1", "exact", true, "FAILED");

        assertThat(hasher.hash("tool-1", "exact", true, "FAILED"))
                .isEqualTo(hasher.hash(occurrence));
    }

    @Test
    void malformedUnicode_isRejectedInsteadOfSilentlyReplacementEncoded() {
        String unpairedHighSurrogate = "broken-\uD83D";

        assertThatThrownBy(() -> hasher.hash(
                "tool-1", unpairedHighSurrogate, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool result payload is not valid Unicode");
    }

    private static PersistedBlockOccurrence occurrence(
            String toolUseId, String content, boolean error, String errorType) {
        return new PersistedBlockOccurrence(
                1L, 0L, "00000000-0000-0000-0000-000000000901",
                UUID.fromString("00000000-0000-4000-8000-000000000902"),
                0, 0, toolUseId, content, error, errorType, "trace");
    }
}
