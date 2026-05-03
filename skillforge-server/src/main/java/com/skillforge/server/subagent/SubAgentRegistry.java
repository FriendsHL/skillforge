package com.skillforge.server.subagent;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentPendingResultEntity;
import com.skillforge.server.entity.SubAgentRunEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SubAgentPendingResultRepository;
import com.skillforge.server.repository.SubAgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SubAgent 异步调度注册表。
 *
 * 职责:
 *  - 追踪每一次父→子派发(runId 维度)
 *  - 维护 "父 session → pending 结果队列" 的 mailbox
 *  - 子 session 完成时把结果投递给父 session
 *  - 如果父 session 当前 idle,立刻触发 chatAsync 唤醒父 loop;否则父 loop finally 时 drain
 *  - 强制深度 / 并发上限
 *
 * 持久化:
 *  - runs   → t_subagent_run 表 (SubAgentRunRepository)
 *  - pending → t_subagent_pending_result 表 (SubAgentPendingResultRepository)
 *  这样 server 重启后 in-flight 的子派发和尚未投递的结果都不会丢。
 *
 * 为避免 ChatService ↔ SubAgentRegistry 的循环依赖,这里用 ObjectProvider<ChatService> 懒加载。
 */
@Component
public class SubAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRegistry.class);

    public static final int MAX_DEPTH = 3;
    public static final int MAX_ACTIVE_CHILDREN_PER_PARENT = 5;
    public static final int MAX_DELIVERY_RETRIES = 3;

    /**
     * 固定大小的 stripe 锁数组,按 parentSessionId 哈希分桶。
     * 用数组而不是 ConcurrentHashMap / String.intern():
     *  - intern() 的字符串常驻 metaspace,UUID 量大时会泄漏
     *  - ConcurrentHashMap<String, Lock> 需要手动清理同样会增长
     *  - 数组容量固定,内存恒定;不同父 session 偶尔哈希到同一桶只是轻度串行,不影响正确性
     */
    private static final int LOCK_STRIPES = 64;
    private final Object[] parentLocks;

    /** Per-target monotonic seqNo generators for peer messaging. */
    private final ConcurrentHashMap<String, AtomicLong> seqNoGenerators = new ConcurrentHashMap<>();

    private final SessionRepository sessionRepository;
    private final SubAgentRunRepository runRepository;
    private final SubAgentPendingResultRepository pendingRepository;
    private final ObjectProvider<com.skillforge.server.service.ChatService> chatServiceProvider;

    public SubAgentRegistry(SessionRepository sessionRepository,
                            SubAgentRunRepository runRepository,
                            SubAgentPendingResultRepository pendingRepository,
                            ObjectProvider<com.skillforge.server.service.ChatService> chatServiceProvider) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.pendingRepository = pendingRepository;
        this.chatServiceProvider = chatServiceProvider;
        this.parentLocks = new Object[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.parentLocks[i] = new Object();
        }
    }

    private Object lockFor(String parentSessionId) {
        // Math.floorMod 处理负哈希
        return parentLocks[Math.floorMod(parentSessionId.hashCode(), LOCK_STRIPES)];
    }

    // ============ dispatch 侧 ============

    /**
     * 检查并注册一次新的 SubAgent run。
     * 失败会抛 IllegalStateException(深度/并发超限或父 session 不存在)。
     */
    public SubAgentRun registerRun(SessionEntity parentSession, Long childAgentId,
                                   String childAgentName, String task) {
        if (parentSession == null) {
            throw new IllegalStateException("Parent session not found");
        }
        int parentDepth = parentSession.getDepth();
        if (parentDepth >= MAX_DEPTH) {
            throw new IllegalStateException("SubAgent spawn rejected: depth limit (" + MAX_DEPTH + ") reached");
        }
        long activeCount = sessionRepository.countByParentSessionIdAndRuntimeStatus(parentSession.getId(), "running");
        if (activeCount >= MAX_ACTIVE_CHILDREN_PER_PARENT) {
            throw new IllegalStateException("SubAgent spawn rejected: max " + MAX_ACTIVE_CHILDREN_PER_PARENT
                    + " active children per parent");
        }
        String runId = java.util.UUID.randomUUID().toString();
        SubAgentRunEntity entity = new SubAgentRunEntity();
        entity.setRunId(runId);
        entity.setParentSessionId(parentSession.getId());
        entity.setChildAgentId(childAgentId);
        entity.setChildAgentName(childAgentName);
        entity.setTask(task);
        entity.setSpawnedAt(Instant.now());
        entity.setStatus("RUNNING");
        runRepository.save(entity);
        return toRun(entity);
    }

    public void attachChildSession(String runId, String childSessionId) {
        runRepository.findById(runId).ifPresent(entity -> {
            entity.setChildSessionId(childSessionId);
            runRepository.save(entity);
        });
    }

    public SubAgentRun getRun(String runId) {
        return runRepository.findById(runId).map(this::toRun).orElse(null);
    }

    public List<SubAgentRun> listRunsForParent(String parentSessionId) {
        List<SubAgentRun> out = new ArrayList<>();
        for (SubAgentRunEntity e : runRepository.findByParentSessionId(parentSessionId)) {
            out.add(toRun(e));
        }
        return out;
    }

    // ============ 子 session 完成回调 ============

    /**
     * ChatService.runLoop 在每次 loop 结束(成功/失败)时调用。
     *
     * 做两件事:
     *  1. 如果这是一个子 session(parentSessionId != null),把结果 push 到父 session 的 pending 队列
     *     并在父 idle 时立刻触发父 loop 的 chatAsync
     *  2. 如果这个 session 本身是父 session(它自己的 loop 刚跑完),drain 它的 pending 队列并 chatAsync
     */
    public void onSessionLoopFinished(String sessionId, String finalMessage, String status,
                                       int toolCalls, long durationMs) {
        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return;

        // case 1: 这是一个子 session 刚跑完 → 通知父
        if (session.getParentSessionId() != null) {
            String runId = session.getSubAgentRunId();
            SubAgentRunEntity runEntity = runId != null ? runRepository.findById(runId).orElse(null) : null;
            if (runEntity != null) {
                runEntity.setStatus("error".equals(status) ? "FAILED" : "COMPLETED");
                runEntity.setFinalMessage(finalMessage);
                runEntity.setCompletedAt(Instant.now());
                runRepository.save(runEntity);
            }
            SubAgentRun runDto = runEntity != null ? toRun(runEntity) : null;

            // Use TeamResult format for collab run members, SubAgent Result format otherwise
            String resultMsg;
            if (session.getCollabRunId() != null) {
                resultMsg = buildTeamResultMessage(runDto, session, finalMessage, status, toolCalls, durationMs);
            } else {
                resultMsg = buildResultMessage(runDto, session, finalMessage, status, toolCalls, durationMs);
            }
            enqueueForParent(session.getParentSessionId(), resultMsg);
            // 父 idle 则立刻唤醒(父 running 时会在父自己的 finally 里被 drain)
            maybeResumeParent(session.getParentSessionId());
        }

        // case 2: 这个 session 自己 (无论父/根) 结束时,drain 自己队列里已经到达的子结果
        //         这避免竞争:父跑 loop 的时候恰好子结束并 enqueue,父 finally 时也 drain 一遍。
        maybeResumeParent(sessionId);
    }

    /**
     * Sweeper / 启动恢复用的入口:某个 run 卡死且没有可用的 child session,
     * 直接伪造一条结果通知父 session,避免父面板永远停在 RUNNING。
     */
    public void notifyParentOfOrphanRun(SubAgentRunEntity run, String reason) {
        if (run == null || run.getParentSessionId() == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[SubAgent Result runId=").append(run.getRunId()).append("]\n");
        if (run.getChildAgentName() != null) {
            sb.append("Child agent: ").append(run.getChildAgentName()).append("\n");
        }
        sb.append("Status: cancelled\n");
        sb.append("Final message:\n");
        sb.append(reason == null ? "(cancelled by sweeper)" : reason);
        sb.append("\n[/SubAgent Result]");
        enqueueForParent(run.getParentSessionId(), sb.toString());
        maybeResumeParent(run.getParentSessionId());
    }

    private void enqueueForParent(String parentSessionId, String resultMsg) {
        SubAgentPendingResultEntity row = new SubAgentPendingResultEntity();
        row.setParentSessionId(parentSessionId);
        row.setPayload(resultMsg);
        row.setCreatedAt(Instant.now());
        pendingRepository.save(row);
        log.info("SubAgent result enqueued for parent={}", parentSessionId);
    }

    /**
     * 尝试把 pending 队列合并投递给父 session。
     *
     * 并发保护: 用固定大小的 stripe 锁数组(按 parentSessionId 哈希),保证同一父只有一个线程
     * 在 drain,避免重复投递。不同父大概率落不同桶,偶尔碰撞只是轻度串行。
     * 注意: 这是单机 MVP 方案;多实例部署时应改用 DB 行锁 / 乐观锁。
     */
    private void maybeResumeParent(String parentSessionId) {
        synchronized (lockFor(parentSessionId)) {
            List<SubAgentPendingResultEntity> rows =
                    pendingRepository.findByParentSessionIdAndStatusIsNullOrderByIdAsc(parentSessionId);
            if (rows.isEmpty()) return;

            SessionEntity parent = sessionRepository.findById(parentSessionId).orElse(null);
            if (parent == null) {
                pendingRepository.deleteAll(rows);
                return;
            }
            if (!"idle".equals(parent.getRuntimeStatus())) {
                // 父还在跑,等它自己 finally 再 drain
                return;
            }

            StringBuilder combined = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) combined.append("\n\n");
                combined.append(rows.get(i).getPayload());
            }
            String payload = combined.toString();
            int n = rows.size();

            // 先删除再 chatAsync:保证不会重复投递;如果 chatAsync 抛错,把合并后的 payload 作为单行塞回
            pendingRepository.deleteAll(rows);
            pendingRepository.flush();

            // Compute max retryCount from the batch being delivered
            int maxRetry = 0;
            for (SubAgentPendingResultEntity r : rows) {
                if (r.getRetryCount() > maxRetry) maxRetry = r.getRetryCount();
            }

            log.info("Resuming parent session {} with {} pending subagent result(s)", parentSessionId, n);
            try {
                // OBS-4 §2.1: preserveActiveRoot=true — subagent 结果回投是合成续接，不是新 user message
                // 边界，要让 parent 的下一个 trace 继承父 active_root（INV-3 同 user message 内主 agent
                // 所有 trace 共享同一 root_trace_id）。决策 Q7：父 session 的 active_root 不在此清空。
                chatServiceProvider.getObject().chatAsync(parentSessionId, payload, parent.getUserId(), true);
            } catch (Exception e) {
                int nextRetry = maxRetry + 1;
                if (nextRetry >= MAX_DELIVERY_RETRIES) {
                    log.error("Failed to resume parent session {} after {} retries, marking DELIVERY_FAILED",
                            parentSessionId, nextRetry, e);
                    SubAgentPendingResultEntity failed = new SubAgentPendingResultEntity();
                    failed.setParentSessionId(parentSessionId);
                    failed.setPayload(payload);
                    failed.setRetryCount(nextRetry);
                    failed.setStatus("DELIVERY_FAILED");
                    failed.setCreatedAt(Instant.now());
                    pendingRepository.save(failed);
                } else {
                    log.error("Failed to resume parent session {}, re-enqueueing (retry {})",
                            parentSessionId, nextRetry, e);
                    SubAgentPendingResultEntity retry = new SubAgentPendingResultEntity();
                    retry.setParentSessionId(parentSessionId);
                    retry.setPayload(payload);
                    retry.setRetryCount(nextRetry);
                    retry.setCreatedAt(Instant.now());
                    pendingRepository.save(retry);
                }
            }
        }
    }

    // ============ Peer messaging (Phase 2) ============

    /**
     * Generate a monotonic seqNo for the given target session.
     * Uses System.nanoTime() as the initial value for MVP simplicity.
     */
    public long nextSeqNo(String targetSessionId) {
        return seqNoGenerators
                .computeIfAbsent(targetSessionId, k -> new AtomicLong(System.nanoTime()))
                .incrementAndGet();
    }

    /**
     * Enqueue a message for any session (generalized peer messaging).
     * Uses messageId-based dedup to avoid duplicate delivery.
     * Returns false if the target session doesn't exist or is in a terminal state.
     */
    public boolean enqueueForSession(String targetSessionId, String payload, String messageId, Long seqNo) {
        // Orphan detection: check if target session is alive
        SessionEntity target = sessionRepository.findById(targetSessionId).orElse(null);
        if (target == null) {
            log.warn("Target session {} does not exist, message dropped (messageId={})", targetSessionId, messageId);
            return false;
        }
        if (target.getCompletedAt() != null
                && !"running".equals(target.getRuntimeStatus())
                && !"idle".equals(target.getRuntimeStatus())) {
            log.warn("Target session {} is not alive (status={}, completedAt={}), message dropped (messageId={})",
                    targetSessionId, target.getRuntimeStatus(), target.getCompletedAt(), messageId);
            return false;
        }

        if (messageId != null && pendingRepository.existsByMessageId(messageId)) {
            log.info("Dedup: messageId={} already exists for target={}, skipping", messageId, targetSessionId);
            return true;
        }
        SubAgentPendingResultEntity row = new SubAgentPendingResultEntity();
        row.setParentSessionId(targetSessionId); // keep parentSessionId populated for backward compat queries
        row.setTargetSessionId(targetSessionId);
        row.setPayload(payload);
        row.setMessageId(messageId);
        row.setSeqNo(seqNo);
        row.setCreatedAt(Instant.now());
        pendingRepository.save(row);
        log.info("Peer message enqueued for target={} messageId={} seqNo={}", targetSessionId, messageId, seqNo);
        return true;
    }

    /**
     * Try to resume any session by draining its pending queue.
     * Same logic as maybeResumeParent but works for any session.
     */
    public void maybeResumeSession(String targetSessionId) {
        synchronized (lockFor(targetSessionId)) {
            // Use targetSessionId query with seqNo ordering for proper peer message support
            List<SubAgentPendingResultEntity> rows =
                    pendingRepository.findByTargetSessionIdAndStatusIsNullOrderBySeqNoAsc(targetSessionId);
            // Fallback to parentSessionId query for backward compat (SubAgent results don't set targetSessionId)
            if (rows.isEmpty()) {
                rows = pendingRepository.findByParentSessionIdAndStatusIsNullOrderByIdAsc(targetSessionId);
            }
            if (rows.isEmpty()) return;

            SessionEntity target = sessionRepository.findById(targetSessionId).orElse(null);
            if (target == null) {
                pendingRepository.deleteAll(rows);
                return;
            }
            if (!"idle".equals(target.getRuntimeStatus())) {
                // Target still running, it will drain on its own loop finally
                return;
            }

            StringBuilder combined = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) combined.append("\n\n");
                combined.append(rows.get(i).getPayload());
            }
            String payload = combined.toString();
            int n = rows.size();

            pendingRepository.deleteAll(rows);
            pendingRepository.flush();

            // Compute max retryCount from the batch being delivered
            int maxRetry = 0;
            for (SubAgentPendingResultEntity r : rows) {
                if (r.getRetryCount() > maxRetry) maxRetry = r.getRetryCount();
            }

            log.info("Resuming session {} with {} pending message(s)", targetSessionId, n);
            try {
                // OBS-4 §2.1: preserveActiveRoot=true — peer message / subagent result 是合成续接，
                // 不是新 user message 边界。target 的 active_root 由前一个 trace（同 user message 内）
                // 已设；新 trace 应继承同一 root（INV-3）。
                chatServiceProvider.getObject().chatAsync(targetSessionId, payload, target.getUserId(), true);
            } catch (Exception e) {
                int nextRetry = maxRetry + 1;
                if (nextRetry >= MAX_DELIVERY_RETRIES) {
                    log.error("Failed to resume session {} after {} retries, marking DELIVERY_FAILED",
                            targetSessionId, nextRetry, e);
                    SubAgentPendingResultEntity failed = new SubAgentPendingResultEntity();
                    failed.setParentSessionId(targetSessionId);
                    failed.setTargetSessionId(targetSessionId);
                    failed.setPayload(payload);
                    failed.setRetryCount(nextRetry);
                    failed.setStatus("DELIVERY_FAILED");
                    failed.setCreatedAt(Instant.now());
                    pendingRepository.save(failed);
                } else {
                    log.error("Failed to resume session {}, re-enqueueing (retry {})",
                            targetSessionId, nextRetry, e);
                    SubAgentPendingResultEntity retry = new SubAgentPendingResultEntity();
                    retry.setParentSessionId(targetSessionId);
                    retry.setTargetSessionId(targetSessionId);
                    retry.setPayload(payload);
                    retry.setRetryCount(nextRetry);
                    retry.setCreatedAt(Instant.now());
                    pendingRepository.save(retry);
                }
            }
        }
    }

    private String buildTeamResultMessage(SubAgentRun run, SessionEntity child, String finalMessage,
                                          String status, int toolCalls, long durationMs) {
        // Extract handle from session title (format: "Team: <handle>")
        String handle = "unknown";
        if (child.getTitle() != null && child.getTitle().startsWith("Team: ")) {
            handle = child.getTitle().substring(6);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[TeamResult handle=").append(handle);
        sb.append(" collabRunId=").append(child.getCollabRunId());
        sb.append("]\n");
        sb.append("Agent: ");
        if (run != null && run.childAgentName != null) {
            sb.append(run.childAgentName);
        } else {
            sb.append("unknown");
        }
        sb.append(" (session ").append(child.getId()).append(")\n");
        sb.append("Status: ").append(status == null ? "completed" : status).append("\n");
        sb.append("Tool calls: ").append(toolCalls).append(", duration: ").append(durationMs).append("ms\n");
        sb.append("Result:\n");
        sb.append(finalMessage == null ? "(no final message)" : finalMessage);
        sb.append("\n[/TeamResult]");
        return sb.toString();
    }

    private String buildResultMessage(SubAgentRun run, SessionEntity child, String finalMessage,
                                       String status, int toolCalls, long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[SubAgent Result");
        if (run != null) sb.append(" runId=").append(run.runId);
        sb.append("]\n");
        if (run != null && run.childAgentName != null) {
            sb.append("Child agent: ").append(run.childAgentName);
            sb.append(" (session ").append(child.getId()).append(")\n");
        }
        sb.append("Status: ").append(status == null ? "completed" : status).append("\n");
        sb.append("Tool calls: ").append(toolCalls).append(", duration: ").append(durationMs).append(" ms\n");
        sb.append("Final message:\n");
        sb.append(finalMessage == null ? "(no final message)" : finalMessage);
        sb.append("\n[/SubAgent Result]");
        return sb.toString();
    }

    private SubAgentRun toRun(SubAgentRunEntity e) {
        SubAgentRun r = new SubAgentRun();
        r.runId = e.getRunId();
        r.parentSessionId = e.getParentSessionId();
        r.childSessionId = e.getChildSessionId();
        r.childAgentId = e.getChildAgentId();
        r.childAgentName = e.getChildAgentName();
        r.task = e.getTask();
        r.status = e.getStatus();
        r.finalMessage = e.getFinalMessage();
        r.spawnedAt = e.getSpawnedAt();
        r.completedAt = e.getCompletedAt();
        return r;
    }

    // ============ 数据结构 ============

    /**
     * DTO,镜像 SubAgentRunEntity —— SubAgentTool 和测试都直接读这些字段。
     * 保留为 POJO 是为了不在 skill 代码里暴露 JPA entity。
     */
    public static class SubAgentRun {
        public String runId;
        public String parentSessionId;
        public String childSessionId;
        public Long childAgentId;
        public String childAgentName;
        public String task;
        public String status; // RUNNING / COMPLETED / FAILED / CANCELLED
        public String finalMessage;
        public Instant spawnedAt;
        public Instant completedAt;
    }
}
