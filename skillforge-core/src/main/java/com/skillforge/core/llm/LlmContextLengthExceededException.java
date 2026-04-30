package com.skillforge.core.llm;

/**
 * Thrown when an LLM provider rejects a request because the prompt exceeds the model's
 * context window. Distinguished from generic API errors so {@code AgentLoopEngine} can
 * trigger a {@code compactFull} + retry instead of failing the conversation.
 *
 * <p>Recognised provider signals:
 * <ul>
 *   <li><b>Claude (Anthropic):</b> {@code error.type == "invalid_request_error"} and
 *       message contains "prompt is too long"</li>
 *   <li><b>OpenAI-compatible:</b> {@code error.code == "context_length_exceeded"} or
 *       message contains "context length"</li>
 * </ul>
 *
 * <p>Other API errors propagate as plain {@link RuntimeException}, preserving existing
 * behaviour.
 *
 * @since CTX-1
 */
public class LlmContextLengthExceededException extends RuntimeException {

    public LlmContextLengthExceededException(String message) {
        super(message);
    }

    public LlmContextLengthExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
