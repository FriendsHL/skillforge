package com.skillforge.server.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Closed, identity-free input accepted by {@code SessionHistorySearch}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "query", "seqFrom", "seqTo", "roles", "kinds", "toolName", "toolUseId",
        "compacted", "summaryState", "limit", "cursor"
})
public record SessionHistorySearchInput(
        String query,
        Long seqFrom,
        Long seqTo,
        List<Role> roles,
        List<Kind> kinds,
        String toolName,
        String toolUseId,
        Compacted compacted,
        SummaryState summaryState,
        Integer limit,
        String cursor) {

    public SessionHistorySearchInput {
        roles = roles == null ? null : List.copyOf(roles);
        kinds = kinds == null ? null : List.copyOf(kinds);
    }

    public enum Role { USER, ASSISTANT }

    public enum Kind { TEXT, TOOL_USE, TOOL_RESULT, SUMMARY }

    public enum Compacted { ANY, COMPACTED, UNCOMPACTED }

    public enum SummaryState { ANY, ACTIVE, SUPERSEDED }
}
