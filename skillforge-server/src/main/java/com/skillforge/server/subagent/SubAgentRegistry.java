package com.skillforge.server.subagent;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
 * 为避免 ChatService ↔ SubAgentRegistry 的循环依赖,这里用 ObjectProvider<ChatService> 懒加载。
 */
@Component
public class SubAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRegistry.class);

    public static final int MAX_DEPTH = 3;
    public static final int MAX_ACTIVE_CHILDREN_PER_PARENT = 5;

    private final SessionRepository sessionRepository;
    private final ObjectProvider<com.skillforge.server.service.ChatService> chatServiceProvider;

    // runId → SubAgentRun
    private final ConcurrentHashMap<String, SubAgentRun> runs = new ConcurrentHashMap<>();
    // parentSessionId → queue of pending result messages
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> pendingResults = new ConcurrentHashMap<>();

    public SubAgentRegistry(SessionRepository sessionRepository,
                            ObjectProvider<com.skillforge.server.service.ChatService> chatServiceProvider) {
        this.sessionRepository = sessionRepository;
        this.chatServiceProvider = chatServiceProvider;
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
        SubAgentRun run = new SubAgentRun();
        run.runId = runId;
        run.parentSessionId = parentSession.getId();
        run.childAgentId = childAgentId;
        run.childAgentName = childAgentName;
        run.task = task;
        run.spawnedAt = Instant.now();
        run.status = "RUNNING";
        runs.put(runId, run);
        return run;
    }

    public void attachChildSession(String runId, String childSessionId) {
        SubAgentRun run = runs.get(runId);
        if (run != null) {
            run.childSessionId = childSessionId;
        }
    }

    public SubAgentRun getRun(String runId) {
        return runs.get(runId);
    }

    public List<SubAgentRun> listRunsForParent(String parentSessionId) {
        List<SubAgentRun> out = new ArrayList<>();
        for (SubAgentRun r : runs.values()) {
            if (parentSessionId.equals(r.parentSessionId)) {
                out.add(r);
            }
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
            SubAgentRun run = runId != null ? runs.get(runId) : null;
            if (run != null) {
                run.status = "error".equals(status) ? "FAILED" : "COMPLETED";
                run.finalMessage = finalMessage;
                run.completedAt = Instant.now();
            }
            String resultMsg = buildResultMessage(run, session, finalMessage, status, toolCalls, durationMs);
            enqueueForParent(session.getParentSessionId(), resultMsg);
            // 父 idle 则立刻唤醒(父 running 时会在父自己的 finally 里被 drain)
            maybeResumeParent(session.getParentSessionId());
        }

        // case 2: 这个 session 自己 (无论父/根) 结束时,drain 自己队列里已经到达的子结果
        //         这避免竞争:父跑 loop 的时候恰好子结束并 enqueue,父 finally 时也 drain 一遍。
        maybeResumeParent(sessionId);
    }

    private void enqueueForParent(String parentSessionId, String resultMsg) {
        pendingResults.computeIfAbsent(parentSessionId, k -> new ConcurrentLinkedDeque<>()).add(resultMsg);
        log.info("SubAgent result enqueued for parent={}, pending={}",
                parentSessionId, pendingResults.get(parentSessionId).size());
    }

    private void maybeResumeParent(String parentSessionId) {
        ConcurrentLinkedDeque<String> queue = pendingResults.get(parentSessionId);
        if (queue == null || queue.isEmpty()) return;

        SessionEntity parent = sessionRepository.findById(parentSessionId).orElse(null);
        if (parent == null) {
            pendingResults.remove(parentSessionId);
            return;
        }
        if (!"idle".equals(parent.getRuntimeStatus())) {
            // 父还在跑,等它自己 finally 再 drain
            return;
        }

        // 原子 drain:pollFirst 循环,直到队列空或有人跟我们抢走
        StringBuilder combined = new StringBuilder();
        int n = 0;
        String item;
        while ((item = queue.pollFirst()) != null) {
            if (n > 0) combined.append("\n\n");
            combined.append(item);
            n++;
        }
        if (n == 0) return;

        // 移除空队列
        pendingResults.remove(parentSessionId, queue);

        log.info("Resuming parent session {} with {} pending subagent result(s)", parentSessionId, n);
        try {
            chatServiceProvider.getObject().chatAsync(parentSessionId, combined.toString(), parent.getUserId());
        } catch (Exception e) {
            log.error("Failed to resume parent session {}", parentSessionId, e);
            // 失败时把结果塞回队列,下次机会再试
            pendingResults.computeIfAbsent(parentSessionId, k -> new ConcurrentLinkedDeque<>())
                    .addFirst(combined.toString());
        }
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

    // ============ 数据结构 ============

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
