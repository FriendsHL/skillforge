package com.skillforge.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.skillforge.server.entity.AgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link AgentEntity} to / from a user-friendly YAML Map shape.
 *
 * YAML schema:
 * <pre>
 * name: ...
 * description: ...
 * modelId: ...
 * executionMode: ask
 * public: false
 * systemPrompt: |
 *   ...
 * skills:
 *   - Bash
 *   - FileRead
 * config:
 *   key: value
 * </pre>
 *
 * Note: {@code skills:} is a list in YAML but {@code AgentEntity.skillIds} is a
 * JSON-encoded TEXT column. This mapper hides that difference from callers.
 */
public class AgentYamlMapper {

    private static final Logger log = LoggerFactory.getLogger(AgentYamlMapper.class);

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final ObjectMapper YAML;

    static {
        YAMLFactory f = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        YAML = new ObjectMapper(f).findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private AgentYamlMapper() {}

    public static ObjectMapper yamlMapper() {
        return YAML;
    }

    /**
     * Parse a YAML string into an {@link AgentEntity}. The caller is expected
     * to persist the returned entity. Does not set {@code id}, {@code ownerId},
     * {@code createdAt} or {@code updatedAt}.
     *
     * @throws IllegalArgumentException on parse errors or missing required fields
     */
    @SuppressWarnings("unchecked")
    public static AgentEntity fromYaml(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) {
            throw new IllegalArgumentException("Empty YAML body");
        }
        Map<String, Object> m;
        try {
            m = YAML.readValue(yamlText, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getMessage(), e);
        }
        if (m == null) {
            throw new IllegalArgumentException("YAML parsed to null");
        }
        AgentEntity a = new AgentEntity();
        Object name = m.get("name");
        if (name == null || name.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required field: name");
        }
        a.setName(name.toString());
        if (m.get("description") != null) a.setDescription(m.get("description").toString());
        if (m.get("modelId") != null) a.setModelId(m.get("modelId").toString());
        if (m.get("systemPrompt") != null) a.setSystemPrompt(m.get("systemPrompt").toString());
        if (m.get("soulPrompt") != null) a.setSoulPrompt(m.get("soulPrompt").toString());
        if (m.get("toolsPrompt") != null) a.setToolsPrompt(m.get("toolsPrompt").toString());
        if (m.get("executionMode") != null) a.setExecutionMode(m.get("executionMode").toString());
        Object pub = m.get("public");
        if (pub instanceof Boolean) a.setPublic((Boolean) pub);
        else if (pub != null) a.setPublic(Boolean.parseBoolean(pub.toString()));

        // Prefer skillIdsRaw if present (set by toYaml on corrupt input so a
        // round-trip preserves the user's data verbatim).
        Object rawSkillIds = m.get("skillIdsRaw");
        if (rawSkillIds != null) {
            a.setSkillIds(rawSkillIds.toString());
        } else {
            Object skillsObj = m.get("skills");
            List<String> skills = new ArrayList<>();
            if (skillsObj instanceof List) {
                for (Object o : (List<Object>) skillsObj) {
                    if (o != null) skills.add(o.toString());
                }
            }
            try {
                a.setSkillIds(JSON.writeValueAsString(skills));
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize skills list", e);
            }
        }

        // behaviorRules — prefer raw if present (corrupt round-trip)
        Object rawBehaviorRules = m.get("behaviorRulesRaw");
        if (rawBehaviorRules != null) {
            a.setBehaviorRules(rawBehaviorRules.toString());
        } else {
            Object brObj = m.get("behaviorRules");
            if (brObj instanceof Map) {
                try {
                    a.setBehaviorRules(JSON.writeValueAsString(brObj));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Failed to serialize behaviorRules", e);
                }
            }
        }

        // lifecycleHooks — same corrupt-defense pattern as behaviorRules
        Object rawLifecycleHooks = m.get("lifecycleHooksRaw");
        if (rawLifecycleHooks != null) {
            a.setLifecycleHooks(rawLifecycleHooks.toString());
        } else {
            Object lhObj = m.get("lifecycleHooks");
            if (lhObj instanceof Map) {
                try {
                    a.setLifecycleHooks(JSON.writeValueAsString(lhObj));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Failed to serialize lifecycleHooks", e);
                }
            }
        }

        Object configObj = m.get("config");
        if (configObj instanceof Map) {
            try {
                a.setConfig(JSON.writeValueAsString(configObj));
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize config map", e);
            }
        }
        a.setStatus("active");
        return a;
    }

