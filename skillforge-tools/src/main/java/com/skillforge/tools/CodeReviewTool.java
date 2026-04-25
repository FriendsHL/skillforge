package com.skillforge.tools;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CodeReviewTool — run an LLM review over a snippet of code.
 *
 * <p>Intended to be invoked by Code Agent before registering a generated script as a hook
 * method. Produces a structured review covering correctness, safety and readability.
 */
public class CodeReviewTool implements Tool {

    private static final int MAX_CODE_CHARS = 32_768;
    private static final int MAX_TOKENS = 2048;

    private static final String SYSTEM_PROMPT = """
            You are a senior engineer performing a focused code review.
            Return your review in four sections, each with concrete findings:

            1. Correctness — does the code do what it claims? Edge cases, obvious bugs.
            2. Safety — dangerous shell/FS/network operations, injection, secrets exposure, unvalidated inputs.
            3. Readability — naming, structure, comments. Only call out real issues.
            4. Verdict — one of: APPROVE / REQUEST_CHANGES / REJECT. One-line rationale.

            Be terse. Do not echo the code. Do not hallucinate APIs that aren't shown.
            If the caller provides a `focus`, weight your analysis toward that concern.
            """;

    private final LlmProviderFactory llmProviderFactory;
    private final String providerName;

    public CodeReviewTool(LlmProviderFactory llmProviderFactory, String providerName) {
        this.llmProviderFactory = llmProviderFactory;
        this.providerName = providerName;
    }

    @Override
    public String getName() {
        return "CodeReview";
    }

    @Override
    public String getDescription() {
        return "Ask an LLM to review a code snippet for correctness, safety and readability. "
                + "Use this BEFORE registering a generated script or compiled method. "
                + "Returns a structured review with a final verdict (APPROVE / REQUEST_CHANGES / REJECT).";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", Map.of(
                "type", "string",
                "description", "The code to review."
        ));
        properties.put("language", Map.of(
                "type", "string",
                "description", "Language of the code (bash, node, java, ...). Used to frame the review."
        ));
        properties.put("focus", Map.of(
                "type", "string",
                "description", "Optional: a specific concern to weight the review toward (e.g. 'input validation', 'timeout handling')."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("code", "language"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        String code = asString(input.get("code"));
        String language = asString(input.get("language"));
        String focus = asString(input.get("focus"));

        if (code == null || code.isBlank()) {
            return SkillResult.error("code is required");
        }
        if (language == null || language.isBlank()) {
            return SkillResult.error("language is required");
        }
        if (code.length() > MAX_CODE_CHARS) {
            return SkillResult.error("code too long (max " + MAX_CODE_CHARS + " chars)");
        }

        LlmProvider provider = resolveProvider();
        if (provider == null) {
            return SkillResult.error("no LLM provider configured for CodeReview");
        }

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(SYSTEM_PROMPT);
        request.setMaxTokens(MAX_TOKENS);
        request.setTemperature(0.2);
        request.setMessages(List.of(Message.user(buildPrompt(language, code, focus))));

        try {
            LlmResponse response = provider.chat(request);
            String content = response.getContent();
            if (content == null || content.isBlank()) {
                return SkillResult.error("LLM returned empty review");
            }
            return SkillResult.success(content);
        } catch (RuntimeException e) {
            return SkillResult.error("code_review_failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private LlmProvider resolveProvider() {
        if (providerName != null && !providerName.isBlank()) {
            LlmProvider p = llmProviderFactory.getProvider(providerName);
            if (p != null) return p;
        }
        // Fall back to any named provider (callers typically wire "claude")
        return llmProviderFactory.getProvider("claude");
    }

    private static String buildPrompt(String language, String code, String focus) {
        StringBuilder sb = new StringBuilder(code.length() + 256);
        sb.append("Review the following ").append(language.toLowerCase(Locale.ROOT)).append(" code.\n");
        if (focus != null && !focus.isBlank()) {
            sb.append("Focus: ").append(focus.strip()).append('\n');
        }
        sb.append("\n```").append(language.toLowerCase(Locale.ROOT)).append('\n');
        sb.append(code);
        if (!code.endsWith("\n")) sb.append('\n');
        sb.append("```\n");
        return sb.toString();
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
