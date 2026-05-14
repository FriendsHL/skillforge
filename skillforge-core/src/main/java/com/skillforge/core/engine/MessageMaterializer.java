package com.skillforge.core.engine;

import com.skillforge.core.model.Message;

import java.util.List;

/**
 * MULTIMODAL-MVP r2 (B2 fix): callback for engine-boundary message materialization.
 *
 * <p>The engine's persisted / in-memory message list keeps lightweight reference
 * blocks ({@code image_ref} / {@code pdf_ref}) so {@link Message} byte-shape
 * matches what {@code SessionService.updateSessionMessages} reads from the DB
 * (see {@code persistence-shape-invariant.md}). Just before handing the request
 * to {@code LlmProvider.chatStream}, the engine calls
 * {@link #expandForProvider(String, List)} to obtain a <em>copy</em> with the
 * reference blocks expanded into provider-bound forms (base64 image / extracted
 * PDF text). The engine's own messages list is never mutated.</p>
 *
 * <p>Implementations MUST:</p>
 * <ul>
 *   <li>Return a NEW list (do not modify the input).</li>
 *   <li>For each message containing a reference block, build a new Message
 *       with a new content list. Do NOT mutate any shared Message or
 *       ContentBlock instance.</li>
 *   <li>Be safe to call repeatedly within the same loop iteration — engine
 *       may call this once per LLM attempt (initial + max_tokens continuation
 *       + post-overflow retry).</li>
 *   <li>Be a no-op (return the same input reference) when no message contains
 *       reference blocks, to avoid allocating on the common text-only path.</li>
 * </ul>
 *
 * <p>The {@code sessionId} parameter scopes attachment lookups (an attachment
 * stored under one session must not materialize into another session's request).</p>
 */
public interface MessageMaterializer {

    /**
     * Returns a provider-ready copy of {@code messages} with attachment
     * reference blocks expanded inline. Implementations MUST return the input
     * reference unchanged when no expansion is required.
     *
     * @param sessionId scopes attachment lookups; non-null
     * @param messages  the engine's current message list; non-null, treated read-only
     * @return materialized copy when expansion happened, otherwise the original
     *         {@code messages} reference (caller may compare {@code result == messages}
     *         to detect "no work to do")
     */
    List<Message> expandForProvider(String sessionId, List<Message> messages);
}
