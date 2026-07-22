package com.skillforge.server.init;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.runtime.RuntimeFailureClassifier;
import com.skillforge.server.runtime.RuntimeFailureFact;
import com.skillforge.server.runtime.RuntimeFailureState;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * On server restart, recover production sessions abandoned in {@code running} state.
 * For each session in
 * {@code runtimeStatus IN ('running', 'waiting_user')}:
 *
 * <ul>
 *   <li>Preserve {@code waiting_user} controls without auto-answering them.</li>
 *   <li>Resume from a persisted user/tool_result boundary without adding a synthetic query.</li>
 *   <li>Fail closed on exceptional orphan tool_use history and after three attempts.</li>
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
    private static final RuntimeFailureClassifier RUNTIME_FAILURE_CLASSIFIER =
            new RuntimeFailureClassifier();

    /** Phase: well before WebServerStartStopLifecycle (which uses ~Integer.MAX_VALUE). */
    public static final int PHASE = Integer.MIN_VALUE + 100;

    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final ChatService chatService;

    private volatile boolean running = false;

    public PendingConfirmationStartupRecovery(SessionRepository sessionRepository,
                                              SessionService sessionService,
                                              ChatService chatService) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.chatService = chatService;
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
            // EVAL-V2 M3a §2.2 R3: eval session 不走 production 的 confirmation 修复路径 ——
            // eval 流程不会出现 install confirmation；万一出现也由 EvalOrchestrator 处理。
            // 跳过这里防止改写 eval session 的 messages 序列影响后续归因分析。
            if (SessionEntity.ORIGIN_EVAL.equals(s.getOrigin())) {
                continue;
            }
            // Child sessions are owned by SubAgentStartupRecovery, which also reconciles
            // the durable run row and parent delivery. Avoid double submission here.
            if (s.getParentSessionId() != null) {
                continue;
            }
            if ("waiting_user".equals(rs)) {
                log.info("Recovery: preserving waiting_user sessionId={}", s.getId());
                continue;
            }
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
            markInterrupted(s, "SERVER_RESTART_ORPHAN_TOOL_USE",
                    "Persisted history contains an incomplete tool call.", "observed");
            return orphanIds.size();
        }
        if (msgs.isEmpty()) {
            markInterrupted(s, "SERVER_RESTART_NO_CHECKPOINT",
                    "No persisted recovery boundary is available.", "possible");
            return 0;
        }
        Message tail = msgs.get(msgs.size() - 1);
        if (tail.getRole() == Message.Role.ASSISTANT) {
            s.setRuntimeStatus("idle");
            s.setRecoveryAttempts(0);
            s.setRecoveryState("none");
            s.setRecoveryReason(null);
            s.setRecoveryStartedAt(null);
            RuntimeFailureState.clear(s);
            sessionService.saveSession(s);
            return 0;
        }
        if (s.getRecoveryAttempts() >= 3) {
            s.setRuntimeStatus("error");
            s.setRecoveryState("wedged");
            s.setRecoveryReason("RECOVERY_ATTEMPTS_EXHAUSTED");
            RuntimeFailureState.apply(s, RUNTIME_FAILURE_CLASSIFIER.harnessFailure(
                    "RECOVERY_ATTEMPTS_EXHAUSTED", "Automatic recovery was exhausted.", "possible"));
            s.setRuntimeStatus("error");
            sessionService.saveSession(s);
            return 0;
        }
        s.setRecoveryAttempts(s.getRecoveryAttempts() + 1);
        s.setRecoveryState("recovering");
        s.setRecoveryReason("SERVER_RESTART");
        s.setRecoveryStartedAt(Instant.now());
        sessionService.saveSession(s);
        try {
            chatService.resumeInterruptedTurnAsync(sessionId);
        } catch (RuntimeException error) {
            markInterrupted(s, "RECOVERY_DISPATCH_FAILED",
                    "The interrupted task could not be resubmitted.", "possible");
            throw error;
        }
        return 0;
    }

    private void markInterrupted(SessionEntity session, String code, String message, String sideEffects) {
        session.setRuntimeStatus("error");
        session.setRecoveryState("interrupted");
        session.setRecoveryReason(code);
        RuntimeFailureFact failure = RUNTIME_FAILURE_CLASSIFIER.harnessFailure(code, message, sideEffects);
        RuntimeFailureState.apply(session, failure);
        session.setRuntimeStatus("error");
        sessionService.saveSession(session);
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
