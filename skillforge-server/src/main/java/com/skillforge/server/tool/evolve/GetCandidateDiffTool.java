package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — read-only tool that resolves the {@code before} /
 * {@code after} text of a generated candidate so the evolve-loop workflow JS can
 * assemble a semantic delta ({@code surface, before, after, diff}) and emit it
 * into the iteration's step ledger for traceability (design §3.2 path a).
 *
 * <p><b>Why a tool (vs the LLM emitting full text).</b> Having the candidate-gen
 * agent leaf return full before/after text would burn tokens and risk truncation.
 * Instead the leaf returns only {@code candidateId}; the deterministic workflow JS
 * calls this read-only tool to fetch the texts and computes the diff — cheap and
 * exact.
 *
 * <p><b>Inputs:</b> {@code candidateId} (the generated version/draft id),
 * {@code surface} ({@code prompt} / {@code behavior_rule} / {@code skill}),
 * optional {@code baseVersionId} (the current-best the candidate was built on —
 * iteration 2+ hill-climb). When {@code baseVersionId} is absent the "before" is
 * resolved to the agent's current active surface (iteration 1).
 *
 * <p><b>Recursion guard (invariant).</b> Read-only; registered in the workflow
 * {@code tool()} whitelist registry ({@code WorkflowEvolveToolRegistryFactory}) —
 * NOT exposed to LLM sub-agents.
 */
public class GetCandidateDiffTool implements Tool {

    public static final String NAME = "GetCandidateDiff";

    /** Above this many lines on either side, fall back to a block (non-LCS) diff. */
    private static final int LCS_LINE_CAP = 2000;

    private static final Logger log = LoggerFactory.getLogger(GetCandidateDiffTool.class);

