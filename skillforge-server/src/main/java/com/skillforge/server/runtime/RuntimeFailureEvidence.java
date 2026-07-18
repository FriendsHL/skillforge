package com.skillforge.server.runtime;

/** Turn-boundary evidence used to decide whether replay is safe. */
public record RuntimeFailureEvidence(
        boolean providerStreamDeltaObserved,
        boolean toolCallObserved,
        boolean persistedTailIsUser) {
}
