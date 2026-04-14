package com.skillforge.server.subagent;

import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory roster tracking handle → sessionId mappings per collabRunId.
 * Auto-evicts dead sessions on resolve().
 */
@Component
public class AgentRoster {

    private static final Logger log = LoggerFactory.getLogger(AgentRoster.class);

    // collabRunId → Map<handle, sessionId>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> roster = new ConcurrentHashMap<>();

    private final SessionRepository sessionRepository;
    private final CollabRunRepository collabRunRepository;

    public AgentRoster(SessionRepository sessionRepository, CollabRunRepository collabRunRepository) {
        this.sessionRepository = sessionRepository;
        this.collabRunRepository = collabRunRepository;
    }

    public void register(String collabRunId, String handle, String sessionId) {
        roster.computeIfAbsent(collabRunId, k -> new ConcurrentHashMap<>()).put(handle, sessionId);
        log.info("AgentRoster: registered handle={} sessionId={} in collab={}", handle, sessionId, collabRunId);
    }

    /**
     * Resolve handle to sessionId. Returns null and auto-unregisters if the session is completed/error.
     */
    public String resolve(String collabRunId, String handle) {
        ConcurrentHashMap<String, String> members = roster.get(collabRunId);
        if (members == null) return null;
        String sessionId = members.get(handle);
        if (sessionId == null) return null;

        // Check if session is still alive
        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || "error".equals(session.getRuntimeStatus())
                || (session.getCompletedAt() != null && !"running".equals(session.getRuntimeStatus()))) {
            // Auto-evict dead session
            members.remove(handle);
            log.info("AgentRoster: auto-evicted dead handle={} sessionId={} from collab={}", handle, sessionId, collabRunId);
            return null;
        }
        return sessionId;
    }

    public Map<String, String> listMembers(String collabRunId) {
        ConcurrentHashMap<String, String> members = roster.get(collabRunId);
        if (members == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(members);
    }

    /**
     * Reverse lookup: find which handle maps to the given sessionId in a collab run.
     * Returns null if not found.
     */
    public String resolveHandle(String collabRunId, String sessionId) {
        ConcurrentHashMap<String, String> members = roster.get(collabRunId);
        if (members == null) return null;
        for (Map.Entry<String, String> entry : members.entrySet()) {
            if (entry.getValue().equals(sessionId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void unregister(String collabRunId, String handle) {
        ConcurrentHashMap<String, String> members = roster.get(collabRunId);
        if (members != null) {
            members.remove(handle);
            log.info("AgentRoster: unregistered handle={} from collab={}", handle, collabRunId);
        }
    }

    /**
     * Rebuild roster from DB on startup. Only loads sessions from RUNNING collab runs
     * to avoid resurrecting members of already-cancelled/completed runs.
     */
    public void rebuildFromDb() {
        // Only rebuild for RUNNING collab runs
        List<CollabRunEntity> runningRuns = collabRunRepository.findByStatus("RUNNING");
        for (CollabRunEntity run : runningRuns) {
            List<SessionEntity> sessions = sessionRepository.findByCollabRunIdAndRuntimeStatus(
                    run.getCollabRunId(), "running");
            for (SessionEntity session : sessions) {
                String title = session.getTitle();
                String handle = null;
                if (title != null && title.startsWith("Team: ")) {
                    handle = title.substring(6);
                }
                if (handle != null) {
                    register(run.getCollabRunId(), handle, session.getId());
                }
            }
        }
        log.info("AgentRoster: rebuilt from DB, {} collab runs loaded", roster.size());
    }
}
