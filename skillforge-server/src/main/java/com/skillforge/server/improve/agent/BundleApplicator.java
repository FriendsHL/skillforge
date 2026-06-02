package com.skillforge.server.improve.agent;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 (§2.1) — applies a {@link Bundle}
 * (pointer tuple) onto an agent, producing the concrete {@link AgentDefinition}
 * to run in one side of a whole-agent A/B.
 *
 * <p>Invariant: a surface NOT present in the bundle (null pointer) keeps the
 * agent's currently-active version (no change). Only the surfaces with a
 * non-null pointer are overridden.
 *
 * <p>Phase 1 only wires the <b>prompt</b> branch. The {@code behaviorRuleVersionId}
 * branch is NOT yet implemented and <b>throws</b> rather than silently skipping
 * (§7 W4) — a Phase-1.5 caller that supplies a behavior-rule pointer must get a
 * loud error, never a wrong A/B that quietly ignored the rule side.
 */
@Component
public class BundleApplicator {

    private static final Logger log = LoggerFactory.getLogger(BundleApplicator.class);

    private final AgentService agentService;
    private final PromptVersionRepository promptVersionRepository;
    private final AgentDefinitionCloner cloner;

    public BundleApplicator(AgentService agentService,
                            PromptVersionRepository promptVersionRepository,
                            AgentDefinitionCloner cloner) {
        this.agentService = agentService;
        this.promptVersionRepository = promptVersionRepository;
        this.cloner = cloner;
    }

    /**
     * Build the {@link AgentDefinition} for {@code base} with {@code bundle}
     * applied. A {@code null} bundle (or one with all-null pointers) returns the
     * agent's current definition unchanged.
     *
     * @throws IllegalArgumentException a prompt version pointer is unknown, or its
     *                                  {@code agentId} doesn't match {@code base}
     *                                  (§7 W7 — a bundle pointer must belong to the
     *                                  target agent; a dead pointer fails loud).
     * @throws UnsupportedOperationException the bundle carries a non-null
     *                                  {@code behaviorRuleVersionId} (not wired
     *                                  until Phase 2, §7 W4).
     */
    public AgentDefinition apply(AgentEntity base, Bundle bundle) {
        // Deep-clone so the A/B's two def instances never share nested mutable
        // state (BehaviorRulesConfig etc.). toAgentDefinition already builds a
        // fresh def, but the shared cloner keeps the isolation explicit (§7 W3).
        AgentDefinition def = cloner.clone(agentService.toAgentDefinition(base));
        if (bundle == null) {
            return def;
        }

        String baseAgentId = String.valueOf(base.getId());

        // behavior_rule branch — NOT wired until Phase 2 (§7 W4): fail loud, never
        // silently skip a supplied pointer.
        if (bundle.behaviorRuleVersionId() != null && !bundle.behaviorRuleVersionId().isBlank()) {
            throw new UnsupportedOperationException(
                    "behavior_rule bundle not wired until Phase 2 (behaviorRuleVersionId="
                            + bundle.behaviorRuleVersionId() + ", agentId=" + baseAgentId + ")");
        }

        // prompt branch: load the version content by id REGARDLESS of status
        // (§7 W7 — best顺延 may reference a non-active version), but validate the
        // version belongs to the target agent.
        if (bundle.promptVersionId() != null && !bundle.promptVersionId().isBlank()) {
            PromptVersionEntity version = promptVersionRepository.findById(bundle.promptVersionId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "prompt version not found for bundle pointer: " + bundle.promptVersionId()));
            if (!baseAgentId.equals(version.getAgentId())) {
                throw new IllegalArgumentException(
                        "prompt version " + bundle.promptVersionId() + " belongs to agent "
                                + version.getAgentId() + " but bundle targets agent " + baseAgentId
                                + " — a bundle pointer must belong to the target agent");
            }
            def.setSystemPrompt(version.getContent());
            log.debug("[BundleApplicator] applied prompt version {} to agent {}",
                    bundle.promptVersionId(), baseAgentId);
        }

        return def;
    }
}
