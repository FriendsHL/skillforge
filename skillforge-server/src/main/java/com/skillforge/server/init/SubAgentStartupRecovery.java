package com.skillforge.server.init;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentRunEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SubAgentRunRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Server 重启时恢复 in-flight SubAgent run 行。
 *
 * 对每一条 status=RUNNING 的 SubAgentRunEntity:
 *  - childSessionId == null           → 派发流程没完成,mark CANCELLED
 *  - child.runtimeStatus == running   → 前次 JVM 挂掉 mid-loop,chatLoopExecutor 任务丢了,
 *                                        从持久化消息边界恢复，不新增合成 user message
 *  - child.runtimeStatus == idle/err  → 子 loop 跑完但 finally 钩子没触发,走 registry 恢复路径
 *  - child 不存在                      → mark CANCELLED 并通知父
 *
 * 放在 @Order(100) 运行,保证 Spring 全部 bean 就位后再恢复运行中的子 Agent。
 */
@Component
@Order(100)
public class SubAgentStartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SubAgentStartupRecovery.class);

    private final SubAgentRunRepository runRepository;
    private final SessionRepository sessionRepository;
    private final SubAgentRegistry subAgentRegistry;
    private final ChatService chatService;
    private final AgentRoster agentRoster;

    public SubAgentStartupRecovery(SubAgentRunRepository runRepository,
                                   SessionRepository sessionRepository,
                                   SubAgentRegistry subAgentRegistry,
                                   ChatService chatService,
                                   AgentRoster agentRoster) {
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.subAgentRegistry = subAgentRegistry;
        this.chatService = chatService;
        this.agentRoster = agentRoster;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Rebuild AgentRoster from DB for multi-agent collab runs
        try {
            agentRoster.rebuildFromDb();
        } catch (Exception e) {
            log.error("AgentRoster rebuild failed", e);
        }

        List<SubAgentRunEntity> runs = runRepository.findByStatus("RUNNING");
        if (runs.isEmpty()) {
            log.info("SubAgentStartupRecovery: no RUNNING rows to recover");
            return;
        }
        log.info("SubAgentStartupRecovery: found {} RUNNING run(s) to recover", runs.size());
        for (SubAgentRunEntity run : runs) {
            try {
                recoverRun(run);
            } catch (Exception e) {
                log.error("SubAgentStartupRecovery: failed to recover run {}", run.getRunId(), e);
            }
        }
    }

    private void recoverRun(SubAgentRunEntity run) {
        String childId = run.getChildSessionId();
        if (childId == null) {
            log.warn("Startup recovery: run {} has no child session, marking CANCELLED", run.getRunId());
            markCancelled(run, "Startup recovery: child session never attached before previous shutdown");
            subAgentRegistry.notifyParentOfOrphanRun(run,
                    "Startup recovery: child session never attached before previous shutdown");
            return;
        }
        Optional<SessionEntity> childOpt = sessionRepository.findById(childId);
        if (childOpt.isEmpty()) {
            log.warn("Startup recovery: run {} child session {} missing, marking CANCELLED",
                    run.getRunId(), childId);
            markCancelled(run, "Startup recovery: child session " + childId + " no longer exists");
            subAgentRegistry.notifyParentOfOrphanRun(run,
                    "Startup recovery: child session " + childId + " no longer exists");
            return;
        }
        SessionEntity child = childOpt.get();
        // EVAL-V2 M3a §2.2 R3: eval session 不走 production resume 路径 —— eval task 重启策略
        // 由 EvalOrchestrator (M3a b2) 决定（重跑或标记失败），这里直接 mark CANCELLED 避免
        // 跑出 production 风格的 "[Resume from restart]" 续接污染 eval trace。
        if (SessionEntity.ORIGIN_EVAL.equals(child.getOrigin())) {
            log.info("Startup recovery: skipping eval session {} (run {})", childId, run.getRunId());
            markCancelled(run, "Startup recovery: skipped eval session (handled by EvalOrchestrator)");
            return;
        }
        String rs = child.getRuntimeStatus();
        if ("running".equals(rs)) {
            if (child.getRecoveryAttempts() >= 3) {
                child.setRuntimeStatus("error");
                child.setRecoveryState("wedged");
                child.setRecoveryReason("RECOVERY_ATTEMPTS_EXHAUSTED");
                sessionRepository.save(child);
                markCancelled(run, "Startup recovery: automatic recovery attempts exhausted");
                subAgentRegistry.notifyParentOfOrphanRun(run,
                        "Startup recovery: automatic recovery attempts exhausted");
                return;
            }
            child.setRecoveryAttempts(child.getRecoveryAttempts() + 1);
            child.setRecoveryState("recovering");
            child.setRecoveryReason("SERVER_RESTART");
            child.setRecoveryStartedAt(Instant.now());
            sessionRepository.save(child);
            // 前次 JVM 挂掉 mid-loop,重新起一个 chatAsync 继续
            log.info("Startup recovery: resubmitting child session {} (run {}) from persisted boundary",
                    childId, run.getRunId());
            try {
                chatService.resumeInterruptedTurnAsync(childId);
            } catch (Exception e) {
                log.error("Startup recovery: chatAsync failed for child session {}, marking run CANCELLED",
                        childId, e);
                child.setRuntimeStatus("error");
                child.setRecoveryState("interrupted");
                child.setRecoveryReason("RECOVERY_DISPATCH_FAILED");
                sessionRepository.save(child);
                markCancelled(run, "Startup recovery: failed to resubmit child loop: " + e.getMessage());
                subAgentRegistry.notifyParentOfOrphanRun(run,
                        "Startup recovery: failed to resubmit child loop: " + e.getMessage());
            }
            return;
        }
        // idle / error: 子已经收尾,只是 finally 钩子没跑 → 复用 registry 恢复路径
        log.info("Startup recovery: child session {} (run {}) is {}; replaying finally hook",
                childId, run.getRunId(), rs);
        String status = "error".equals(rs) ? "error" : "completed";
        String finalMessage = "error".equals(rs)
                ? (child.getRuntimeError() != null ? child.getRuntimeError()
                        : "Startup recovery: recovered from missing finally hook")
                : "Startup recovery: recovered from missing finally hook";
        subAgentRegistry.onSessionLoopFinished(childId, finalMessage, status, 0, 0L);
    }

    private void markCancelled(SubAgentRunEntity run, String reason) {
        run.setStatus("CANCELLED");
        run.setFinalMessage(reason);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
    }
}
