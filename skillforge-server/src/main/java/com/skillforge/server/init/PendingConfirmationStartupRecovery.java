package com.skillforge.server.init;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * B4 fix: on server restart, repair sessions that were mid-install-confirmation
 * (or any orphan {@code tool_use} case). For each session in
 * {@code runtimeStatus IN ('running', 'waiting_user')}:
 *
 * <ul>
 *   <li>Collect orphan tool_use ids — tool_use blocks whose id is never referenced by
 *       a tool_result in the persisted message list.</li>
 *   <li>Append one fabricated {@code tool_result(isError=true)} per orphan to preserve
 *       the tool_use ↔ tool_result 1:1 pairing invariant.</li>
 *   <li>Transition session to {@code runtimeStatus = "error"} with an informative reason.</li>
 * </ul>
 *
 * <p>Implements {@link SmartLifecycle} with {@code phase = Integer.MIN_VALUE + 100} so
 * Spring runs this recovery <b>before</b> the embedded web server's lifecycle phase
 * starts accepting HTTP requests. This closes the race "user POSTs to
 * {@code /api/chat/{id}} before recovery fabricates the orphan tool_result" that
 * {@code ApplicationRunner @Order(50)} could not fully prevent on Spring Boot 3.2
 * embedded Tomcat.
 *
 * <p>Footgun discipline: does NOT annotate {@code @Transactional} on itself — all
 * persistence is routed through {@link SessionService} public methods which already
 * have proper AOP-visible transactions. (See {@code .claude/rules/pipeline.md} §2.2.)
 */
@Component
public class PendingConfirmationStartupRecovery implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PendingConfirmationStartupRecovery.class);

    /** Phase: well before WebServerStartStopLifecycle (which uses ~Integer.MAX_VALUE). */
    public static final int PHASE = Integer.MIN_VALUE + 100;

    private final SessionRepository sessionRepository;
    private final SessionService sessionService;

    private volatile boolean running = false;

    public PendingConfirmationStartupRecovery(SessionRepository sessionRepository,
                                              SessionService sessionService) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        try {
            runRecovery();
        } catch (Exception e) {
            log.error("PendingConfirmationStartupRecovery failed (continuing startup)", e);
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** Start automatically on context refresh (before web server lifecycle). */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    void runRecovery() {
        // Load all sessions and filter by runtimeStatus in Java — keeps the query surface
        // small (no new JPA derived query) and the dataset is small (active sessions only
        // typically in the low 100s on a dev instance).
        List<SessionEntity> all = sessionRepository.findAll();
        int scanned = 0;
        int repairedSessions = 0;
        int totalOrphans = 0;
        for (SessionEntity s : all) {
            String rs = s.getRuntimeStatus();
            if (!"running".equals(rs) && !"waiting_user".equals(rs)) continue;
            scanned++;
            try {
                int orphans = repairSession(s);
                if (orphans > 0) {
                    repairedSessions++;
                    totalOrphans += orphans;
                }
            } catch (Exception e) {
                log.error("PendingConfirmationStartupRecovery: failed to repair sessionId={}", s.getId(), e);
            }
        }
        if (scanned > 0) {
            log.info("PendingConfirmationStartupRecovery: scanned={} repaired={} orphans={}",
                    scanned, repairedSessions, totalOrphans);
        } else {
            log.info("PendingConfirmationStartupRecovery: no in-flight sessions to recover");
        }
    }

    /**
     * @return number of orphan tool_use ids that were repaired (0 when nothing was wrong
     *         mid-flight but the session was still mid-running at shutdown).
     */
    int repairSession(SessionEntity s) {
        String sessionId = s.getId();
        List<Message> msgs = sessionService.getFullHistory(sessionId);
        List<String> orphanIds = collectOrphanToolUseIds(msgs);

        if (!orphanIds.isEmpty()) {
            List<Message> fabricated = new ArrayList<>(orphanIds.size());
            for (String id : orphanIds) {
                fabricated.add(Message.toolResult(id,
                        "Install confirmation aborted due to server restart", true));
            }
            sessionService.appendNormalMessages(sessionId, fabricated);
            log.warn("Recovery: sessionId={} appended {} fabricated tool_result(s) for orphan tool_use",
                    sessionId, orphanIds.size());
        }
        s.setRuntimeStatus("error");
        s.setRuntimeStep(null);
        s.setRuntimeError(orphanIds.isEmpty()
                ? "Server restarted while session was active"
                : "Recovered from restart: " + orphanIds.size() + " orphan tool_use(s) repaired");
        sessionService.saveSession(s);
        return orphanIds.size();
    }

    /** Collect tool_use ids whose matching tool_result is never observed in the message list. */
    static List<String> collectOrphanToolUseIds(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        Set<String> allToolUseIds = new java.util.LinkedHashSet<>();
        Set<String> completedToolUseIds = new HashSet<>();
        for (Message m : messages) {
            Object content = m.getContent();
            if (!(content instanceof List<?> blocks)) continue;
            for (Object o : blocks) {
                if (!(o instanceof ContentBlock cb)) continue;
                String type = cb.getType();
                if ("tool_use".equals(type)) {
                    if (cb.getId() != null) allToolUseIds.add(cb.getId());
                } else if ("tool_result".equals(type)) {
                    if (cb.getToolUseId() != null) completedToolUseIds.add(cb.getToolUseId());
                }
            }
        }
        List<String> orphans = new ArrayList<>();
        for (String id : allToolUseIds) {
            if (!completedToolUseIds.contains(id)) {
                orphans.add(id);
            }
        }
        return orphans;
    }
}
