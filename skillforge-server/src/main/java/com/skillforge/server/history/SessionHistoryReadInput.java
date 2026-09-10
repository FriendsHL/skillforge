package com.skillforge.server.history;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Closed, identity-free input accepted by {@code SessionHistoryRead}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "refs", "seqFrom", "seqTo", "aroundSeq", "before", "after", "tail",
        "archiveRef", "offset", "maxChars", "cursor"
})
public record SessionHistoryReadInput(
        @JsonIgnore SelectorArm arm,
        List<String> refs,
        Long seqFrom,
        Long seqTo,
        Long aroundSeq,
        Integer before,
        Integer after,
        Integer tail,
        String archiveRef,
        Integer offset,
        Integer maxChars,
        String cursor) {

    public SessionHistoryReadInput {
        refs = refs == null ? null : List.copyOf(refs);
    }

    public enum SelectorArm { REFS, RANGE, AROUND, TAIL, ARCHIVE }
}
