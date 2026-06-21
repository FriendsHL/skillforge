package com.skillforge.server.acp;

/**
 * A delivered {@code session/update} notification: the session it belongs to plus
 * the typed {@link AcpUpdate}.
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1.
 *
 * @param sessionId the {@code params.sessionId} (may be {@code null} if absent)
 * @param update    the translated, typed update; never {@code null}
 */
public record AcpSessionUpdate(String sessionId, AcpUpdate update) {
}
