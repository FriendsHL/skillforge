package com.skillforge.server.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryCanonicalSelectorTest {

    private final HistoryToolInputValidator validator = new HistoryToolInputValidator();
    private final HistoryCanonicalSelector selector = new HistoryCanonicalSelector(new ObjectMapper());

    @Test
    void searchSelectorHashIgnoresCursorButBindsEveryFilterAndBudget() {
        SessionHistorySearchInput first = validator.validateSearchInput(Map.of(
                "query", "事实😀", "roles", List.of("USER"), "limit", 8));
        SessionHistorySearchInput continuation = validator.validateSearchInput(Map.of(
                "query", "事实😀", "roles", List.of("USER"), "limit", 8, "cursor", "opaque"));
        SessionHistorySearchInput changed = validator.validateSearchInput(Map.of(
                "query", "事实😀", "roles", List.of("USER"), "limit", 9));

        assertThat(selector.hash(first)).isEqualTo(selector.hash(continuation));
        assertThat(selector.hash(changed)).isNotEqualTo(selector.hash(first));
        assertThat(selector.canonicalJson(first)).doesNotContain("cursor", "sessionId", "userId");
    }

    @Test
    void readSelectorHashBindsArmAndProjectionBudget() {
        SessionHistoryReadInput first = validator.validateReadInput(Map.of(
                "archiveRef", "archive:e4:idabc", "offset", 2, "maxChars", 8000));
        SessionHistoryReadInput changedBudget = validator.validateReadInput(Map.of(
                "archiveRef", "archive:e4:idabc", "offset", 2, "maxChars", 8001));

        assertThat(selector.hash(changedBudget)).isNotEqualTo(selector.hash(first));
        assertThat(selector.canonicalJson(first)).contains("archiveRef", "maxChars")
                .doesNotContain("cursor", "sessionId", "userId");
    }
}
