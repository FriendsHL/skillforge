package com.skillforge.core.engine.confirm;

/**
 * Thrown by {@link ConfirmationPrompter} when no usable confirmation channel is available
 * (web broadcaster missing, feishu {@code encryptKey} unconfigured, card-send failed, etc.).
 *
 * <p>Caller ({@link com.skillforge.core.engine.AgentLoopEngine#handleInstallConfirmation
 * AgentLoopEngine.handleInstallConfirmation}) catches this and converts it to an
 * {@code isError=true} tool_result, preserving tool_use ↔ tool_result pairing.
 */
public class ChannelUnavailableException extends RuntimeException {
    public ChannelUnavailableException(String message) {
        super(message);
    }

    public ChannelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
