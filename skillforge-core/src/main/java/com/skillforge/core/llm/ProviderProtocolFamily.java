package com.skillforge.core.llm;

/**
 * Protocol-family classification for OpenAI-compatible providers.
 *
 * <p>The same OpenAI Chat Completions wire shape has subtle per-provider dialects for
 * thinking-mode control and {@code reasoning_content} replay semantics. Rather than
 * subclassing {@link OpenAiProvider}, we classify the model into a family and drive
 * request-body construction + message conversion from capability flags.</p>
 *
 * <p>Capability flags are based on live curl evidence (plan §10 Test 1/2/3).</p>
 */
public enum ProviderProtocolFamily {

    /**
     * DashScope-hosted Qwen models ({@code qwen*}). Top-level {@code enable_thinking}
     * toggle. {@code reasoning_content} replay on tool_use messages is <em>tolerated</em>
     * when omitted (empty, omitted, placeholder all return 200 OK; plan §10 Test 3);
     * we still emit {@code ""} as fallback for symmetry with DEEPSEEK_V4.
     */
    QWEN_DASHSCOPE(
            /* supportsThinkingToggle */          true,
            /* requiresReasoningContentReplay */  true,
            /* dropsReasoningContentOnReplay */   false,
            /* thinkingFieldDialect */            ThinkingFieldDialect.QWEN_ENABLE_THINKING,
            /* supportsReasoningEffort */         false),

    /**
     * DeepSeek v4 models ({@code deepseek-v4-*}). Top-level {@code thinking: {type: ...}}
     * + top-level {@code reasoning_effort}. Replay of tool_use with omitted
     * {@code reasoning_content} returns HTTP 400 "must be passed back" (plan §10
     * Test 3 + Step 0 re-verification).
     */
    DEEPSEEK_V4(
            true,
            true,
            false,
            ThinkingFieldDialect.DEEPSEEK_V4_THINKING,
            true),

    /**
     * DeepSeek R1-style reasoner legacy ({@code deepseek-reasoner}). No tool-calls support;
     * per DeepSeek docs, {@code reasoning_content} should NOT be replayed on the next turn.
     * Not in SkillForge's default model list (see FALLBACK_MODEL_OPTIONS); kept for
     * future-proofing. Doc-based, not live-tested (plan §4.3 / reviewer W2).
     */
    DEEPSEEK_REASONER_LEGACY(
            false,
            false,
            true,
            ThinkingFieldDialect.NONE,
            false),

    /**
     * DeepSeek v3-era chat/coder ({@code deepseek-chat}, {@code deepseek-coder}).
     * No thinking, no reasoning_effort.
     */
    DEEPSEEK_CHAT_LEGACY(
            false,
            false,
            false,
            ThinkingFieldDialect.NONE,
            false),

    /**
     * OpenAI reasoning models (o1*, o3*, o4*). Top-level {@code reasoning_effort}
     * (OpenAI standard). Thinking on/off is not user-toggleable.
     */
    OPENAI_REASONING(
            false,
            false,
            false,
            ThinkingFieldDialect.NONE,
            true),

    /**
     * Generic OpenAI-compatible (gpt-4o, llama, mistral, vllm, ollama, bailian:glm-5, …).
     * No thinking, no reasoning_effort.
     */
    GENERIC_OPENAI(
            false,
            false,
            false,
            ThinkingFieldDialect.NONE,
            false),

    /**
     * Anthropic native — handled by {@link ClaudeProvider}, not OpenAiProvider. Listed for
     * completeness of the resolver's return domain; OpenAiProvider dispatch will never see
     * this value in practice.
     */
    CLAUDE(
            false,
            false,
            false,
            ThinkingFieldDialect.NONE,
            false);

    /** Whether this family supports toggling thinking on/off in the request body. */
    public final boolean supportsThinkingToggle;
    /** Whether API rejects missing {@code reasoning_content} when assistant has tool_calls. */
    public final boolean requiresReasoningContentReplay;
    /** Whether API rejects {@code reasoning_content} on replay (must be dropped). */
    public final boolean dropsReasoningContentOnReplay;
    /** Which body-level thinking field dialect to emit (if any). */
    public final ThinkingFieldDialect thinkingFieldDialect;
    /** Whether family accepts top-level {@code reasoning_effort}. */
    public final boolean supportsReasoningEffort;

    ProviderProtocolFamily(boolean supportsThinkingToggle,
                           boolean requiresReasoningContentReplay,
                           boolean dropsReasoningContentOnReplay,
                           ThinkingFieldDialect dialect,
                           boolean supportsReasoningEffort) {
        this.supportsThinkingToggle = supportsThinkingToggle;
        this.requiresReasoningContentReplay = requiresReasoningContentReplay;
        this.dropsReasoningContentOnReplay = dropsReasoningContentOnReplay;
        this.thinkingFieldDialect = dialect;
        this.supportsReasoningEffort = supportsReasoningEffort;
    }

    /** Thinking-field dialect emitted at the top level of the request body. */
    public enum ThinkingFieldDialect {
        /** Emit nothing. */
        NONE,
        /** Top-level {@code "enable_thinking": <bool>}. */
        QWEN_ENABLE_THINKING,
        /** Top-level {@code "thinking": {"type": "enabled"|"disabled"}}. */
        DEEPSEEK_V4_THINKING
    }
}
