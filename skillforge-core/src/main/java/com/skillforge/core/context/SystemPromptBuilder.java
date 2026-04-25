package com.skillforge.core.context;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 系统提示词构建器，将 Agent 定义、Tool 列表和上下文整合为完整的 system prompt。
 */
public class SystemPromptBuilder {

    private static final int MAX_CUSTOM_RULE_LENGTH = 500;
    private static final Pattern DANGEROUS_TAGS = Pattern.compile(
            "<(?:system|assistant|user|tool_use|tool_result|function|instructions)[^>]*>",
            Pattern.CASE_INSENSITIVE);

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

        // 5. Available skills
        if (skillDefinitions != null && !skillDefinitions.isEmpty()) {
            sb.append("## Available Skills\n\n");
            for (SkillDefinition skill : skillDefinitions) {
                sb.append("- **").append(skill.getName()).append("**: ").append(skill.getDescription()).append("\n");
            }
            sb.append("\n");
        }

        // 6. Behavior Rules — after Available Skills, before Context (recency bias)
        appendBehaviorRules(sb);

        // 7. Context from providers
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

    /**
     * Append resolved builtin behavior rules and sandboxed custom rules to the prompt.
     */
    private void appendBehaviorRules(StringBuilder sb) {
        List<String> resolved = agentDefinition.getResolvedBehaviorRules();
        boolean hasBuiltin = resolved != null && !resolved.isEmpty();

        AgentDefinition.BehaviorRulesConfig config = agentDefinition.getBehaviorRules();
        List<String> customRules = config != null ? config.getCustomRules() : null;
        boolean hasCustom = customRules != null && !customRules.isEmpty();

        if (!hasBuiltin && !hasCustom) return;

        sb.append("## Behavior Rules\n\n");

        // Builtin rules — resolved prompt texts
        if (hasBuiltin) {
            sb.append("You MUST follow these behavioral guidelines:\n\n");
            for (int i = 0; i < resolved.size(); i++) {
                sb.append(i + 1).append(". ").append(resolved.get(i)).append("\n");
            }
            sb.append("\n");
        }

        // Custom rules — sandboxed with XML tag for prompt injection defense
        if (hasCustom) {
            sb.append("<user-configured-guidelines>\n");
            sb.append("The agent creator has configured the following custom behavior guidelines:\n");
            for (String rule : customRules) {
                String sanitized = sanitizeCustomRule(rule);
                if (!sanitized.isBlank()) {
                    sb.append("- ").append(sanitized).append("\n");
                }
            }
            sb.append("</user-configured-guidelines>\n\n");
        }
    }

    static String sanitizeCustomRule(String rule) {
        if (rule == null) return "";
        String cleaned = DANGEROUS_TAGS.matcher(rule).replaceAll("[filtered]");
        if (cleaned.length() > MAX_CUSTOM_RULE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_CUSTOM_RULE_LENGTH) + "...";
        }
        return cleaned.strip();
    }
}
