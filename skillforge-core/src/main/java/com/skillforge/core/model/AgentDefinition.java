package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
}
