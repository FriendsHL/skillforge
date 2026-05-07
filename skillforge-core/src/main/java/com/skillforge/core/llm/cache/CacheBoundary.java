package com.skillforge.core.llm.cache;

/**
 * Boundary marker for SystemPromptBuilder stable / dynamic split (PROMPT-CACHE-MVP §Q3).
 *
 * <p>The marker is embedded directly in the assembled {@code systemPrompt} string passed to
 * {@link com.skillforge.core.llm.LlmRequest#setSystemPrompt(String)} — providers that
 * support manual cache breakpoints (Anthropic) split on this marker; providers that do not
 * (OpenAI-compatible families) ignore it entirely (the marker is a benign HTML-style
 * comment and survives in the assistant-side prompt without impact).
 *
 * <p>The leading and trailing newlines are deliberate: callers concatenate
 * {@code stable + MARKER + dynamic} verbatim and rely on the marker to add its own
 * vertical space.
 */
public final class CacheBoundary {

    /** Marker literal — must be exactly this string to allow providers to split() reliably. */
    public static final String MARKER = "<!-- SKILLFORGE_CACHE_BOUNDARY -->";

    /** Marker wrapped with newlines for direct concatenation. */
    public static final String MARKER_WITH_NEWLINES = "\n" + MARKER + "\n";

    private CacheBoundary() {}
}
