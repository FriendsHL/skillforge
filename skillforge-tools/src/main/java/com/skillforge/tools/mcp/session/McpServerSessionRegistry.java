package com.skillforge.tools.mcp.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of {@link McpServerSession}s, keyed by server name.
 *
 * <p>Owned by {@code McpServerLifecycle} in the server module. Held as a separate
 * tools-module class so the adapter and tool-registrar layers can resolve sessions
 * without depending on the Spring lifecycle bean directly.
 */
public class McpServerSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpServerSessionRegistry.class);

    private final ConcurrentHashMap<String, McpServerSession> sessions = new ConcurrentHashMap<>();

    /** Register or replace a session. Caller is responsible for disconnecting any prior. */
    public void put(McpServerSession session) {
        McpServerSession prior = sessions.put(session.getServerName(), session);
        if (prior != null && prior != session) {
            log.warn("McpServerSessionRegistry: replacing existing session for '{}'; "
                    + "prior was not disconnected by caller", session.getServerName());
        }
    }

    public Optional<McpServerSession> get(String name) {
        return Optional.ofNullable(sessions.get(name));
    }

    /** Remove (without disconnecting). Returns the prior entry if any. */
    public Optional<McpServerSession> remove(String name) {
        return Optional.ofNullable(sessions.remove(name));
    }

    public Collection<McpServerSession> all() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public int size() { return sessions.size(); }
}
