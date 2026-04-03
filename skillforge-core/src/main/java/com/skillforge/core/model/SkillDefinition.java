package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 元数据定义，对应 skill.yaml 中的配置。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillDefinition {

    private String id;
    private String name;
    private String description;

    @JsonProperty("skill_path")
    private String skillPath;

    private List<String> triggers = new ArrayList<>();

    @JsonProperty("required_tools")
    private List<String> requiredTools = new ArrayList<>();

    @JsonProperty("prompt_content")
    private String promptContent;

    @JsonProperty("owner_id")
    private String ownerId;

    @JsonProperty("is_public")
    private boolean isPublic;

    public SkillDefinition() {
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

    public String getSkillPath() {
        return skillPath;
    }

    public void setSkillPath(String skillPath) {
        this.skillPath = skillPath;
    }

    public List<String> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<String> triggers) {
        this.triggers = triggers;
    }

    public List<String> getRequiredTools() {
        return requiredTools;
    }

    public void setRequiredTools(List<String> requiredTools) {
        this.requiredTools = requiredTools;
    }

    /**
     * 兼容 skill.yaml 中的 "tools" 字段，映射到 requiredTools。
     */
    @JsonSetter("tools")
    public void setTools(List<String> tools) {
        this.requiredTools = tools;
    }

    public String getPromptContent() {
        return promptContent;
    }

    public void setPromptContent(String promptContent) {
        this.promptContent = promptContent;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }
}
