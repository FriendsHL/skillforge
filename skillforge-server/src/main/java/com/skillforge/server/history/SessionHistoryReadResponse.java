package com.skillforge.server.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

/** Closed model-facing evidence response for {@code SessionHistoryRead}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"schemaVersion", "events", "cursor", "truncated"})
public record SessionHistoryReadResponse(
        int schemaVersion,
        List<Event> events,
        String cursor,
        boolean truncated) {

    public SessionHistoryReadResponse {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "ref", "evidenceClass", "kind", "role", "logicalSeq", "toolName", "toolUseId",
            "content", "codePointOffset", "complete", "authorizedContentHash"
    })
    public record Event(
            String ref,
            String evidenceClass,
            String kind,
            String role,
            long logicalSeq,
            String toolName,
            String toolUseId,
            String content,
            int codePointOffset,
            boolean complete,
            String authorizedContentHash) {
    }
}
