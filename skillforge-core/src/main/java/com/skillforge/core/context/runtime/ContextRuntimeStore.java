package com.skillforge.core.context.runtime;

import java.util.Optional;

/**
 * Persistence seam for on-demand context runtime state.
 *
 * <p>Implementations must treat failures as operational degradation rather
 * than a reason to corrupt or rewrite conversation messages.
 */
public interface ContextRuntimeStore {

    Optional<ContextRuntimeSnapshot> load(String sessionId);

    void save(String sessionId, ContextRuntimeSnapshot snapshot);
}
