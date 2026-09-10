package com.skillforge.server.history.query;

import java.util.List;

/** Does not treat legacy or merely-present archive rows as canonical evidence. */
public final class FailClosedHistoryCanonicalArchiveResolver
        implements HistoryCanonicalArchiveResolver {

    @Override
    public Resolution resolve(
            List<RawToolResultOccurrence> rawOccurrences,
            List<HistoryQueryStore.ArchiveRow> archiveRows) {
        return Resolution.rawOnly();
    }
}
