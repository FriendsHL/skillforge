package com.skillforge.server.improve.agent;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig.CustomRule;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.improve.behavior.BehaviorRuleVersionToCustomRulesMapper;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 (§2.1) — applies a {@link Bundle}
 * (pointer tuple) onto an agent, producing the concrete {@link AgentDefinition}
 * to run in one side of a whole-agent A/B.
 *
 * <p>Invariant: a surface NOT present in the bundle (null pointer) keeps the
 * agent's currently-active version (no change). Only the surfaces with a
 * non-null pointer are overridden.
 *
 * <p>Phase 2 wires both the <b>prompt</b> and <b>behavior_rule</b> branches. A
 * surface with a null pointer keeps the agent's active version (no change).
 */
@Component
public class BundleApplicator {

    private static final Logger log = LoggerFactory.getLogger(BundleApplicator.class);

    private final AgentService agentService;
    private final PromptVersionRepository promptVersionRepository;
    private final BehaviorRuleVersionRepository behaviorRuleVersionRepository;
    private final BehaviorRuleVersionToCustomRulesMapper rulesMapper;
    private final AgentDefinitionCloner cloner;

    public BundleApplicator(AgentService agentService,
                            PromptVersionRepository promptVersionRepository,
                            BehaviorRuleVersionRepository behaviorRuleVersionRepository,
                            BehaviorRuleVersionToCustomRulesMapper rulesMapper,
                            AgentDefinitionCloner cloner) {
        this.agentService = agentService;
        this.promptVersionRepository = promptVersionRepository;
        this.behaviorRuleVersionRepository = behaviorRuleVersionRepository;
        this.rulesMapper = rulesMapper;
        this.cloner = cloner;
    }

    /**
     * Build the {@link AgentDefinition} for {@code base} with {@code bundle}
     * applied. A {@code null} bundle (or one with all-null pointers) returns the
     * agent's current definition unchanged.
     *
     * @throws IllegalArgumentException a prompt / behavior_rule version pointer is
     *                                  unknown, or its {@code agentId} doesn't match
     *                                  {@code base} (§7 W7 — a bundle pointer must
     *                                  belong to the target agent; a dead pointer
     *                                  fails loud).
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

        // behavior_rule branch (Phase 2, §8 #2): load the version content by id
        // REGARDLESS of status (best顺延 may reference a non-active version),
        // validate ownership (W7), map rulesJson → CustomRules, APPEND to the
        // agent's existing customRules (the rule version is an increment on top of
        // the agent's active rules, same as injectCandidateRule in the behavior_rule
        // with-vs-without path).
        if (bundle.behaviorRuleVersionId() != null && !bundle.behaviorRuleVersionId().isBlank()) {
            BehaviorRuleVersionEntity version = behaviorRuleVersionRepository
                    .findById(bundle.behaviorRuleVersionId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "behavior_rule version not found for bundle pointer: "
                                    + bundle.behaviorRuleVersionId()));
            if (!baseAgentId.equals(version.getAgentId())) {
                throw new IllegalArgumentException(
                        "behavior_rule version " + bundle.behaviorRuleVersionId() + " belongs to agent "
                                + version.getAgentId() + " but bundle targets agent " + baseAgentId
                                + " — a bundle pointer must belong to the target agent");
            }
            BehaviorRulesConfig rules = def.getBehaviorRules();
            if (rules == null) {
                rules = new BehaviorRulesConfig();
                def.setBehaviorRules(rules);
            }
            List<CustomRule> combined = new ArrayList<>();
            if (rules.getCustomRules() != null) {
                combined.addAll(rules.getCustomRules());
            }
            combined.addAll(rulesMapper.toCustomRules(version.getRulesJson()));
            rules.setCustomRules(combined);
            log.debug("[BundleApplicator] applied behavior_rule version {} to agent {} ({} custom rules total)",
                    bundle.behaviorRuleVersionId(), baseAgentId, combined.size());
        }

        return def;
    }
}
