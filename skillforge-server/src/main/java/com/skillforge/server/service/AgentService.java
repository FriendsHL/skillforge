package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
        return agentRepository.save(agent);
    }

    // WARNING: This method manually copies each field. When adding new fields
    // to AgentEntity, you MUST add the corresponding setter here, or updates
    // will silently lose data.
    public AgentEntity updateAgent(Long id, AgentEntity updated) {
        validateLifecycleHooksSize(updated);
        AgentEntity existing = agentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + id));
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setModelId(updated.getModelId());
        existing.setSystemPrompt(updated.getSystemPrompt());
        existing.setSkillIds(updated.getSkillIds());
        existing.setToolIds(updated.getToolIds());
        existing.setConfig(updated.getConfig());
        existing.setSoulPrompt(updated.getSoulPrompt());
        existing.setToolsPrompt(updated.getToolsPrompt());
        existing.setBehaviorRules(updated.getBehaviorRules());
        existing.setLifecycleHooks(updated.getLifecycleHooks());
        existing.setOwnerId(updated.getOwnerId());
        existing.setPublic(updated.isPublic());
        existing.setStatus(updated.getStatus());
        existing.setMaxLoops(updated.getMaxLoops());
        if (updated.getExecutionMode() != null) {
            existing.setExecutionMode(updated.getExecutionMode());
        }
        return agentRepository.save(existing);
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

    public AgentEntity getAgent(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + id));
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
