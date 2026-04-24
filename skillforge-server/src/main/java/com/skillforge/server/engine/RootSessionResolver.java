package com.skillforge.server.engine;

import com.skillforge.core.engine.confirm.RootSessionLookup;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks {@link SessionEntity#getParentSessionId()} upward to find the top-most ancestor
 * (the root session). Hard cap at depth 10 to defend against accidental cycles / bad data.
 *
 * <p>W10 conservative fallback: on any failure (session missing, loop detected, depth limit
 * reached) return the <b>original input</b> sessionId, not the partially-walked current.
 * This keeps cache keys stable under data anomalies and avoids contaminating sibling trees.
 */
@Component
public class RootSessionResolver implements RootSessionLookup {

    private static final Logger log = LoggerFactory.getLogger(RootSessionResolver.class);
    private static final int MAX_DEPTH = 10;

    private final SessionService sessionService;

    public RootSessionResolver(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String resolveRoot(String sessionId) {
        if (sessionId == null) return null;
        String cur = sessionId;
        for (int i = 0; i < MAX_DEPTH; i++) {
            SessionEntity s;
            try {
                s = sessionService.getSession(cur);
            } catch (RuntimeException ex) {
                log.warn("RootSessionResolver: getSession({}) failed at depth {}, falling back to input: {}",
                        cur, i, ex.getMessage());
                return sessionId;
            }
            String parent = s.getParentSessionId();
            if (parent == null || parent.isBlank()) {
                return cur;
            }
            if (parent.equals(cur)) {
                log.error("RootSessionResolver: parent==self loop at sid={}, falling back to input", cur);
                return sessionId;
            }
            cur = parent;
        }
        log.warn("RootSessionResolver: depth >{} reached starting at sid={}, falling back to input (data anomaly)",
                MAX_DEPTH, sessionId);
        return sessionId;
    }
}
