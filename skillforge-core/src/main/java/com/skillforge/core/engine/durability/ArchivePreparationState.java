package com.skillforge.core.engine.durability;

/** Terminal continuation gate after a durable Tool result batch has been committed. */
public enum ArchivePreparationState {
    PREPARED,
    RAW_FALLBACK
}
