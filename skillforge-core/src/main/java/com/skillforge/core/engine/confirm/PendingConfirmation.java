package com.skillforge.core.engine.confirm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory record for a pending install-confirmation. Held by
 * {@link PendingConfirmationRegistry}; held references by both the main engine thread
 * (blocking on {@link #latch}) and callback threads (Tomcat / Feishu WS) that call
 * {@link PendingConfirmationRegistry#complete} to wake the engine.
 *
 * <p>Not persisted. Process restart contract: see
 * {@code PendingConfirmationStartupRecovery} — orphan tool_use ids are repaired with
 * fabricated error tool_result rows.
 */
public final class PendingConfirmation {

    private final String confirmationId;
    private final String sessionId;
    private final String toolUseId;
    private final String installTool;
    private final String installTarget;
    private final String commandPreview;
    /** Feishu only: open_id of the user whose message triggered this turn (multi-user chat auth). */
    private final String triggererOpenId;
    private final long timeoutSeconds;

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<Decision> decision = new AtomicReference<>(null);
    /** Feishu only: open_id of the user who actually clicked the button. Informational. */
    private final AtomicReference<String> responderOpenId = new AtomicReference<>(null);

    public PendingConfirmation(String confirmationId, String sessionId, String toolUseId,
                               String installTool, String installTarget,
                               String commandPreview, String triggererOpenId,
                               long timeoutSeconds) {
        this.confirmationId = confirmationId;
        this.sessionId = sessionId;
        this.toolUseId = toolUseId;
        this.installTool = installTool;
        this.installTarget = installTarget;
        this.commandPreview = commandPreview;
        this.triggererOpenId = triggererOpenId;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String confirmationId() { return confirmationId; }
    public String sessionId() { return sessionId; }
    public String toolUseId() { return toolUseId; }
    public String installTool() { return installTool; }
    public String installTarget() { return installTarget; }
    public String commandPreview() { return commandPreview; }
    public String triggererOpenId() { return triggererOpenId; }
    public long timeoutSeconds() { return timeoutSeconds; }

    CountDownLatch latch() { return latch; }
    AtomicReference<Decision> decisionRef() { return decision; }
    AtomicReference<String> responderOpenIdRef() { return responderOpenId; }
}
