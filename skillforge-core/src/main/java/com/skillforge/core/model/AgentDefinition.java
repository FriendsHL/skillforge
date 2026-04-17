package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 定义，包含模型、提示词、关联的 Skill 等配置。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentDefinition {

    private String id;
    private String name;
    private String description;

    @JsonProperty("model_id")
    private String modelId;

    @JsonProperty("system_prompt")
    private String systemPrompt;

    @JsonProperty("skill_ids")
    private List<String> skillIds = new ArrayList<>();

    private Map<String, Object> config = new HashMap<>();

    @JsonProperty("soul_prompt")
    private String soulPrompt;

    @JsonProperty("tools_prompt")
    private String toolsPrompt;

    @JsonProperty("behavior_rules")
    private BehaviorRulesConfig behaviorRules;

    /** Resolved prompt texts from builtin rules, populated by server layer. Not serialized. */
    @JsonIgnore
    private List<String> resolvedBehaviorRules = new ArrayList<>();

    /**
     * Lifecycle hook configuration. Parsed from {@code t_agent.lifecycle_hooks} JSON column
     * by {@code AgentService.toAgentDefinition}. The dispatcher consumes it directly.
     * Not re-serialized (AgentDefinition is not the wire schema for lifecycle_hooks) — keep
     * {@code @JsonIgnore} so the JSON payload stays stable.
     */
    @JsonIgnore
    private LifecycleHooksConfig lifecycleHooks;

    /**
     * Structured behavior rules config parsed from the JSON storage column.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BehaviorRulesConfig {
        private List<String> builtinRuleIds = new ArrayList<>();
        private List<String> customRules = new ArrayList<>();

        public BehaviorRulesConfig() {}

        public List<String> getBuiltinRuleIds() { return builtinRuleIds; }
        public void setBuiltinRuleIds(List<String> builtinRuleIds) {
            this.builtinRuleIds = builtinRuleIds != null ? builtinRuleIds : new ArrayList<>();
        }
        public List<String> getCustomRules() { return customRules; }
        public void setCustomRules(List<String> customRules) {
            this.customRules = customRules != null ? customRules : new ArrayList<>();
        }
    }

    public AgentDefinition() {
    }

    /**
     * 获取温度参数，默认 0.7。
     */
    public double getTemperature() {
        Object val = config.get("temperature");
        if (val instanceof Number num) {
            return num.doubleValue();
        }
        return 0.7;
    }

    /**
     * 获取最大上下文 token 数，默认 100000。
     * 用于上下文压缩时判断是否需要压缩。
     */
    public int getMaxContextTokens() {
        Object val = config.get("max_context_tokens");
        if (val instanceof Number num) {
            return num.intValue();
        }
        return 100000;
    }

    /**
     * 获取最大 token 数，默认 4096。
     */
    public int getMaxTokens() {
        Object val = config.get("max_tokens");
        if (val instanceof Number num) {
            return num.intValue();
        }
        return 4096;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<String> getSkillIds() {
        return skillIds;
    }

    public void setSkillIds(List<String> skillIds) {
        this.skillIds = skillIds;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public String getSoulPrompt() {
        return soulPrompt;
    }

    public void setSoulPrompt(String soulPrompt) {
        this.soulPrompt = soulPrompt;
    }

    public String getToolsPrompt() {
        return toolsPrompt;
    }

    public void setToolsPrompt(String toolsPrompt) {
        this.toolsPrompt = toolsPrompt;
    }

    public BehaviorRulesConfig getBehaviorRules() {
        return behaviorRules;
    }

    public void setBehaviorRules(BehaviorRulesConfig behaviorRules) {
        this.behaviorRules = behaviorRules;
    }

    public List<String> getResolvedBehaviorRules() {
        return resolvedBehaviorRules;
    }

    public void setResolvedBehaviorRules(List<String> resolvedBehaviorRules) {
        this.resolvedBehaviorRules = resolvedBehaviorRules != null ? resolvedBehaviorRules : new ArrayList<>();
    }

    public LifecycleHooksConfig getLifecycleHooks() {
        return lifecycleHooks;
    }

    public void setLifecycleHooks(LifecycleHooksConfig lifecycleHooks) {
        this.lifecycleHooks = lifecycleHooks;
    }
}
