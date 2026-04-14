package com.skillforge.core.context;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;

import java.util.List;
import java.util.Map;

/**
 * 系统提示词构建器，将 Agent 定义、Skill 列表和上下文整合为完整的 system prompt。
 */
public class SystemPromptBuilder {

    private final AgentDefinition agentDefinition;
    private final List<SkillDefinition> skillDefinitions;
    private final List<ContextProvider> contextProviders;

    public SystemPromptBuilder(AgentDefinition agentDefinition,
                               List<SkillDefinition> skillDefinitions,
                               List<ContextProvider> contextProviders) {
        this.agentDefinition = agentDefinition;
        this.skillDefinitions = skillDefinitions;
        this.contextProviders = contextProviders;
    }

    /**
     * 构建完整的系统提示词。
     * 拼接顺序：CLAUDE.md → AGENT.md → SOUL.md → TOOLS.md/默认Guidelines → Skills → Context
     */
    public String build() {
        return build(null);
    }

    /**
     * 构建完整的系统提示词，支持注入全局 CLAUDE.md 内容。
     *
     * @param claudeMd 全局 CLAUDE.md 内容（可为 null）
     */
    public String build(String claudeMd) {
        StringBuilder sb = new StringBuilder();

        // 1. CLAUDE.md — 全局规则
        if (claudeMd != null && !claudeMd.isBlank()) {
            sb.append(claudeMd.strip()).append("\n\n");
        }

        // 2. AGENT.md — 核心指令（现有 systemPrompt）
        String agentPrompt = agentDefinition.getSystemPrompt();
        if (agentPrompt != null && !agentPrompt.isBlank()) {
            sb.append(agentPrompt.strip()).append("\n\n");
        }

        // 3. SOUL.md — 人格/语气
        String soulPrompt = agentDefinition.getSoulPrompt();
        if (soulPrompt != null && !soulPrompt.isBlank()) {
            sb.append(soulPrompt.strip()).append("\n\n");
        }

        // 4. TOOLS.md — 工具经验（有则替代默认 Guidelines）
        String toolsPrompt = agentDefinition.getToolsPrompt();
        if (toolsPrompt != null && !toolsPrompt.isBlank()) {
            sb.append(toolsPrompt.strip()).append("\n\n");
        } else {
            sb.append("## Tool Usage Guidelines\n\n");
            sb.append("- Use FileRead instead of running `cat` or `head` via Bash\n");
            sb.append("- Use Glob instead of running `find` or `ls` via Bash\n");
            sb.append("- Use Grep instead of running `grep` or `rg` via Bash\n");
            sb.append("- Use FileEdit for modifying existing files instead of FileWrite\n");
            sb.append("- Always read a file before editing or overwriting it\n");
            sb.append("- Use absolute file paths whenever possible\n");
            sb.append("\n");
        }

        // 3. Available skills
        if (skillDefinitions != null && !skillDefinitions.isEmpty()) {
            sb.append("## Available Skills\n\n");
            for (SkillDefinition skill : skillDefinitions) {
                sb.append("- **").append(skill.getName()).append("**: ").append(skill.getDescription()).append("\n");
            }
            sb.append("\n");
        }

        // 3. Context from providers
        if (contextProviders != null && !contextProviders.isEmpty()) {
            sb.append("## Context\n\n");
            for (ContextProvider provider : contextProviders) {
                Map<String, String> context = provider.getContext();
                if (context != null && !context.isEmpty()) {
                    sb.append("### ").append(provider.getName()).append("\n");
                    for (Map.Entry<String, String> entry : context.entrySet()) {
                        sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                    }
                    sb.append("\n");
                }
            }
        }

        return sb.toString().stripTrailing();
    }
}
