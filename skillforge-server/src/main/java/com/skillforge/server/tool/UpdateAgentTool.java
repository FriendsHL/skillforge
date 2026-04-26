package com.skillforge.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.confirm.ToolApprovalRegistry;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentAuthoredHookService;
import com.skillforge.server.service.AgentMutationService;
import com.skillforge.server.service.AgentTargetResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mutates agent configuration only after the engine grants a one-shot approval token.
 *
 * <p>SEC-2 boundary: agent-authored hook changes are submitted through
 * {@link AgentAuthoredHookService#propose(AgentAuthoredHookService.ProposeRequest)} and remain
 * PENDING. This tool never approves agent-authored hooks and never serializes them into
 * {@code t_agent.lifecycle_hooks}.
 */
public class UpdateAgentTool implements Tool {

    public static final String NAME = "UpdateAgent";

    private static final Logger log = LoggerFactory.getLogger(UpdateAgentTool.class);
    private static final List<String> EXECUTION_MODES = List.of("ask", "auto");
    private static final List<String> THINKING_MODES = List.of("auto", "enabled", "disabled");
    private static final List<String> REASONING_EFFORTS = List.of("low", "medium", "high", "max");
    private static final List<String> STATUSES = List.of("active", "inactive", "archived");

    private final AgentTargetResolver targetResolver;
    private final AgentMutationService mutationService;
    private final ObjectMapper objectMapper;
    private final ToolApprovalRegistry approvalRegistry;

    public UpdateAgentTool(AgentTargetResolver targetResolver,
                           AgentMutationService mutationService,
                           ObjectMapper objectMapper,
                           ToolApprovalRegistry approvalRegistry) {
        this.targetResolver = targetResolver;
        this.mutationService = mutationService;
        this.objectMapper = objectMapper;
        this.approvalRegistry = approvalRegistry;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Patch an editable target agent after explicit human approval. "
                + "Supports normal config fields plus structured hookChanges. "
                + "agentAuthoredProposals are created as PENDING only and still require separate review.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetAgentId", Map.of("type", "integer", "description", "Optional target agent id"));
        properties.put("targetAgentName", Map.of("type", "string", "description", "Optional exact target agent name"));
        properties.put("patch", Map.of(
                "type", "object",
                "description", "Partial agent config patch. Supported fields: name, description, role, modelId, systemPrompt, soulPrompt, toolsPrompt, skills, tools, config, behaviorRules, visibility, status, executionMode, maxLoops, thinkingMode, reasoningEffort."
        ));
        properties.put("hookChanges", Map.of(
                "type", "object",
                "description", "Structured hook changes. userLifecycleHooks replaces the user hook JSON/object. agentAuthoredProposals creates PENDING agent-authored hook proposals."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (context == null || context.getSessionId() == null) {
                return SkillResult.validationError("sessionId is required");
            }
            Map<String, Object> args = input != null ? input : Map.of();
            AgentEntity patch = toPatch(objectMap(args.get("patch"), "patch"));
            Map<String, Object> hookChanges = objectMap(args.get("hookChanges"), "hookChanges");
            applyUserHookChanges(hookChanges, patch);

            if (!approvalRegistry.consume(
                    context.getApprovalToken(), context.getSessionId(), getName(), context.getToolUseId())) {
                return SkillResult.error("UpdateAgent requires explicit user approval before execution.");
            }

            AgentEntity target = targetResolver.resolveEditableTarget(
                    context.getSessionId(), context.getUserId(),
                    args.get("targetAgentId"), args.get("targetAgentName"));
            Long authorAgentId = targetResolver.authorAgentIdForSession(context.getSessionId());

            List<AgentAuthoredHookService.ProposeRequest> proposals =
                    toHookProposals(target.getId(), authorAgentId, context.getSessionId(),
                            hookChanges);

            if (isEmptyPatch(patch) && proposals.isEmpty()) {
                return SkillResult.validationError("UpdateAgent requires at least one patch field or hookChanges entry.");
            }

            AgentMutationService.UpdateResult updated =
                    mutationService.updateAgent(target.getId(), patch, proposals);
            return SkillResult.success(objectMapper.writeValueAsString(toDto(updated)));
        } catch (IllegalArgumentException e) {
            return SkillResult.validationError(e.getMessage());
        } catch (Exception e) {
            log.error("UpdateAgentTool execute failed", e);
            return SkillResult.error("UpdateAgent error: " + e.getMessage());
        }
    }

    private AgentEntity toPatch(Map<String, Object> patchMap) throws JsonProcessingException {
        AgentEntity patch = new AgentEntity();
        if (patchMap == null || patchMap.isEmpty()) {
            return patch;
        }
        rejectField(patchMap, "id");
        rejectField(patchMap, "ownerId");
        rejectField(patchMap, "lifecycleHooks");
        rejectField(patchMap, "lifecycleHooksRaw");

        if (patchMap.containsKey("name")) patch.setName(requiredString(patchMap.get("name"), "name"));
        if (patchMap.containsKey("description")) patch.setDescription(stringOrEmpty(patchMap.get("description")));
        if (patchMap.containsKey("role")) patch.setRole(stringOrEmpty(patchMap.get("role")));
        if (patchMap.containsKey("modelId")) patch.setModelId(stringOrEmpty(patchMap.get("modelId")));
        if (patchMap.containsKey("systemPrompt")) patch.setSystemPrompt(stringOrEmpty(patchMap.get("systemPrompt")));
        if (patchMap.containsKey("soulPrompt")) patch.setSoulPrompt(stringOrEmpty(patchMap.get("soulPrompt")));
        if (patchMap.containsKey("toolsPrompt")) patch.setToolsPrompt(stringOrEmpty(patchMap.get("toolsPrompt")));
        if (patchMap.containsKey("skills") || patchMap.containsKey("skillIds")) {
            patch.setSkillIds(objectMapper.writeValueAsString(
                    stringList(firstNonNull(patchMap.get("skills"), patchMap.get("skillIds")), "skills")));
        }
        if (patchMap.containsKey("tools") || patchMap.containsKey("toolIds")) {
            patch.setToolIds(objectMapper.writeValueAsString(
                    stringList(firstNonNull(patchMap.get("tools"), patchMap.get("toolIds")), "tools")));
        }
        if (patchMap.containsKey("config")) {
            patch.setConfig(jsonObjectOrRaw(patchMap.get("config"), "config"));
        }
        if (patchMap.containsKey("behaviorRules")) {
            patch.setBehaviorRules(jsonObjectOrRaw(patchMap.get("behaviorRules"), "behaviorRules"));
        }
        if (patchMap.containsKey("visibility") || patchMap.containsKey("isPublic")) {
            patch.setPublic(parseVisibility(patchMap));
        }
        if (patchMap.containsKey("status")) {
            patch.setStatus(enumValue(patchMap.get("status"), "status", STATUSES, null));
        }
        if (patchMap.containsKey("executionMode")) {
            patch.setExecutionMode(enumValue(patchMap.get("executionMode"), "executionMode", EXECUTION_MODES, null));
        }
        if (patchMap.containsKey("maxLoops")) {
            patch.setMaxLoops(intValue(patchMap.get("maxLoops"), "maxLoops", 1, 100));
        }
        if (patchMap.containsKey("thinkingMode")) {
            String mode = enumValue(patchMap.get("thinkingMode"), "thinkingMode", THINKING_MODES, null);
            patch.setThinkingMode(mode);
        }
        if (patchMap.containsKey("reasoningEffort")) {
            patch.setReasoningEffort(enumValue(patchMap.get("reasoningEffort"), "reasoningEffort", REASONING_EFFORTS, null));
        }
        return patch;
    }

    private List<AgentAuthoredHookService.ProposeRequest> toHookProposals(
            Long targetAgentId,
            Long authorAgentId,
            String sessionId,
            Map<String, Object> hookChanges) {
        if (hookChanges == null || hookChanges.isEmpty()) {
            return List.of();
        }

        Object rawProposals = firstNonNull(
                hookChanges.get("agentAuthoredProposals"),
                hookChanges.get("proposeAgentAuthoredHooks"));
        if (rawProposals == null) {
            return List.of();
        }
        if (!(rawProposals instanceof List<?> list)) {
            throw new IllegalArgumentException("hookChanges.agentAuthoredProposals must be an array");
        }
        List<AgentAuthoredHookService.ProposeRequest> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> proposal = objectMap(item, "hookChanges.agentAuthoredProposals[]");
            if (proposal == null) {
                throw new IllegalArgumentException("hookChanges.agentAuthoredProposals[] must be objects");
            }
            out.add(new AgentAuthoredHookService.ProposeRequest(
                    targetAgentId,
                    authorAgentId,
                    sessionId,
                    asString(proposal.get("event")),
                    asString(proposal.get("methodTarget")),
                    asString(proposal.get("displayName")),
                    asString(proposal.get("description")),
                    asInteger(proposal.get("timeoutSeconds")),
                    asString(proposal.get("failurePolicy")),
                    Boolean.TRUE.equals(proposal.get("async")),
                    proposal.get("args"),
                    asLong(proposal.get("parentHookId"))));
        }
        return out;
    }

    private void applyUserHookChanges(Map<String, Object> hookChanges, AgentEntity patch) throws JsonProcessingException {
        if (hookChanges == null || hookChanges.isEmpty()) {
            return;
        }
        if (hookChanges.containsKey("userLifecycleHooks")) {
            patch.setLifecycleHooks(jsonObjectOrRaw(hookChanges.get("userLifecycleHooks"), "hookChanges.userLifecycleHooks"));
        }
        if (hookChanges.containsKey("userLifecycleHooksRaw")) {
            patch.setLifecycleHooks(stringOrEmpty(hookChanges.get("userLifecycleHooksRaw")));
        }
        rejectField(hookChanges, "agentAuthoredHooks");
        rejectField(hookChanges, "approveAgentAuthoredHooks");
        rejectField(hookChanges, "approvedAgentAuthoredHooks");
    }

    private Map<String, Object> toDto(AgentMutationService.UpdateResult result) {
        AgentEntity agent = result.agent();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", agent.getId());
        out.put("name", agent.getName());
        out.put("description", agent.getDescription());
        out.put("role", agent.getRole());
        out.put("modelId", agent.getModelId());
        out.put("visibility", Boolean.TRUE.equals(agent.isPublic()) ? "public" : "private");
        out.put("status", agent.getStatus());
        out.put("maxLoops", agent.getMaxLoops());
        out.put("executionMode", agent.getExecutionMode());
        out.put("thinkingMode", agent.getThinkingMode());
        out.put("reasoningEffort", agent.getReasoningEffort());
        List<Map<String, Object>> proposed = result.proposedHooks().stream()
                .map(this::proposedHookToDto)
                .toList();
        out.put("proposedAgentAuthoredHooks", proposed);
        out.put("requiresSeparateHookReview", !proposed.isEmpty());
        return out;
    }

    private Map<String, Object> proposedHookToDto(AgentAuthoredHookEntity hook) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", hook.getId());
        out.put("targetAgentId", hook.getTargetAgentId());
        out.put("authorAgentId", hook.getAuthorAgentId());
        out.put("event", hook.getEvent());
        out.put("methodKind", hook.getMethodKind());
        out.put("methodId", hook.getMethodId());
        out.put("methodRef", hook.getMethodRef());
        out.put("reviewState", hook.getReviewState());
        out.put("enabled", hook.isEnabled());
        return out;
    }

    private static boolean isEmptyPatch(AgentEntity patch) {
        return patch.getName() == null
                && patch.getDescription() == null
                && patch.getRole() == null
                && patch.getModelId() == null
                && patch.getSystemPrompt() == null
                && patch.getSkillIds() == null
                && patch.getToolIds() == null
                && patch.getConfig() == null
                && patch.getSoulPrompt() == null
                && patch.getToolsPrompt() == null
                && patch.getBehaviorRules() == null
                && patch.getLifecycleHooks() == null
                && patch.getStatus() == null
                && patch.getMaxLoops() == null
                && patch.getExecutionMode() == null
                && patch.getThinkingMode() == null
                && patch.getReasoningEffort() == null
                && patch.isPublic() == null;
    }

    private static Map<String, Object> objectMap(Object value, String field) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }

    private String jsonObjectOrRaw(Object value, String field) throws JsonProcessingException {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return objectMapper.writeValueAsString(value);
        }
        String raw = value.toString().trim();
        if (raw.isBlank()) {
            return "";
        }
        objectMapper.readTree(raw);
        return raw;
    }

    private static List<String> stringList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(field + " must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            String s = optionalString(item);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static Boolean parseVisibility(Map<String, Object> input) {
        Object rawVisibility = input.get("visibility");
        if (rawVisibility != null) {
            String visibility = rawVisibility.toString().trim().toLowerCase(Locale.ROOT);
            return switch (visibility) {
                case "public" -> true;
                case "private" -> false;
                default -> throw new IllegalArgumentException("visibility must be public or private");
            };
        }
        Object rawPublic = input.get("isPublic");
        if (rawPublic instanceof Boolean b) {
            return b;
        }
        if (rawPublic != null) {
            return Boolean.parseBoolean(rawPublic.toString());
        }
        return null;
    }

    private static String enumValue(Object value, String field, List<String> allowed, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return defaultValue;
        }
        if (!allowed.contains(s)) {
            throw new IllegalArgumentException(field + " must be one of: " + String.join(", ", allowed));
        }
        return s;
    }

    private static Integer intValue(Object value, String field, int min, int max) {
        if (value == null) {
            return null;
        }
        int parsed;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static String requiredString(Object value, String field) {
        String s = optionalString(value);
        if (s == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return s;
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static void rejectField(Map<String, Object> map, String field) {
        if (map != null && map.containsKey(field)) {
            throw new IllegalArgumentException(field + " is not supported here");
        }
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
