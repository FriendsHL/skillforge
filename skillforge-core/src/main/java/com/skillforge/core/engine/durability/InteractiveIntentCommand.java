package com.skillforge.core.engine.durability;

import java.util.Objects;

/** Immutable command for atomically persisting a Tool intent and its waiting-user control. */
public record InteractiveIntentCommand(
        IntentCommitCommand intent,
        InteractiveStepPlanner.InteractiveStepPlan plan,
        String controlId,
        String displayText,
        FrozenJson payload) {

    public InteractiveIntentCommand {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(plan, "plan");
        if (controlId == null || controlId.isBlank() || controlId.length() > 64) {
            throw new IllegalArgumentException("controlId must contain 1-64 characters");
        }
        Objects.requireNonNull(displayText, "displayText");
        Objects.requireNonNull(payload, "payload");
        if (!intent.manifest().calls().equals(plan.calls())) {
            throw new IllegalArgumentException("interactive plan must match intent manifest");
        }
    }
}
