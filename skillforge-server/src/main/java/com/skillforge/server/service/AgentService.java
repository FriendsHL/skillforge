package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.core.engine.hook.FailurePolicy;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ReasoningEffort;
import com.skillforge.core.model.ThinkingMode;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.exception.AgentNotFoundException;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** Hard cap on lifecycle_hooks JSON payload size to prevent unbounded writes / memory blowups. */
    private static final int LIFECYCLE_HOOKS_MAX_BYTES = 65_536;

    /** Hard cap on {@link HookHandler.ScriptHandler#getScriptBody()} chars. */
    private static final int SCRIPT_BODY_MAX_CHARS = 4_096;

    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;
    private final BehaviorRuleRegistry behaviorRuleRegistry;

    public AgentService(AgentRepository agentRepository,
                        ObjectMapper objectMapper,
                        BehaviorRuleRegistry behaviorRuleRegistry) {
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
        this.behaviorRuleRegistry = behaviorRuleRegistry;
    }

    public AgentEntity createAgent(AgentEntity agent) {
        validateLifecycleHooksSize(agent);
        validateLifecycleHooksSemantics(agent);
        return agentRepository.save(agent);
    }

    // Partial update: null fields in the payload mean "don't change". Only non-null
    // fields overwrite. Exception: isPublic is a primitive boolean, so it's always
    // written — callers that send partial payloads should include isPublic or accept
    // its reset to false. When adding new fields to AgentEntity, add a null-guarded
    // setter here.
    @Transactional
    public AgentEntity updateAgent(Long id, AgentEntity updated) {
        validateLifecycleHooksSize(updated);
        validateLifecycleHooksSemantics(updated);
        AgentEntity existing = agentRepository.findById(id)
                .orElseThrow(() -> new AgentNotFoundException(id));
        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getRole() != null) {
            String role = updated.getRole().trim();
            existing.setRole(role.isEmpty() ? null : role);
        }
        if (updated.getModelId() != null) existing.setModelId(updated.getModelId());
        if (updated.getSystemPrompt() != null) existing.setSystemPrompt(updated.getSystemPrompt());
        if (updated.getSkillIds() != null) existing.setSkillIds(updated.getSkillIds());
        if (updated.getToolIds() != null) existing.setToolIds(updated.getToolIds());
        if (updated.getConfig() != null) existing.setConfig(updated.getConfig());
        if (updated.getSoulPrompt() != null) existing.setSoulPrompt(updated.getSoulPrompt());
        if (updated.getToolsPrompt() != null) existing.setToolsPrompt(updated.getToolsPrompt());
        if (updated.getBehaviorRules() != null) existing.setBehaviorRules(updated.getBehaviorRules());
        if (updated.getLifecycleHooks() != null) existing.setLifecycleHooks(updated.getLifecycleHooks());
        if (updated.getOwnerId() != null) existing.setOwnerId(updated.getOwnerId());
        if (updated.getStatus() != null) existing.setStatus(updated.getStatus());
        if (updated.getMaxLoops() != null) existing.setMaxLoops(updated.getMaxLoops());
        if (updated.getExecutionMode() != null) existing.setExecutionMode(updated.getExecutionMode());
        if (updated.getThinkingMode() != null) existing.setThinkingMode(updated.getThinkingMode());
        if (updated.getReasoningEffort() != null) existing.setReasoningEffort(updated.getReasoningEffort());
        // NOTE: isPublic is a primitive boolean on AgentEntity; cannot distinguish "unset"
        // from "false" without changing the entity field type. Keep current behavior for
        // this field — PUT always writes it. Changing this is deferred to a separate PR
        // that evaluates switching the field to Boolean.
        existing.setPublic(updated.isPublic());
        AgentEntity saved = agentRepository.save(existing);
        log.info("Agent {} updated: fields={}", id, nonNullFieldNames(updated));
        return saved;
    }

    /**
     * Collect the list of fields that were non-null in the update payload. Only emits field
     * names (never values) — systemPrompt / behaviorRules / lifecycleHooks may contain
     * user-supplied content that could leak secrets. isPublic is a primitive boolean and
     * is always written, so it's always included.
     */
    private static List<String> nonNullFieldNames(AgentEntity a) {
        List<String> fields = new ArrayList<>();
        if (a.getName() != null) fields.add("name");
        if (a.getDescription() != null) fields.add("description");
        if (a.getRole() != null) fields.add("role");
        if (a.getModelId() != null) fields.add("modelId");
        if (a.getSystemPrompt() != null) fields.add("systemPrompt");
        if (a.getSkillIds() != null) fields.add("skillIds");
        if (a.getToolIds() != null) fields.add("toolIds");
        if (a.getConfig() != null) fields.add("config");
        if (a.getSoulPrompt() != null) fields.add("soulPrompt");
        if (a.getToolsPrompt() != null) fields.add("toolsPrompt");
        if (a.getBehaviorRules() != null) fields.add("behaviorRules");
        if (a.getLifecycleHooks() != null) fields.add("lifecycleHooks");
        if (a.getOwnerId() != null) fields.add("ownerId");
        if (a.getStatus() != null) fields.add("status");
        if (a.getMaxLoops() != null) fields.add("maxLoops");
        if (a.getExecutionMode() != null) fields.add("executionMode");
        if (a.getThinkingMode() != null) fields.add("thinkingMode");
        if (a.getReasoningEffort() != null) fields.add("reasoningEffort");
        // isPublic is primitive; always written (see updateAgent note)
        fields.add("isPublic");
        return fields;
    }

    public void deleteAgent(Long id) {
        agentRepository.deleteById(id);
    }

    private static void validateLifecycleHooksSize(AgentEntity agent) {
        if (agent == null) return;
        String hooks = agent.getLifecycleHooks();
        if (hooks != null && hooks.length() > LIFECYCLE_HOOKS_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "lifecycle_hooks payload exceeds " + LIFECYCLE_HOOKS_MAX_BYTES + " char limit");
        }
    }

    /**
     * Semantic validation on every {@link HookEntry} after JSON parse:
     * <ul>
     *   <li>{@code ScriptHandler.scriptBody} length &le; {@link #SCRIPT_BODY_MAX_CHARS}. Backend
     *       is the source of truth — frontend Zod is convenience only.</li>
     *   <li>Reject {@code async=true && failurePolicy=SKIP_CHAIN} — the combination has no
     *       meaningful semantics; async entries cannot rewrite the chain.</li>
     * </ul>
     * Throws {@link IllegalArgumentException} → Spring maps to 400 at the controller layer.
     */
    private void validateLifecycleHooksSemantics(AgentEntity agent) {
        if (agent == null) return;
        String json = agent.getLifecycleHooks();
        if (json == null || json.isBlank()) return;

        LifecycleHooksConfig cfg;
        try {
            cfg = parseLifecycleHooksLenient(agent);
        } catch (Exception e) {
            // Parser is already lenient; hard failure here means malformed top-level JSON —
            // let the persist layer accept it (subsequent read returns empty config).
            return;
        }

        for (Map.Entry<HookEvent, List<HookEntry>> ev : cfg.getHooks().entrySet()) {
            List<HookEntry> entries = ev.getValue();
            if (entries == null) continue;
            for (int i = 0; i < entries.size(); i++) {
                HookEntry entry = entries.get(i);
                if (entry == null) continue;
                HookHandler h = entry.getHandler();
                if (h instanceof HookHandler.ScriptHandler sh) {
                    String body = sh.getScriptBody();
                    if (body != null && body.length() > SCRIPT_BODY_MAX_CHARS) {
                        throw new IllegalArgumentException(
                                "scriptBody exceeds " + SCRIPT_BODY_MAX_CHARS + " char limit "
                                        + "(event=" + ev.getKey().wireName() + " index=" + i + ")");
                    }
                }
                if (entry.isAsync() && entry.getFailurePolicy() == FailurePolicy.SKIP_CHAIN) {
                    throw new IllegalArgumentException(
                            "async entry cannot use SKIP_CHAIN policy "
                                    + "(event=" + ev.getKey().wireName() + " index=" + i + ")");
                }
            }
        }
    }

    public AgentEntity getAgent(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new AgentNotFoundException(id));
    }

    public List<AgentEntity> listAgents(Long ownerId) {
        if (ownerId != null) {
            return agentRepository.findByOwnerId(ownerId);
        }
        return agentRepository.findAll();
    }

    public List<AgentEntity> listPublicAgents() {
        return agentRepository.findByIsPublicTrue();
    }

    /**
     * 将 AgentEntity 转为 core 模块的 AgentDefinition，解析 JSON 字段。
     */
    public AgentDefinition toAgentDefinition(AgentEntity entity) {
        AgentDefinition def = new AgentDefinition();
        def.setId(String.valueOf(entity.getId()));
        def.setName(entity.getName());
        def.setDescription(entity.getDescription());
        if (entity.getRole() != null && !entity.getRole().isBlank()) {
            def.getConfig().put("role", entity.getRole());
        }
        def.setModelId(entity.getModelId());
        def.setSystemPrompt(entity.getSystemPrompt());
        def.setSoulPrompt(entity.getSoulPrompt());
        def.setToolsPrompt(entity.getToolsPrompt());

        // 解析 skillIds JSON
        if (entity.getSkillIds() != null && !entity.getSkillIds().isBlank()) {
            try {
                List<String> ids = objectMapper.readValue(entity.getSkillIds(),
                        new TypeReference<List<String>>() {});
                def.setSkillIds(ids);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse skillIds JSON: {}", entity.getSkillIds(), e);
                def.setSkillIds(new ArrayList<>());
            }
        }

        // 解析 toolIds JSON → 放入 config.tool_ids
        if (entity.getToolIds() != null && !entity.getToolIds().isBlank()) {
            try {
                List<String> toolIdList = objectMapper.readValue(entity.getToolIds(),
                        new TypeReference<List<String>>() {});
                if (!toolIdList.isEmpty()) {
                    def.getConfig().put("tool_ids", toolIdList);
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse toolIds JSON: {}", entity.getToolIds(), e);
            }
        }

        // 解析 config JSON
        if (entity.getConfig() != null && !entity.getConfig().isBlank()) {
            try {
                Map<String, Object> configMap = objectMapper.readValue(entity.getConfig(),
                        new TypeReference<Map<String, Object>>() {});
                def.setConfig(configMap);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse config JSON: {}", entity.getConfig(), e);
                def.setConfig(new HashMap<>());
            }
        }

        // Pass agent-level maxLoops into config map
        if (entity.getMaxLoops() != null) {
            def.getConfig().put("max_loops", entity.getMaxLoops());
        }

        // Per-agent thinking-mode / reasoning-effort overrides (null = provider default).
        if (entity.getThinkingMode() != null) {
            ThinkingMode tm = ThinkingMode.fromString(entity.getThinkingMode());
            if (tm != null) {
                def.setThinkingMode(tm);
            }
        }
        if (entity.getReasoningEffort() != null) {
            ReasoningEffort re = ReasoningEffort.fromString(entity.getReasoningEffort());
            if (re != null) {
                def.setReasoningEffort(re);
            }
        }

        // Parse behaviorRules JSON -> BehaviorRulesConfig, then resolve prompt texts
        if (entity.getBehaviorRules() != null && !entity.getBehaviorRules().isBlank()) {
            try {
                AgentDefinition.BehaviorRulesConfig rulesConfig = objectMapper.readValue(
                        entity.getBehaviorRules(), AgentDefinition.BehaviorRulesConfig.class);
                def.setBehaviorRules(rulesConfig);
                def.setResolvedBehaviorRules(
                        behaviorRuleRegistry.resolvePromptTexts(
                                rulesConfig.getBuiltinRuleIds(), def.getSystemPrompt()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse behaviorRules for agent {}: {}",
                        entity.getId(), entity.getBehaviorRules(), e);
                def.setBehaviorRules(new AgentDefinition.BehaviorRulesConfig());
            }
        }

        // Parse lifecycleHooks JSON -> LifecycleHooksConfig.
        // Two-tier defense:
        //   - Outer try: top-level JSON must be valid; otherwise empty config.
        //   - Inner per-entry parse: a single bad HookEntry only drops itself; other entries survive.
        // Logs intentionally avoid the raw JSON payload to prevent leaking secrets in user-supplied hook configs.
        if (entity.getLifecycleHooks() != null && !entity.getLifecycleHooks().isBlank()) {
            def.setLifecycleHooks(parseLifecycleHooksLenient(entity));
        }

        return def;
    }

    private LifecycleHooksConfig parseLifecycleHooksLenient(AgentEntity entity) {
        JsonNode root;
        try {
            root = objectMapper.readTree(entity.getLifecycleHooks());
        } catch (Exception e) {
            log.warn("Failed to parse lifecycleHooks JSON for agent {}: {}",
                    entity.getId(), e.getClass().getSimpleName());
            return LifecycleHooksConfig.empty();
        }
        if (root == null || !root.isObject()) {
            return LifecycleHooksConfig.empty();
        }

        LifecycleHooksConfig cfg = new LifecycleHooksConfig();
        JsonNode versionNode = root.get("version");
        if (versionNode != null && versionNode.isInt()) {
            cfg.setVersion(versionNode.intValue());
        }

        JsonNode hooksNode = root.get("hooks");
        if (hooksNode == null || !hooksNode.isObject()) {
            return cfg;
        }

        Iterator<Map.Entry<String, JsonNode>> it = hooksNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> ev = it.next();
            String wireName = ev.getKey();
            HookEvent event = HookEvent.fromWire(wireName);
            if (event == null || ev.getValue() == null || !ev.getValue().isArray()) {
                continue;
            }
            List<HookEntry> entries = new ArrayList<>();
            for (JsonNode entryNode : ev.getValue()) {
                try {
                    HookEntry entry = objectMapper.treeToValue(entryNode, HookEntry.class);
                    if (entry != null) entries.add(entry);
                } catch (Exception entryErr) {
                    log.warn("Skipping malformed lifecycle hook entry for agent {} event {}: {}",
                            entity.getId(), wireName, entryErr.getClass().getSimpleName());
                }
            }
            if (!entries.isEmpty()) {
                cfg.putEntries(event, entries);
            }
        }
        return cfg;
    }
}
