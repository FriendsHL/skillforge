package com.skillforge.core.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single built-in behavior rule definition loaded from behavior-rules.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BehaviorRuleDefinition(
        String id,
        String category,
        String severity,
        String label,
        String labelZh,
        String promptText,
        String promptTextZh,
        @JsonProperty("deprecated") boolean deprecated,
        String replacedBy,
        List<String> presets
) {
    public BehaviorRuleDefinition {
        if (presets == null) presets = List.of();
    }
}
