package com.skillforge.core.engine.confirm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks pending install-confirmation latches across engine main-loop threads
 * and user-callback (HTTP / Feishu WS) threads.
 *
 * <p>Mirrors {@link com.skillforge.core.engine.PendingAskRegistry} but keyed by
 * {@code confirmationId} (UUID) with a secondary index by sessionId for
 * {@link #completeAllForSession} (cancel cascade).
 */
public class PendingConfirmationRegistry {

    private final Map<String, PendingConfirmation> byId = new ConcurrentHashMap<>();

    public PendingConfirmation register(PendingConfirmation pc) {
        if (pc == null || pc.confirmationId() == null) {
            throw new IllegalArgumentException("pending confirmation must have a confirmationId");
        }
        byId.put(pc.confirmationId(), pc);
        return pc;
    }

    /**
     * Block the calling thread waiting for a decision.
     *
     * @return the Decision, or {@link Decision#TIMEOUT} if the latch never fires.
     *         Returns {@code null} only when {@code confirmationId} is unknown (caller bug).
     */
    public Decision await(String confirmationId, long timeoutSeconds) throws InterruptedException {
        PendingConfirmation pc = byId.get(confirmationId);
        if (pc == null) {
            return null;
        }
        boolean ok = pc.latch().await(timeoutSeconds, TimeUnit.SECONDS);
        if (!ok) {
            pc.decisionRef().compareAndSet(null, Decision.TIMEOUT);
        }
        Decision d = pc.decisionRef().get();
        return d != null ? d : Decision.TIMEOUT;
    }

    /**
     * Wake the latch with a decision. No-op when the confirmation is unknown
     * (already completed / never existed).
     *
     * @return true if the call actually transitioned the pending record.
     */
    public boolean complete(String confirmationId, Decision decision, String responderOpenId) {
        if (decision == null) return false;
        PendingConfirmation pc = byId.get(confirmationId);
        if (pc == null) return false;
        if (!pc.decisionRef().compareAndSet(null, decision)) {
            return false;
        }
        if (responderOpenId != null) {
            pc.responderOpenIdRef().set(responderOpenId);
        }
        pc.latch().countDown();
        return true;
    }

    /**
     * Cancel cascade: wake every pending confirmation belonging to the given session
     * with the specified decision (typically {@link Decision#DENIED}).
     */
    public int completeAllForSession(String sessionId, Decision decision) {
        if (sessionId == null || decision == null) return 0;
        int n = 0;
        for (PendingConfirmation pc : byId.values()) {
            if (sessionId.equals(pc.sessionId())
                    && pc.decisionRef().compareAndSet(null, decision)) {
                pc.latch().countDown();
                n++;
            }
        }
        return n;
    }

    public PendingConfirmation peek(String confirmationId) {
        return byId.get(confirmationId);
    }

    public void removeIfPresent(String confirmationId) {
        if (confirmationId == null) return;
        byId.remove(confirmationId);
    }

    public boolean exists(String confirmationId) {
        return confirmationId != null && byId.containsKey(confirmationId);
    }

    /** Test-only: count of in-flight records. */
    public int size() {
        return byId.size();
    }
}
