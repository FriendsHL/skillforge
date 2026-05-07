package com.skillforge.core.context;

import com.skillforge.core.llm.cache.SystemPromptParts;
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
        SystemPromptParts parts = buildWithBoundary(claudeMd);
        // Backward compat: legacy callers want a single string. Concatenate with a newline
        // separator (no marker) — providers that don't honor cache_control see no change.
        if (parts.dynamic().isEmpty()) {
            return parts.stable();
        }
        if (parts.stable().isEmpty()) {
            return parts.dynamic();
        }
        return parts.stable() + "\n\n" + parts.dynamic();
    }

    /**
     * PROMPT-CACHE-MVP §Q3 — split the system prompt into a stable prefix and a dynamic
     * suffix so prompt-cache aware providers (Anthropic) can attach a {@code cache_control}
     * breakpoint at the boundary.
     *
     * <p>Stable section (cache-eligible):
     * <ol>
     *   <li>Global CLAUDE.md</li>
     *   <li>Agent system prompt</li>
     *   <li>Agent soul prompt</li>
     *   <li>Tool usage guidelines (per-agent override or default)</li>
     *   <li>Behavior rules (builtin + custom, sandboxed)</li>
     * </ol>
     *
     * <p>Dynamic section (cache-broken on every call):
     * <ul>
     *   <li>{@code Context} block from {@link ContextProvider}s — current_date and other
     *       runtime values that change across days / sessions.</li>
     * </ul>
     *
     * <p>Callers that need to inject further dynamic content (Session Context, User
     * Memories, loop-ending reminders) should append to the dynamic part themselves; the
     * agent loop assembles {@code stable + BOUNDARY + dynamic} into the final
     * {@link com.skillforge.core.llm.LlmRequest#getSystemPrompt() systemPrompt}.
     */
    public SystemPromptParts buildWithBoundary(String claudeMd) {
        StringBuilder stable = new StringBuilder();

        // 1. CLAUDE.md — 全局规则
        if (claudeMd != null && !claudeMd.isBlank()) {
            stable.append(claudeMd.strip()).append("\n\n");
        }

        // 2. AGENT.md — 核心指令
        String agentPrompt = agentDefinition.getSystemPrompt();
        if (agentPrompt != null && !agentPrompt.isBlank()) {
            stable.append(agentPrompt.strip()).append("\n\n");
        }

        // 3. SOUL.md — 人格/语气
        String soulPrompt = agentDefinition.getSoulPrompt();
        if (soulPrompt != null && !soulPrompt.isBlank()) {
            stable.append(soulPrompt.strip()).append("\n\n");
        }

        // 4. TOOLS.md — 工具经验（有则替代默认 Guidelines）
        String toolsPrompt = agentDefinition.getToolsPrompt();
        if (toolsPrompt != null && !toolsPrompt.isBlank()) {
            stable.append(toolsPrompt.strip()).append("\n\n");
        } else {
            stable.append("## Tool Usage Guidelines\n\n");
            stable.append("- Use Read instead of running `cat` or `head` via Bash\n");
            stable.append("- Use Glob instead of running `find` or `ls` via Bash\n");
            stable.append("- Use Grep instead of running `grep` or `rg` via Bash\n");
            stable.append("- Use Edit for modifying existing files instead of Write\n");
            stable.append("- Always read a file before editing or overwriting it\n");
            stable.append("- Use absolute file paths whenever possible\n");
            stable.append("\n");
        }

        // 5. Behavior Rules — before Context (recency bias)
        appendBehaviorRules(stable);

        // 6. Context from providers — DYNAMIC (current_date / live env)
        StringBuilder dynamic = new StringBuilder();
        if (contextProviders != null && !contextProviders.isEmpty()) {
            StringBuilder ctxSection = new StringBuilder();
            ctxSection.append("## Context\n\n");
            boolean any = false;
            for (ContextProvider provider : contextProviders) {
                Map<String, String> context = provider.getContext();
                if (context != null && !context.isEmpty()) {
                    ctxSection.append("### ").append(provider.getName()).append("\n");
                    for (Map.Entry<String, String> entry : context.entrySet()) {
                        ctxSection.append("- ").append(entry.getKey()).append(": ")
                                .append(entry.getValue()).append("\n");
                    }
                    ctxSection.append("\n");
                    any = true;
                }
            }
            if (any) {
                dynamic.append(ctxSection);
            }
        }

        return new SystemPromptParts(
                stable.toString().stripTrailing(),
                dynamic.toString().stripTrailing());
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
