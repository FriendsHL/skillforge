package com.skillforge.server.session;

import com.skillforge.core.engine.durability.PersistedBlockOccurrence;

import java.util.List;

/** Transaction participant that prepares canonical archives for verified result occurrences. */
public interface ToolResultOccurrenceArchiveWriter {

    void prepare(List<PersistedBlockOccurrence> resultBlocks);
}
