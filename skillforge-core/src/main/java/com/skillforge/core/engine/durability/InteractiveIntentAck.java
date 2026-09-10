package com.skillforge.core.engine.durability;

import java.util.Objects;

/** Server-authoritative immutable result of the interactive intent transaction. */
public record InteractiveIntentAck(
        IntentCommitAck intent,
        PersistedMessageOccurrence control,
        InteractiveStepPlanner.SelectedControl selectedControl) {

    public InteractiveIntentAck {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(selectedControl, "selectedControl");
        if (control.seqNo() != intent.assistant().seqNo() + 1L) {
            throw new IllegalArgumentException("interactive control must follow its assistant");
        }
    }
}
