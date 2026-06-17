package com.skillforge.server.improve.agent;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig.CustomRule;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.behavior.BehaviorRuleVersionToCustomRulesMapper;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.skill.SkillDefinitionFromDraft;
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
 * <p>Phase 2 wires the <b>prompt</b> and <b>behavior_rule</b> branches. Phase 4
 * (§10 #4) wires the <b>skill</b> branch — which CHANGES the return contract:
 * {@link #apply} now returns an {@link ApplyResult} carrying both the
 * {@link AgentDefinition} and any in-memory candidate {@link SkillDefinition}s
 * the eval sandbox must register (the skill surface is evaluated purely
 * in-memory, so the candidate def has to be carried OUT to the caller).
 */
@Component
public class BundleApplicator {

    private static final Logger log = LoggerFactory.getLogger(BundleApplicator.class);

    private final AgentService agentService;
    private final PromptVersionRepository promptVersionRepository;
    private final BehaviorRuleVersionRepository behaviorRuleVersionRepository;
    private final SkillDraftRepository skillDraftRepository;
    private final BehaviorRuleVersionToCustomRulesMapper rulesMapper;
    private final SkillDefinitionFromDraft skillDefinitionFromDraft;
    private final AgentDefinitionCloner cloner;

    public BundleApplicator(AgentService agentService,
                            PromptVersionRepository promptVersionRepository,
                            BehaviorRuleVersionRepository behaviorRuleVersionRepository,
                            SkillDraftRepository skillDraftRepository,
                            BehaviorRuleVersionToCustomRulesMapper rulesMapper,
                            SkillDefinitionFromDraft skillDefinitionFromDraft,
                            AgentDefinitionCloner cloner) {
        this.agentService = agentService;
        this.promptVersionRepository = promptVersionRepository;
        this.behaviorRuleVersionRepository = behaviorRuleVersionRepository;
        this.skillDraftRepository = skillDraftRepository;
        this.rulesMapper = rulesMapper;
        this.skillDefinitionFromDraft = skillDefinitionFromDraft;
        this.cloner = cloner;
    }

    /**
     * Result of {@link #apply}: the concrete {@link AgentDefinition} to run plus
     * the in-memory candidate skill definitions that the eval sandbox registry
     * must register for the agent loop to resolve the bundle's skill by name.
     * {@code extraSkills} is empty for prompt/rule-only bundles.
     */
    public record ApplyResult(AgentDefinition def, List<SkillDefinition> extraSkills) {}

    /**
     * Build the {@link ApplyResult} for {@code base} with {@code bundle} applied.
     * A {@code null} bundle (or one with all-null pointers) returns the agent's
     * current definition unchanged with no extra skills.
     *
     * @throws IllegalArgumentException a prompt / behavior_rule / skill pointer is
     *                                  unknown, or it doesn't belong to
     *                                  {@code base} (§7 W7 — a bundle pointer must
     *                                  belong to the target agent; a dead pointer
     *                                  fails loud).
     */
    public ApplyResult apply(AgentEntity base, Bundle bundle) {
        // Deep-clone so the A/B's two def instances never share nested mutable
        // state (BehaviorRulesConfig etc.). toAgentDefinition already builds a
        // fresh def, but the shared cloner keeps the isolation explicit (§7 W3).
        AgentDefinition def = cloner.clone(agentService.toAgentDefinition(base));
        if (bundle == null) {
            return new ApplyResult(def, List.of());
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

        // skill branch (Phase 4, §10 #4): load the draft content by id REGARDLESS
        // of status (best顺延 may reference a non-'draft' draft), validate ownership
        // (W7), build an in-memory SkillDefinition (skillPath=null, zero disk),
        // add its NAME to the def's skillIds so the agent loop resolves it, and
        // carry the def OUT in extraSkills for the eval sandbox to register.
        List<SkillDefinition> extraSkills = new ArrayList<>();
        if (bundle.skillDraftId() != null && !bundle.skillDraftId().isBlank()) {
            SkillDraftEntity draft = skillDraftRepository.findById(bundle.skillDraftId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "skill draft not found for bundle pointer: " + bundle.skillDraftId()));
            // §7 W7 ownership: an AGENT-SCOPED draft (non-null targetAgentId) must
            // belong to the target agent. Evolve drafts may carry a null
            // targetAgentId (the shared createDraftFromAttribution path doesn't set
            // it); the orchestrator that composed the bundle is system-trusted, so
            // a null is tolerated (logged) rather than blocked. A NON-null mismatch
            // is a real cross-agent pointer → fail loud.
            if (draft.getTargetAgentId() != null
                    && !baseAgentId.equals(String.valueOf(draft.getTargetAgentId()))) {
                throw new IllegalArgumentException(
                        "skill draft " + bundle.skillDraftId() + " targets agent "
                                + draft.getTargetAgentId() + " but bundle targets agent " + baseAgentId
                                + " — a bundle pointer must belong to the target agent");
            }
            if (draft.getTargetAgentId() == null) {
                log.debug("[BundleApplicator] skill draft {} has null targetAgentId — "
                        + "tolerated (system-driven evolve draft) for agent {}",
                        bundle.skillDraftId(), baseAgentId);
            }
            SkillDefinition skillDef = skillDefinitionFromDraft.build(draft);
            List<String> skillIds = def.getSkillIds() != null
                    ? new ArrayList<>(def.getSkillIds()) : new ArrayList<>();
            if (!skillIds.contains(skillDef.getName())) {
                skillIds.add(skillDef.getName());
            }
            def.setSkillIds(skillIds);
            extraSkills.add(skillDef);
            log.debug("[BundleApplicator] applied skill draft {} (name='{}') to agent {}",
                    bundle.skillDraftId(), skillDef.getName(), baseAgentId);
        }

        return new ApplyResult(def, extraSkills);
    }
}
