package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.server.entity.SkillDraftEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 4 (§10 #2) — the single, shared
 * {@code SkillDraftEntity → in-memory SkillDefinition} builder. The agent-level
 * skill bundle ({@code BundleApplicator}) builds the eval candidate purely
 * in-memory (skillPath=null, zero disk, zero cleanup — §10 decision A) and
 * injects it into the eval sandbox registry.
 *
 * <p><b>Provenance.</b> The body mirrors
 * {@code SkillCreatorService.buildInMemoryDefinition} (line ~543) and the V5
 * {@code SkillAbEvalService.buildSkillDefinition} pattern — both already
 * production-tested the in-memory route. This is the canonical copy; the two
 * existing copies (SkillCreatorService / SimulatorTrialOrchestrator) should
 * converge onto this builder as a follow-up (backlog, §10 — not refactored now
 * to keep Phase 4 surgical).
 *
 * <p><b>CRITICAL invariant (mirrors SkillCreatorService:570-573).</b>
 * {@code allowedTools} is set to {@code requiredTools}. Without it the agent
 * loop's tool-gate rejects the skill's tool calls as "skill didn't declare the
 * tool", silently degrading the eval.
 */
@Component
public class SkillDefinitionFromDraft {

    /**
     * Build an in-memory {@link SkillDefinition} from a persisted draft. The
     * resulting def's {@code name} equals {@code draft.getName()} so a caller can
     * add that name to {@code AgentDefinition.skillIds} and have the agent loop
     * resolve it from the (sandbox) registry by name.
     */
    public SkillDefinition build(SkillDraftEntity draft) {
        // W4: a null name → null skillIds entry → the agent loop resolves a skill
        // named null → not found → the skill's tool calls are silently rejected.
        // The DB column is NOT NULL so this only bites on hand-built in-memory state,
        // but fail loud rather than produce a silently-inert candidate skill.
        java.util.Objects.requireNonNull(draft.getName(),
                "SkillDraftEntity.name must not be null to build a SkillDefinition (draftId="
                        + draft.getId() + ")");
        SkillDefinition def = new SkillDefinition();
        def.setId(draft.getId());
        def.setName(draft.getName());
        def.setDescription(draft.getDescription());

        StringBuilder prompt = new StringBuilder();
        prompt.append("# ").append(draft.getName()).append("\n\n");
        if (draft.getDescription() != null && !draft.getDescription().isBlank()) {
            prompt.append(draft.getDescription()).append("\n\n");
        }
        if (draft.getPromptHint() != null && !draft.getPromptHint().isBlank()) {
            prompt.append(draft.getPromptHint()).append("\n\n");
        }
        if (draft.getTriggers() != null && !draft.getTriggers().isBlank()) {
            prompt.append("**Use when:** ").append(draft.getTriggers()).append("\n\n");
        }
        if (draft.getRequiredTools() != null && !draft.getRequiredTools().isBlank()) {
            prompt.append("**Required tools:** ").append(draft.getRequiredTools()).append("\n");
        }
        def.setPromptContent(prompt.toString());

        if (draft.getTriggers() != null && !draft.getTriggers().isBlank()) {
            def.setTriggers(splitCsv(draft.getTriggers()));
        }
        if (draft.getRequiredTools() != null && !draft.getRequiredTools().isBlank()) {
            List<String> tools = splitCsv(draft.getRequiredTools());
            def.setRequiredTools(tools);
            // allowed-tools mirrors required-tools — without it the agent loop's
            // tool-gate check rejects the call as "skill didn't declare tool".
            def.setAllowedTools(tools);
        }
        return def;
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