    private final PromptVersionRepository promptVersionRepository;
    private final BehaviorRuleVersionRepository behaviorRuleVersionRepository;
    private final SkillDraftRepository skillDraftRepository;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public GetCandidateDiffTool(PromptVersionRepository promptVersionRepository,
                                BehaviorRuleVersionRepository behaviorRuleVersionRepository,
                                SkillDraftRepository skillDraftRepository,
                                AgentRepository agentRepository,
                                ObjectMapper objectMapper) {
        this.promptVersionRepository = promptVersionRepository;
        this.behaviorRuleVersionRepository = behaviorRuleVersionRepository;
        this.skillDraftRepository = skillDraftRepository;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Resolve the before/after text + unified diff of a generated candidate (read-only). "
                + "Inputs:\n"
                + "- \"candidateId\": the generated version/draft id.\n"
                + "- \"surface\": \"prompt\" / \"behavior_rule\" / \"skill\".\n"
                + "- \"baseVersionId\" (optional): the current-best the candidate built on (iter 2+); "
                + "omit on iteration 1 to diff against the agent's active surface.\n"
                + "Returns { surface, before, after, diff }.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("candidateId", Map.of("type", "string",
                "description", "The generated version/draft id."));
        properties.put("surface", Map.of("type", "string",
                "enum", EvolveSurface.v1NonAgentWireValues(),
                "description", "Optimisation surface: prompt / behavior_rule / skill."));
        properties.put("baseVersionId", Map.of("type", "string",
                "description", "Optional current-best id the candidate built on (iter 2+)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("candidateId", "surface"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (candidateId, surface)");
            }
            String candidateId = trimToNull(input.get("candidateId"));
            if (candidateId == null) {
                return SkillResult.validationError("candidateId is required");
            }
            EvolveSurface surface = EvolveSurface.fromWire(trimToNull(input.get("surface")));
            if (surface == null || surface == EvolveSurface.AGENT) {
                return SkillResult.validationError(
                        "surface is required and must be one of: " + EvolveSurface.v1NonAgentAcceptedValues());
            }
            String baseVersionId = trimToNull(input.get("baseVersionId"));

            String before;
            String after;
            switch (surface) {
                case PROMPT -> {
                    PromptVersionEntity cand = promptVersionRepository.findById(candidateId).orElse(null);
                    if (cand == null) {
                        return SkillResult.validationError("prompt version not found: " + candidateId);
                    }
                    after = nullToEmpty(cand.getContent());
                    before = baseVersionId != null
                            ? nullToEmpty(promptVersionRepository.findById(baseVersionId)
                                    .map(PromptVersionEntity::getContent).orElse(null))
                            : resolveActivePrompt(cand.getAgentId());
                }
                case BEHAVIOR_RULE -> {
                    BehaviorRuleVersionEntity cand =
                            behaviorRuleVersionRepository.findById(candidateId).orElse(null);
                    if (cand == null) {
                        return SkillResult.validationError("behavior_rule version not found: " + candidateId);
                    }
                    after = nullToEmpty(cand.getRulesJson());
                    String baseId = baseVersionId != null ? baseVersionId : cand.getBaselineVersionId();
                    before = baseId != null
                            ? nullToEmpty(behaviorRuleVersionRepository.findById(baseId)
                                    .map(BehaviorRuleVersionEntity::getRulesJson).orElse(null))
                            : resolveActiveBehaviorRule(cand.getAgentId());
                }
                case SKILL -> {
                    SkillDraftEntity cand = skillDraftRepository.findById(candidateId).orElse(null);
                    if (cand == null) {
                        return SkillResult.validationError("skill draft not found: " + candidateId);
                    }
                    after = renderSkillDraft(cand);
                    before = baseVersionId != null
                            ? skillDraftRepository.findById(baseVersionId)
                                    .map(this::renderSkillDraft).orElse("")
                            : "";   // iter-1 skill = fresh draft, no prior text
                }
                default -> {
                    return SkillResult.validationError("unsupported surface: " + surface.wire());
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("surface", surface.wire());
            response.put("before", before);
            response.put("after", after);
            response.put("diff", unifiedDiff(before, after));

            log.info("[GetCandidateDiff] surface={} candidateId={} baseVersionId={} beforeLen={} afterLen={}",
                    surface.wire(), candidateId, baseVersionId, before.length(), after.length());
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("GetCandidateDiff execute failed", e);
            return SkillResult.error("GetCandidateDiff error: " + e.getMessage());
        }
    }

    /** The agent's current active prompt content, falling back to its system_prompt. */
    private String resolveActivePrompt(String agentId) {
        if (agentId == null) {
            return "";
        }
        List<PromptVersionEntity> active =
                promptVersionRepository.findByAgentIdAndStatus(agentId, "active");
        if (active != null && !active.isEmpty()) {
            return nullToEmpty(active.get(0).getContent());
        }
        return nullToEmpty(agentSystemPrompt(agentId));
    }

    /** The agent's current active behavior-rule rulesJson (empty when none). */
    private String resolveActiveBehaviorRule(String agentId) {
        if (agentId == null) {
            return "";
        }
        return nullToEmpty(behaviorRuleVersionRepository.findByAgentIdAndStatus(agentId, "active")
                .map(BehaviorRuleVersionEntity::getRulesJson).orElse(null));
    }

    private String agentSystemPrompt(String agentId) {
        try {
            Long id = Long.parseLong(agentId);
            return agentRepository.findById(id).map(AgentEntity::getSystemPrompt).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Stable human-readable render of a skill draft for diffing. */
    private String renderSkillDraft(SkillDraftEntity d) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(nullToEmpty(d.getName())).append('\n');
        sb.append("triggers: ").append(nullToEmpty(d.getTriggers())).append('\n');
        sb.append("requiredTools: ").append(nullToEmpty(d.getRequiredTools())).append('\n');
        sb.append("promptHint: ").append(nullToEmpty(d.getPromptHint()));
        return sb.toString();
    }

    /**
     * A compact line-level diff. Uses an LCS so unchanged lines are preserved as
     * context ({@code " "}), removed lines as {@code "- "}, added lines as
     * {@code "+ "}. Above {@link #LCS_LINE_CAP} lines on either side it falls back
     * to a block diff (all removed, then all added) to avoid O(n*m) blow-up.
     */
    static String unifiedDiff(String before, String after) {
        if (before.equals(after)) {
            return "(no change)";
        }
        String[] a = before.isEmpty() ? new String[0] : before.split("\n", -1);
        String[] b = after.isEmpty() ? new String[0] : after.split("\n", -1);
        if (a.length > LCS_LINE_CAP || b.length > LCS_LINE_CAP) {
            StringBuilder sb = new StringBuilder();
            for (String line : a) sb.append("- ").append(line).append('\n');
            for (String line : b) sb.append("+ ").append(line).append('\n');
            return sb.toString();
        }
        int n = a.length, m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        StringBuilder sb = new StringBuilder();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                sb.append("  ").append(a[i]).append('\n');
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                sb.append("- ").append(a[i]).append('\n');
                i++;
            } else {
                sb.append("+ ").append(b[j]).append('\n');
                j++;
            }
        }
        while (i < n) sb.append("- ").append(a[i++]).append('\n');
        while (j < m) sb.append("+ ").append(b[j++]).append('\n');
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