    /** Serialize an {@link AgentEntity} to a YAML string matching the schema. */
    public static String toYaml(AgentEntity entity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", entity.getName());
        if (entity.getDescription() != null) m.put("description", entity.getDescription());
        if (entity.getModelId() != null) m.put("modelId", entity.getModelId());
        if (entity.getExecutionMode() != null) m.put("executionMode", entity.getExecutionMode());
        m.put("public", entity.isPublic());
        if (entity.getSystemPrompt() != null) m.put("systemPrompt", entity.getSystemPrompt());
        if (entity.getSoulPrompt() != null) m.put("soulPrompt", entity.getSoulPrompt());
        if (entity.getToolsPrompt() != null) m.put("toolsPrompt", entity.getToolsPrompt());

        List<String> skills = new ArrayList<>();
        String skillIdsJson = entity.getSkillIds();
        boolean corrupt = false;
        String rawCorruptValue = null;
        if (skillIdsJson != null && !skillIdsJson.isBlank()) {
            try {
                JsonNode arr = JSON.readTree(skillIdsJson);
                if (arr != null && arr.isArray()) {
                    for (JsonNode el : arr) {
                        skills.add(el.asText());
                    }
                } else {
                    log.warn("AgentYamlMapper: skillIds is not a JSON array, exporting as raw string. agentId={} value='{}'",
                            entity.getId(), skillIdsJson);
                    corrupt = true;
                    rawCorruptValue = skillIdsJson;
                }
            } catch (Exception e) {
                log.warn("AgentYamlMapper: failed to parse skillIds, exporting as raw string. agentId={} value='{}'",
                        entity.getId(), skillIdsJson, e);
                corrupt = true;
                rawCorruptValue = skillIdsJson;
            }
        }
        m.put("skills", skills);
        if (corrupt) {
            // Surface the raw form so round-trip through fromYaml() preserves
            // the user's (corrupted) data instead of silently losing it.
            m.put("skillIdsRaw", rawCorruptValue);
        }

        // behaviorRules — same corrupt-data defense pattern as skillIds
        String brJson = entity.getBehaviorRules();
        boolean brCorrupt = false;
        String brRawCorruptValue = null;
        if (brJson != null && !brJson.isBlank()) {
            try {
                JsonNode brNode = JSON.readTree(brJson);
                if (brNode != null && brNode.isObject()) {
                    m.put("behaviorRules", JSON.convertValue(brNode, Map.class));
                } else {
                    brCorrupt = true;
                    brRawCorruptValue = brJson;
                }
            } catch (Exception e) {
                log.warn("AgentYamlMapper: failed to parse behaviorRules, exporting as raw. agentId={}",
                        entity.getId());
                brCorrupt = true;
                brRawCorruptValue = brJson;
            }
        }
        if (brCorrupt) {
            m.put("behaviorRulesRaw", brRawCorruptValue);
        }

        // lifecycleHooks — same corrupt-defense pattern
        String lhJson = entity.getLifecycleHooks();
        boolean lhCorrupt = false;
        String lhRawCorruptValue = null;
        if (lhJson != null && !lhJson.isBlank()) {
            try {
                JsonNode lhNode = JSON.readTree(lhJson);
                if (lhNode != null && lhNode.isObject()) {
                    m.put("lifecycleHooks", JSON.convertValue(lhNode, Map.class));
                } else {
                    lhCorrupt = true;
                    lhRawCorruptValue = lhJson;
                }
            } catch (Exception e) {
                log.warn("AgentYamlMapper: failed to parse lifecycleHooks, exporting as raw. agentId={}",
                        entity.getId());
                lhCorrupt = true;
                lhRawCorruptValue = lhJson;
            }
        }
        if (lhCorrupt) {
            m.put("lifecycleHooksRaw", lhRawCorruptValue);
        }

        if (entity.getConfig() != null && !entity.getConfig().isBlank()) {
            try {
                Map<String, Object> cfg = JSON.readValue(entity.getConfig(),
                        new TypeReference<Map<String, Object>>() {});
                if (!cfg.isEmpty()) m.put("config", cfg);
            } catch (Exception ignored) {}
        }

        try {
            return YAML.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize agent to YAML", e);
        }
    }
}
