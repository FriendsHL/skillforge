package com.skillforge.core.llm.cache;

/**
 * Two-section split of a system prompt for prompt-cache stability (PROMPT-CACHE-MVP).
 *
 * <p>{@code stable} carries everything that is invariant per agent definition (CLAUDE.md +
 * agent system prompt + soul prompt + tool guidelines + behavior rules); {@code dynamic}
 * carries per-call mutable bits (current date, session ids, user memories, runtime hints).
 * Providers that support manual cache breakpoints (Anthropic) tag the boundary between the
 * two sections so the {@code stable} prefix becomes a cache-eligible block.
 *
 * <p>Stability invariant (INV-1): for the same agent definition the {@code stable} string
 * MUST produce a SHA-256-equal byte sequence across calls. Anything that pollutes that
 * stability (Instant.now, userId, sessionId, request counters, …) MUST live in
 * {@code dynamic}.
 *
 * @param stable  cache-eligible prefix text (never null; may be empty)
 * @param dynamic per-call mutable suffix text (never null; may be empty)
 */
public record SystemPromptParts(String stable, String dynamic) {

    public SystemPromptParts {
        stable = stable == null ? "" : stable;
        dynamic = dynamic == null ? "" : dynamic;
    }
}
