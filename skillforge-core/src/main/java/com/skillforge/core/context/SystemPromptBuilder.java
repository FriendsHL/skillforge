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
     * 由三部分组成：Agent 的 systemPrompt、可用 Skill 列表、动态上下文。
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // 1. Agent system prompt
        if (agentDefinition.getSystemPrompt() != null && !agentDefinition.getSystemPrompt().isBlank()) {
            sb.append(agentDefinition.getSystemPrompt());
            sb.append("\n\n");
        }

        // 2. Available skills
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
