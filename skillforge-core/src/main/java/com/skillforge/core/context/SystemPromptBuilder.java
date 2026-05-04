package com.skillforge.core.context;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;

import java.util.ArrayList;
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
    private final List<ContextProvider> contextProviders;

    public SystemPromptBuilder(AgentDefinition agentDefinition,
                               List<SkillDefinition> skillDefinitions,
                               List<ContextProvider> contextProviders) {
        this.agentDefinition = agentDefinition;
        this.contextProviders = contextProviders;
    }

    /**
     * 构建完整的系统提示词。
     * 拼接顺序：CLAUDE.md → AGENT.md → SOUL.md → TOOLS.md/默认Guidelines → Context
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
            sb.append("- Use Read instead of running `cat` or `head` via Bash\n");
            sb.append("- Use Glob instead of running `find` or `ls` via Bash\n");
            sb.append("- Use Grep instead of running `grep` or `rg` via Bash\n");
            sb.append("- Use Edit for modifying existing files instead of Write\n");
            sb.append("- Always read a file before editing or overwriting it\n");
            sb.append("- Use absolute file paths whenever possible\n");
            sb.append("\n");
        }

        // 5. Behavior Rules — before Context (recency bias)
        appendBehaviorRules(sb);

        // 6. Context from providers
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
        List<AgentDefinition.BehaviorRulesConfig.CustomRule> customRules =
                config != null ? config.getCustomRules() : null;
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
            appendCustomRuleGroup(sb, "MUST", customRules,
                    AgentDefinition.BehaviorRulesConfig.Severity.MUST);
            appendCustomRuleGroup(sb, "SHOULD", customRules,
                    AgentDefinition.BehaviorRulesConfig.Severity.SHOULD);
            appendCustomRuleGroup(sb, "MAY", customRules,
                    AgentDefinition.BehaviorRulesConfig.Severity.MAY);
            sb.append("</user-configured-guidelines>\n\n");
        }
    }

    private static void appendCustomRuleGroup(
            StringBuilder sb,
            String label,
            List<AgentDefinition.BehaviorRulesConfig.CustomRule> customRules,
            AgentDefinition.BehaviorRulesConfig.Severity severity) {
        List<String> sanitizedRules = new ArrayList<>();
        for (AgentDefinition.BehaviorRulesConfig.CustomRule rule : customRules) {
            if (rule == null || rule.getSeverity() != severity) {
                continue;
            }
            String sanitized = sanitizeCustomRule(rule.getText());
            if (!sanitized.isBlank()) {
                sanitizedRules.add(sanitized);
            }
        }
        if (sanitizedRules.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        for (String rule : sanitizedRules) {
            sb.append("- ").append(rule).append("\n");
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
