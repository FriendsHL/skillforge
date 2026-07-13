package com.skillforge.core.engine;

import com.skillforge.core.model.Message;
import com.skillforge.core.skill.PublishedArtifact;

import java.util.List;

/** Ordered result of one tool execution; artifacts never travel in provider-visible text. */
record ToolExecutionOutcome(Message toolResult, List<PublishedArtifact> artifacts) {

    ToolExecutionOutcome {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    static ToolExecutionOutcome withoutArtifacts(Message toolResult) {
        return new ToolExecutionOutcome(toolResult, List.of());
    }
}
