package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 元数据定义，支持两种来源：
 * 1. SKILL.md 的 YAML frontmatter（Claude Code 标准格式）
 * 2. 独立的 skill.yaml 文件（向后兼容）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillDefinition {

    private String id;
    private String name;
    private String description;

    @JsonProperty("argument-hint")
    private String argumentHint;

    @JsonProperty("disable-model-invocation")
    private boolean disableModelInvocation;

    @JsonProperty("user-invocable")
    private Boolean userInvocable;

    @JsonProperty("allowed-tools")
    private List<String> allowedTools;

    private String model;
    private String effort;
    private String context;
    private String agent;
    private String paths;
    private String shell;

    @JsonProperty("skill_path")
    private String skillPath;

    private List<String> triggers = new ArrayList<>();

    @JsonProperty("required_tools")
    private List<String> requiredTools = new ArrayList<>();

    /** SKILL.md 的正文内容（去掉 frontmatter 后的部分） */
    @JsonProperty("prompt_content")
    private String promptContent;

    /** 辅助文件内容: filename -> content (reference.md, examples.md 等) */
    private Map<String, String> references = new HashMap<>();

    /** scripts 目录下的脚本文件路径列表 */
    private List<String> scriptPaths = new ArrayList<>();

    @JsonProperty("owner_id")
    private String ownerId;

    @JsonProperty("is_public")
    private boolean isPublic;

    /** 系统级 Skill（随项目发布，不可删除） vs 用户级（可管理） */
    private boolean system;

    public SkillDefinition() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getArgumentHint() { return argumentHint; }
    public void setArgumentHint(String argumentHint) { this.argumentHint = argumentHint; }

    public boolean isDisableModelInvocation() { return disableModelInvocation; }
    public void setDisableModelInvocation(boolean disableModelInvocation) { this.disableModelInvocation = disableModelInvocation; }

    public Boolean getUserInvocable() { return userInvocable; }
    public void setUserInvocable(Boolean userInvocable) { this.userInvocable = userInvocable; }

    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }

    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public String getPaths() { return paths; }
    public void setPaths(String paths) { this.paths = paths; }

    public String getShell() { return shell; }
    public void setShell(String shell) { this.shell = shell; }

    public String getSkillPath() { return skillPath; }
    public void setSkillPath(String skillPath) { this.skillPath = skillPath; }

    public List<String> getTriggers() { return triggers; }
    public void setTriggers(List<String> triggers) { this.triggers = triggers; }

    public List<String> getRequiredTools() { return requiredTools; }
    public void setRequiredTools(List<String> requiredTools) { this.requiredTools = requiredTools; }

    @JsonSetter("tools")
    public void setTools(List<String> tools) { this.requiredTools = tools; }

    public String getPromptContent() { return promptContent; }
    public void setPromptContent(String promptContent) { this.promptContent = promptContent; }

    public Map<String, String> getReferences() { return references; }
    public void setReferences(Map<String, String> references) { this.references = references; }

    public List<String> getScriptPaths() { return scriptPaths; }
    public void setScriptPaths(List<String> scriptPaths) { this.scriptPaths = scriptPaths; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public boolean isSystem() { return system; }
    public void setSystem(boolean system) { this.system = system; }

    /**
     * 对 promptContent 进行参数替换。
     * 支持 $ARGUMENTS, $0, $1, ... 和 ${CLAUDE_SKILL_DIR}
     */
    public String renderPrompt(String arguments) {
        if (promptContent == null) return "";
        String result = promptContent;

        // 替换 ${CLAUDE_SKILL_DIR}
        if (skillPath != null) {
            result = result.replace("${CLAUDE_SKILL_DIR}", skillPath);
        }

        // 替换 $ARGUMENTS
        if (arguments != null) {
            result = result.replace("$ARGUMENTS", arguments);
            // 替换 $0, $1, $2 ...
            String[] args = arguments.split("\\s+");
            for (int i = 0; i < args.length; i++) {
                result = result.replace("$" + i, args[i]);
            }
        }

        return result;
    }
}
