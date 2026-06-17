package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.service.EvalDatasetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b — read-only tool that lists a target
 * agent's ACTIVE harvested (session-derived) bad-case scenario ids, so the
 * evolve orchestrator can pass them as the explicit target subset of an A/B run
 * ({@code TriggerAbEval(evalScenarioIds=...)}). Purely a lookup: it discovers
 * which active cases to target and measure — it neither activates anything (that
 * is the human gate) nor describes how to resolve any failure.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry} (see {@code SkillForgeConfig}); deliberately ABSENT from
 * {@code WorkflowSkillRegistryFactory} (the workflow sub-agent registry), same as
 * the other Module B/C evolve tools. The orchestrator runs top-level.
 */
public class ListActiveHarvestedScenariosTool implements Tool {

    public static final String NAME = "ListActiveHarvestedScenarios";

    private static final Logger log = LoggerFactory.getLogger(ListActiveHarvestedScenariosTool.class);

    private static final String STATUS_ACTIVE = "active";

    private final EvalScenarioDraftRepository scenarioRepository;
    private final EvalDatasetService evalDatasetService;
    private final ObjectMapper objectMapper;

    public ListActiveHarvestedScenariosTool(EvalScenarioDraftRepository scenarioRepository,
                                            EvalDatasetService evalDatasetService,
                                            ObjectMapper objectMapper) {
        this.scenarioRepository = scenarioRepository;
        this.evalDatasetService = evalDatasetService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "List the target agent's ACTIVE harvested (session-derived) bad-case eval "
                + "scenario ids, so they can be passed as the explicit target subset of an A/B "
                + "run (TriggerAbEval evalScenarioIds). Read-only lookup — it only reports which "
                + "active cases exist to target and measure; it does NOT activate drafts (human "
                + "gate) and does NOT describe any remedy. Input: {agentId}. Returns "
                + "{agentId, scenarioIds:[...], count, items:[{id, name, sourceRef}]}; scenarioIds "
                + "is [] (never null) when the agent has no active harvested scenarios.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agentId", Map.of(
                "type", "string",
                "description", "The target agent id whose active harvested scenarios to list."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("agentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required (agentId)");
            }
            String agentId = trimToNull(input.get("agentId"));
            if (agentId == null) {
                return SkillResult.validationError("agentId is required");
            }

            List<EvalScenarioEntity> rows = scenarioRepository.findByAgentIdAndStatusAndSourceType(
                    agentId, STATUS_ACTIVE, EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED);
            if (rows.isEmpty()) {
                // Nothing active → skip the default-version lookup entirely.
                return SkillResult.success(objectMapper.writeValueAsString(
                        payload(agentId, new ArrayList<>(), new ArrayList<>())));
            }

            // Membership filter (design W2): only return ids that are actually MEMBERS
            // of the agent's currently-resolved default dataset version. A scenario can
            // be flipped to active by a side path (e.g. EvalScenarioDraft approve)
            // WITHOUT being added to any dataset version; handing such a non-member id
            // to the orchestrator would make resolveRoleSplit silently drop it (target
            // empty → general-only). So an "active" id is only a usable target if it's
            // a member. (The activate endpoint always adds it = member, so the happy
            // path is unaffected.)
            Set<String> memberIds = resolveDefaultVersionMemberIds(agentId);

            List<String> scenarioIds = new ArrayList<>();
            List<Map<String, Object>> items = new ArrayList<>();
            for (EvalScenarioEntity s : rows) {
                if (!memberIds.contains(s.getId())) {
                    continue;
                }
                scenarioIds.add(s.getId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", s.getId());
                item.put("name", s.getName());
                item.put("sourceRef", s.getSourceRef());
                items.add(item);
            }

            log.info("[ListActiveHarvestedScenarios] agentId={} active={} member-targets={}",
                    agentId, rows.size(), scenarioIds.size());
            return SkillResult.success(objectMapper.writeValueAsString(
                    payload(agentId, scenarioIds, items)));
        } catch (Exception e) {
            log.error("ListActiveHarvestedScenarios execute failed", e);
            return SkillResult.error("ListActiveHarvestedScenarios error: " + e.getMessage());
        }
    }

    private static Map<String, Object> payload(String agentId, List<String> scenarioIds,
                                               List<Map<String, Object>> items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agentId);
        payload.put("scenarioIds", scenarioIds);
        payload.put("count", scenarioIds.size());
        payload.put("items", items);
        return payload;
    }

    /**
     * Scenario ids that are members of the agent's currently-resolved default
     * dataset version. Empty when the agent has no resolvable default version (so
     * nothing qualifies as a target).
     */
    private Set<String> resolveDefaultVersionMemberIds(String agentId) {
        String defaultVersionId = evalDatasetService.findDefaultVersionIdForAgent(agentId);
        if (defaultVersionId == null || defaultVersionId.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (EvalScenarioEntity m : scenarioRepository.findAllByDatasetVersionId(defaultVersionId)) {
            ids.add(m.getId());
        }
        return ids;
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
