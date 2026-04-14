package com.skillforge.server.subagent;

import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentRunEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SubAgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 定时清扫 t_subagent_run 表里卡住的 RUNNING 行。
 *
 * 目标场景:
 *  1. RUNNING 且 childSessionId 指向的 session 已 idle/error 且 updatedAt 早于 ~30s
 *     → finally 钩子丢了(server crash / 钩子抛异常),通过 onSessionLoopFinished 恢复
 *  2. RUNNING 但 childSessionId 为 null 且 spawn 时间超过 10 分钟
 *     → 派发流程没完成,永远不会有子 session,标记 CANCELLED 并通知父
 *  3. RUNNING 且 childSessionId 不为 null 但子 session 已被删除
 *     → 标记 CANCELLED 并通知父
 */
@Component
public class SubAgentRunSweeper {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRunSweeper.class);

    /** 子 session 进入 idle/error 状态后,多久算 finally hook 丢失 */
    static final Duration CHILD_IDLE_GRACE = Duration.ofSeconds(30);
    /** 没 attach 子 session 的 run 多久算永久卡死 */
    static final Duration NO_CHILD_TIMEOUT = Duration.ofMinutes(10);
    /** All members idle for this long means the collab run missed its completion notification */
    static final Duration COLLAB_IDLE_THRESHOLD = Duration.ofMinutes(30);
    /** Any member running longer than this without progress triggers a warning */
    static final Duration COLLAB_STUCK_WARNING = Duration.ofHours(2);

    private final SubAgentRunRepository runRepository;
    private final SessionRepository sessionRepository;
    private final SubAgentRegistry subAgentRegistry;
    private final CollabRunRepository collabRunRepository;

    public SubAgentRunSweeper(SubAgentRunRepository runRepository,
                              SessionRepository sessionRepository,
                              SubAgentRegistry subAgentRegistry,
                              CollabRunRepository collabRunRepository) {
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.subAgentRegistry = subAgentRegistry;
        this.collabRunRepository = collabRunRepository;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void sweep() {
        try {
            sweepOnce();
        } catch (Exception e) {
            log.error("SubAgentRunSweeper.sweep failed", e);
        }
        try {
            sweepStaleCollabRuns();
        } catch (Exception e) {
            log.error("SubAgentRunSweeper.sweepStaleCollabRuns failed", e);
        }
    }

    void sweepOnce() {
        List<SubAgentRunEntity> running = runRepository.findByStatus("RUNNING");
        if (running.isEmpty()) return;
        Instant now = Instant.now();
        for (SubAgentRunEntity run : running) {
            try {
                handleRun(run, now);
            } catch (Exception e) {
                log.error("SubAgentRunSweeper: failed to handle run {}", run.getRunId(), e);
            }
        }
    }

    private void handleRun(SubAgentRunEntity run, Instant now) {
        String childSessionId = run.getChildSessionId();

        // case 2: 没有子 session —— 只有派发永久失败才走这里
        if (childSessionId == null) {
            Instant spawnedAt = run.getSpawnedAt();
            if (spawnedAt != null && Duration.between(spawnedAt, now).compareTo(NO_CHILD_TIMEOUT) >= 0) {
                log.warn("Sweeper: run {} has no child after {}, marking CANCELLED",
                        run.getRunId(), NO_CHILD_TIMEOUT);
                cancelAndNotifyParent(run, "Sweeper: child session never attached");
            }
            return;
        }

        Optional<SessionEntity> childOpt = sessionRepository.findById(childSessionId);
        if (childOpt.isEmpty()) {
            // case 3: 子 session 被删除
            log.warn("Sweeper: run {} child session {} deleted, marking CANCELLED",
                    run.getRunId(), childSessionId);
            cancelAndNotifyParent(run, "Sweeper: child session " + childSessionId + " no longer exists");
            return;
        }

        // case 1: 子 session 已经 idle/error 但 run 还是 RUNNING,说明 finally hook 丢了
        SessionEntity child = childOpt.get();
        String rs = child.getRuntimeStatus();
        if (!"idle".equals(rs) && !"error".equals(rs)) {
            return;
        }
        LocalDateTime updatedAt = child.getUpdatedAt();
        if (updatedAt == null) return;
        Instant updatedAtInstant = updatedAt.atZone(ZoneId.systemDefault()).toInstant();
        if (Duration.between(updatedAtInstant, now).compareTo(CHILD_IDLE_GRACE) < 0) {
            return;
        }

        log.warn("Sweeper: run {} child session {} is {} (updatedAt={}) but run still RUNNING; re-dispatching finally hook",
                run.getRunId(), childSessionId, rs, updatedAt);

        String status = "error".equals(rs) ? "error" : "completed";
        String finalMessage = "error".equals(rs)
                ? (child.getRuntimeError() != null ? child.getRuntimeError() : "Sweeper: recovered from lost finally hook")
                : "Sweeper: recovered from lost finally hook";
        // 复用 registry 的恢复通路 —— 它会 mark 状态、enqueue、maybeResumeParent
        subAgentRegistry.onSessionLoopFinished(childSessionId, finalMessage, status, 0, 0L);
    }

    /**
     * Sweep stale CollabRuns:
     * - If all member sessions are done (idle/error) and have been idle > 30 min, mark COMPLETED
     * - If any member has been running > 2 hours, log a warning
     */
    void sweepStaleCollabRuns() {
        List<CollabRunEntity> running = collabRunRepository.findByStatus("RUNNING");
        if (running.isEmpty()) return;
        Instant now = Instant.now();

        for (CollabRunEntity collabRun : running) {
            try {
                List<SessionEntity> members = sessionRepository.findByCollabRunId(collabRun.getCollabRunId());
                if (members.isEmpty()) continue;

                boolean allDone = true;
                boolean allIdleLongEnough = true;

                for (SessionEntity member : members) {
                    String rs = member.getRuntimeStatus();
                    boolean memberDone = "idle".equals(rs) || "error".equals(rs);

                    if (!memberDone) {
                        allDone = false;
                        allIdleLongEnough = false;

                        // Check for stuck member (running > 2 hours)
                        if ("running".equals(rs) && member.getCreatedAt() != null) {
                            Instant createdInstant = member.getCreatedAt()
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
                            if (Duration.between(createdInstant, now).compareTo(COLLAB_STUCK_WARNING) >= 0) {
                                log.warn("Sweeper: collab run {} member session {} has been running for > 2 hours",
                                        collabRun.getCollabRunId(), member.getId());
                            }
                        }
                    } else {
                        // Member is done — check if it's been idle long enough
                        Instant completedAt = member.getCompletedAt();
                        if (completedAt == null) {
                            // Use updatedAt as fallback
                            LocalDateTime updatedAt = member.getUpdatedAt();
                            if (updatedAt != null) {
                                completedAt = updatedAt.atZone(java.time.ZoneId.systemDefault()).toInstant();
                            }
                        }
                        if (completedAt == null || Duration.between(completedAt, now).compareTo(COLLAB_IDLE_THRESHOLD) < 0) {
                            allIdleLongEnough = false;
                        }
                    }
                }

                if (allDone && allIdleLongEnough) {
                    int updated = collabRunRepository.completeIfRunning(collabRun.getCollabRunId(), Instant.now());
                    if (updated > 0) {
                        log.warn("Sweeper: collab run {} had all members done but was still RUNNING; marked COMPLETED",
                                collabRun.getCollabRunId());
                    }
                }
            } catch (Exception e) {
                log.error("Sweeper: failed to handle collab run {}", collabRun.getCollabRunId(), e);
            }
        }
    }

    /**
     * 不可恢复的情况:run 直接 mark CANCELLED,并 fabricate 一次 finally 通知父,避免父
     * 面板永远停在 RUNNING。走 registry 常规通路保证 enqueue + drain 语义一致。
     */
    private void cancelAndNotifyParent(SubAgentRunEntity run, String reason) {
        run.setStatus("CANCELLED");
        run.setFinalMessage(reason);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);

        // 没有真正的 child session 可用,直接通过 registry 往父的 pending 队列塞一条伪造结果
        // 走 publicNotifyParent 辅助入口,避免我们再次造一个 ghost SessionEntity 触发复杂查询
        try {
            subAgentRegistry.notifyParentOfOrphanRun(run, reason);
        } catch (Exception e) {
            log.error("Sweeper: failed to notify parent for cancelled run {}", run.getRunId(), e);
        }
    }
}
