package com.skillforge.server.history;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryToolInputValidatorTest {

    private final HistoryToolInputValidator validator = new HistoryToolInputValidator();

    @Test
    void acceptsBoundedSearchSelectorsAndReturnsImmutableCanonicalInput() {
        Map<String, Object> validated = validator.validateSearch(Map.of(
                "query", "write_batch_id",
                "seqFrom", 4,
                "seqTo", 40L,
                "roles", List.of("USER", "ASSISTANT"),
                "kinds", List.of("TEXT", "TOOL_RESULT"),
                "compacted", "ANY",
                "summaryState", "ACTIVE",
                "limit", 8
        ));

        assertThat(validated).containsEntry("seqFrom", 4L).containsEntry("seqTo", 40L);
        assertThatThrownBy(() -> validated.put("query", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsSearchWithoutLocatorOrWithInvalidRangeAndEnum() {
        assertCode(() -> validator.validateSearch(Map.of("limit", 8)), "MISSING_SELECTOR");
        assertCode(() -> validator.validateSearch(Map.of("cursor", "opaque", "limit", 8)),
                "MISSING_SELECTOR");
        assertCode(() -> validator.validateSearch(Map.of("query", "  ")), "OUT_OF_BOUNDS");
        assertCode(() -> validator.validateSearch(Map.of("query", "  ", "roles", List.of("USER"))),
                "OUT_OF_BOUNDS");
        assertCode(() -> validator.validateSearch(Map.of("seqFrom", 9, "seqTo", 4)),
                "INVALID_RANGE");
        assertCode(() -> validator.validateSearch(Map.of("roles", List.of("SYSTEM"))),
                "INVALID_ENUM");
        assertCode(() -> validator.validateSearch(Map.of("limit", 51, "query", "x")),
                "OUT_OF_BOUNDS");
    }

    @Test
    void rejectsUnknownAndEveryIdentityFieldVariantBeforeDispatch() {
        for (String identity : List.of(
                "sessionId", "session_id", "SessionID", "session-id",
                "userId", "user_id", "USER-ID")) {
            assertCode(() -> validator.validateSearch(Map.of(identity, "foreign", "query", "x")),
                    "IDENTITY_FIELD_FORBIDDEN");
        }
        assertCode(() -> validator.validateSearch(Map.of("query", "x", "unexpected", true)),
                "UNKNOWN_FIELD");
        assertCode(() -> validator.validateSearch(Map.of("query", Map.of("value", "x"))),
                "INVALID_TYPE");
    }

    @Test
    void rejectsNestedIdentityFieldVariantsBeforeTypeValidation() {
        for (String identity : List.of(
                "sessionId", "SESSION_ID", "session-id", "session.id", "session id",
                "userId", "USER_ID", "user-id", "user.id", "user id")) {
            assertCode(() -> validator.validateSearch(Map.of(
                    "query", Map.of("nested", List.of(Map.of(identity, "foreign"))))),
                    "IDENTITY_FIELD_FORBIDDEN");
            assertCode(() -> validator.validateRead(Map.of(
                    "refs", List.of(Map.of("nested", Map.of(identity, "foreign"))))),
                    "IDENTITY_FIELD_FORBIDDEN");
        }
    }

    @Test
    void returnsClosedTypedInputsWithoutRuntimeIdentity() {
        SessionHistorySearchInput search = validator.validateSearchInput(Map.of(
                "query", "精确事实😀", "roles", List.of("USER"), "limit", 8));
        SessionHistoryReadInput read = validator.validateReadInput(Map.of(
                "aroundSeq", 20, "before", 2, "after", 3));

        assertThat(search.query()).isEqualTo("精确事实😀");
        assertThat(search.roles()).containsExactly(SessionHistorySearchInput.Role.USER);
        assertThat(search.limit()).isEqualTo(8);
        assertThat(read.arm()).isEqualTo(SessionHistoryReadInput.SelectorArm.AROUND);
        assertThat(read.aroundSeq()).isEqualTo(20L);
        assertThat(recursiveRecordComponentNames(SessionHistorySearchInput.class))
                .doesNotContain("sessionId", "userId");
        assertThat(recursiveRecordComponentNames(SessionHistoryReadInput.class))
                .doesNotContain("sessionId", "userId");
    }

    @Test
    void acceptsEachReadSelectorArm() {
        assertThat(validator.validateRead(Map.of("refs", List.of("msg:e0:id1:block0"))))
                .containsKey("refs");
        assertThat(validator.validateRead(Map.of("seqFrom", 0, "seqTo", 10)))
                .containsEntry("seqFrom", 0L).containsEntry("seqTo", 10L);
        assertThat(validator.validateRead(Map.of("aroundSeq", 8, "before", 2, "after", 3)))
                .containsEntry("aroundSeq", 8L);
        assertThat(validator.validateRead(Map.of("tail", 20))).containsEntry("tail", 20);
        assertThat(validator.validateRead(Map.of(
                "archiveRef", "archive:e0:idarchive-1", "offset", 0, "maxChars", 8_000)))
                .containsEntry("offset", 0);
    }

    @Test
    void readRefsPreserveOrderAndDeduplicateFirstOccurrence() {
        assertThat(validator.validateRead(Map.of(
                "refs", List.of("msg:e0:id2:block0", "msg:e0:id1:block0", "msg:e0:id2:block0"))))
                .containsEntry("refs", List.of("msg:e0:id2:block0", "msg:e0:id1:block0"));
    }

    @Test
    void rejectsPartialMixedAndOversizedReadSelectors() {
        assertCode(() -> validator.validateRead(Map.of("seqFrom", 1)), "INVALID_SELECTOR");
        assertCode(() -> validator.validateRead(Map.of("refs", List.of("r"), "tail", 1)),
                "INVALID_SELECTOR");
        assertCode(() -> validator.validateRead(Map.of(
                "aroundSeq", 8, "before", 25, "after", 25)), "OUT_OF_BOUNDS");
        assertCode(() -> validator.validateRead(Map.of("tail", 51)), "OUT_OF_BOUNDS");
        assertCode(() -> validator.validateRead(Map.of(
                "archiveRef", "a", "offset", 0, "maxChars", 20_001)), "OUT_OF_BOUNDS");
        assertCode(() -> validator.validateRead(Map.of("refs", List.of(Map.of("sessionId", "x")))),
                "IDENTITY_FIELD_FORBIDDEN");
        assertCode(() -> validator.validateRead(Map.of("cursor", "opaque")), "INVALID_SELECTOR");
    }

    private static void assertCode(Runnable invocation, String code) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(HistoryInputValidationException.class)
                .extracting(error -> ((HistoryInputValidationException) error).getCode())
                .isEqualTo(code);
    }

    private static java.util.Set<String> recursiveRecordComponentNames(Class<?> type) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (!type.isRecord()) return names;
        for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
            names.addAll(recursiveRecordComponentNames(component.getType()));
        }
        return names;
    }
}
