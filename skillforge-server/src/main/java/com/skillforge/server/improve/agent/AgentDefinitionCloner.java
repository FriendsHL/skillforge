package com.skillforge.server.improve.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.AgentDefinition;
import org.springframework.stereotype.Component;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 (§7 W3) — surface-agnostic deep clone of
 * an {@link AgentDefinition} via JSON roundtrip. Extracted as a shared helper so
 * {@link BundleApplicator} (and future bundle-surface appliers) don't each
 * re-implement the clone.
 *
 * <p>A shallow copy would share the nested mutable
 * {@code AgentDefinition.BehaviorRulesConfig} between the baseline and candidate
 * defs of an A/B run → mutual pollution. The JSON roundtrip is the same approach
 * {@code BehaviorRuleAbEvalService.cloneDef} uses; that path is intentionally
 * left in place (§7 W3 — don't refactor the behavior surface in Phase 1).
 */
@Component
public class AgentDefinitionCloner {

    private final ObjectMapper objectMapper;

    public AgentDefinitionCloner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Deep-clone {@code src} via JSON write→read. */
    public AgentDefinition clone(AgentDefinition src) {
        try {
            String json = objectMapper.writeValueAsString(src);
            return objectMapper.readValue(json, AgentDefinition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("AgentDefinition deep-clone failed", ex);
        }
    }
}
