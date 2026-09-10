package com.skillforge.server.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

/** Closed model-facing locator response for {@code SessionHistorySearch}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"schemaVersion", "locators", "cursor", "exhaustive"})
public record SessionHistorySearchResponse(
        int schemaVersion,
        List<Locator> locators,
        String cursor,
        boolean exhaustive) {

    public SessionHistorySearchResponse {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        locators = List.copyOf(Objects.requireNonNull(locators, "locators"));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "ref", "evidenceClass", "kind", "role", "logicalSeq", "toolName", "toolUseId",
            "compacted", "summaryState", "preview", "authorizedContentHash"
    })
    public record Locator(
            String ref,
            String evidenceClass,
            String kind,
            String role,
            long logicalSeq,
            String toolName,
            String toolUseId,
            boolean compacted,
            String summaryState,
            String preview,
            String authorizedContentHash) {
    }
}
