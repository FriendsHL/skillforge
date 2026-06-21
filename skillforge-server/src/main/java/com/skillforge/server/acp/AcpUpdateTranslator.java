package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Translates an ACP {@code session/update} payload's {@code update} object into a
 * typed {@link AcpUpdate}.
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1. This is a CLEAN PROTOCOL LAYER — it intentionally
 * does NOT convert to SkillForge {@code Message}/{@code ContentBlock} (that is
 * P1a-2).
 *
 * <p><b>Interface, not a concrete class (D-W1):</b> the ACP {@code session/update}
 * dialect differs per adapter. {@link CcAcpUpdateTranslator} handles the cc
 * adapter; P3 will add a {@code CodexAcpUpdateTranslator} for the codex dialect.
 * {@link AcpClient} depends on this interface so adding a dialect never changes
 * the client's constructor contract.
 *
 * <p><b>Contract:</b> implementations MUST NOT throw — anything they cannot
 * confidently type must be returned as {@link AcpUpdate.Unknown} so the client's
 * inbound loop stays crash-proof against protocol drift.
 */
public interface AcpUpdateTranslator {

    /**
     * Translate the {@code update} node (the value of {@code params.update} in a
     * {@code session/update} notification).
     *
     * @param update the {@code update} object; if {@code null}/non-object it must
     *               be routed to {@link AcpUpdate.Unknown}
     * @return a typed update; never {@code null}, never throws
     */
    AcpUpdate translate(JsonNode update);
}
