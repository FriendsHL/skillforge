package com.skillforge.server.subagent;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service managing multi-agent collaboration runs.
 */
@Service
public class CollabRunService {

    private static final Logger log = LoggerFactory.getLogger(CollabRunService.class);

    private static final int MAX_CHILDREN_PER_PARENT = 5;

    private final CollabRunRepository collabRunRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final AgentService agentService;
    private final SubAgentRegistry subAgentRegistry;
    private final AgentRoster agentRoster;
    private final CancellationRegistry cancellationRegistry;
    private final ObjectProvider<ChatService> chatServiceProvider;
    private final ChatEventBroadcaster broadcaster;

    public CollabRunService(CollabRunRepository collabRunRepository,
                            SessionRepository sessionRepository,
                            SessionService sessionService,
                            AgentService agentService,
                            SubAgentRegistry subAgentRegistry,
                            AgentRoster agentRoster,
                            CancellationRegistry cancellationRegistry,
                            ObjectProvider<ChatService> chatServiceProvider,
                            ChatEventBroadcaster broadcaster) {
        this.collabRunRepository = collabRunRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.subAgentRegistry = subAgentRegistry;
        this.agentRoster = agentRoster;
        this.cancellationRegistry = cancellationRegistry;
        this.chatServiceProvider = chatServiceProvider;
        this.broadcaster = broadcaster;
    }

    /**
     * Create a new collaboration run with the given session as leader.
     */
    public CollabRunEntity createRun(String leaderSessionId, int maxDepth, int maxTotalAgents) {
        CollabRunEntity run = new CollabRunEntity();
        run.setCollabRunId(UUID.randomUUID().toString());
        run.setLeaderSessionId(leaderSessionId);
        run.setStatus("RUNNING");
        run.setMaxDepth(maxDepth);
        run.setMaxTotalAgents(maxTotalAgents);
        run.setCreatedAt(Instant.now());
        collabRunRepository.save(run);

        // Mark the leader session as belonging to this collab run
        SessionEntity leader = sessionService.getSession(leaderSessionId);
        leader.setCollabRunId(run.getCollabRunId());
        sessionService.saveSession(leader);

        log.info("CollabRun created: collabRunId={}, leader={}", run.getCollabRunId(), leaderSessionId);

        if (broadcaster != null) {
            broadcaster.collabRunStatus(run.getCollabRunId(), "RUNNING");
        }

        return run;
    }

    /**
     * Spawn a new member agent in the collaboration run.
     */
    public SessionEntity spawnMember(String collabRunId, String handle, Long agentId,
                                      String task, String briefing, SessionEntity spawningSession) {
        return spawnMember(collabRunId, handle, agentId, task, briefing, spawningSession, false);
    }

    /**
     * Spawn a new member agent in the collaboration run, with optional lightContext flag.
     */
    public SessionEntity spawnMember(String collabRunId, String handle, Long agentId,
                                      String task, String briefing, SessionEntity spawningSession,
                                      boolean lightContext) {
        return spawnMember(collabRunId, handle, agentId, task, briefing, spawningSession, lightContext, null);
    }

    /**
     * Spawn a new member agent in the collaboration run, with optional lightContext and maxLoops override.
     */
    public SessionEntity spawnMember(String collabRunId, String handle, Long agentId,
                                      String task, String briefing, SessionEntity spawningSession,
                                      boolean lightContext, Integer maxLoops) {
        CollabRunEntity collabRun = collabRunRepository.findById(collabRunId)
                .orElseThrow(() -> new IllegalStateException("CollabRun not found: " + collabRunId));

        // Check depth limit
        if (spawningSession.getDepth() >= collabRun.getMaxDepth()) {
            throw new IllegalStateException("TeamCreate rejected: depth limit (" + collabRun.getMaxDepth()
                    + ") reached. Current depth: " + spawningSession.getDepth());
        }

        // Check max children per parent
        long activeChildren = sessionRepository.countByParentSessionIdAndRuntimeStatus(
                spawningSession.getId(), "running");
        if (activeChildren >= MAX_CHILDREN_PER_PARENT) {
            throw new IllegalStateException("TeamCreate rejected: max " + MAX_CHILDREN_PER_PARENT
                    + " active children per parent");
        }

        // Check handle uniqueness — reject if handle already maps to a live session
        String existingSessionId = agentRoster.resolve(collabRunId, handle);
        if (existingSessionId != null) {
            throw new IllegalStateException("TeamCreate rejected: handle '" + handle
                    + "' is already in use by session " + existingSessionId.substring(0, 8));
        }

        // Check max total agents
        long totalAgents = sessionRepository.countByCollabRunId(collabRunId);
        if (totalAgents >= collabRun.getMaxTotalAgents()) {
            throw new IllegalStateException("TeamCreate rejected: max " + collabRun.getMaxTotalAgents()
                    + " total agents in this collaboration run");
        }

        // Resolve agent
        AgentEntity targetAgent;
        try {
            targetAgent = agentService.getAgent(agentId);
        } catch (Exception e) {
            throw new IllegalStateException("Target agent not found: id=" + agentId);
        }

        // Register a SubAgentRunEntity (reuse existing pattern)
        SubAgentRegistry.SubAgentRun run = subAgentRegistry.registerRun(
                spawningSession, agentId, targetAgent.getName(), task);

        // Create child session via SessionService
        SessionEntity child = sessionService.createSubSession(spawningSession, agentId, run.runId);
        // Set collabRunId on child
        child.setCollabRunId(collabRunId);
        // Set title with handle for roster rebuild
        child.setTitle("Team: " + handle);
        // Set lightContext flag if requested
        if (lightContext) {
            child.setLightContext(true);
        }
        // Set maxLoops override on child session if provided
        if (maxLoops != null && maxLoops > 0) {
            child.setMaxLoops(maxLoops);
        }
        // OBS-4 §2.5 INV-4: 复制父 session 当前 active_root 给 child，让 child 内部 trace
        // 继承同一 root（递归 child of child 也走此路径，决策 Q6 无深度限制）。
        // 注意：必须在 chatAsync 之前 setActiveRootTraceId，让 child 的 chatAsync 能读到。
        child.setActiveRootTraceId(spawningSession.getActiveRootTraceId());
        sessionService.saveSession(child);

        subAgentRegistry.attachChildSession(run.runId, child.getId());

        // Register in AgentRoster
        agentRoster.register(collabRunId, handle, child.getId());

        // Prepare the full task message
        String fullTask = task;
        if (briefing != null && !briefing.isBlank()) {
            fullTask = "## Briefing\n\n" + briefing + "\n\n## Task\n\n" + task;
        }

        // Fire chatAsync asynchronously
        // OBS-4 §2.1: preserveActiveRoot=true — child 已被设好 active_root（= 父 active_root），
        // 不要在 chatAsync 入口清空，让 child 内部第一个 trace 也继承同一 root（INV-4）。
        chatServiceProvider.getObject().chatAsync(child.getId(), fullTask,
                spawningSession.getUserId(), true);

        log.info("CollabRun member spawned: collabRunId={}, handle={}, childSession={}, agent={}",
                collabRunId, handle, child.getId(), targetAgent.getName());

        if (broadcaster != null) {
            broadcaster.collabMemberSpawned(collabRunId, handle, child.getId(), targetAgent.getName());
        }

        return child;
    }

    /**
     * Called when a member session completes. Checks if all members are done.
     * Uses JPQL conditional update to avoid TOCTOU race when multiple children complete simultaneously.
     */
    @Transactional
    public void onMemberCompleted(String collabRunId, String sessionId) {
        CollabRunEntity collabRun = collabRunRepository.findById(collabRunId).orElse(null);
        if (collabRun == null || !"RUNNING".equals(collabRun.getStatus())) return;

        // Find the handle for this session
        String handle = null;
        for (var entry : agentRoster.listMembers(collabRunId).entrySet()) {
            if (entry.getValue().equals(sessionId)) {
                handle = entry.getKey();
                break;
            }
        }

        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        String status = session != null ? session.getRuntimeStatus() : "unknown";

        if (broadcaster != null && handle != null) {
            String summary = session != null && session.getTitle() != null ? session.getTitle() : "";
            broadcaster.collabMemberFinished(collabRunId, handle, status, summary);
        }

        // Check if all sessions in the collab run are done — use atomic update to avoid TOCTOU race
        List<SessionEntity> runningSessions = sessionRepository.findByCollabRunIdAndRuntimeStatus(collabRunId, "running");
        if (runningSessions.isEmpty()) {
            int updated = collabRunRepository.completeIfRunning(collabRunId, Instant.now());
            if (updated > 0) {
                log.info("CollabRun completed: collabRunId={}", collabRunId);
                if (broadcaster != null) {
                    broadcaster.collabRunStatus(collabRunId, "COMPLETED");
                }
            }
            // else: another thread already completed/cancelled it — skip
        }
    }

    /**
     * Cancel all running sessions in the collaboration run.
     * Skips if the run is already COMPLETED or CANCELLED.
     */
    public void cancelRun(String collabRunId) {
        CollabRunEntity collabRun = collabRunRepository.findById(collabRunId).orElse(null);
        if (collabRun == null) {
            throw new IllegalStateException("CollabRun not found: " + collabRunId);
        }
        // Skip if already terminal — avoids overwriting COMPLETED with CANCELLED
        if ("COMPLETED".equals(collabRun.getStatus()) || "CANCELLED".equals(collabRun.getStatus())) {
            log.info("CollabRun {} already in terminal state {}, skipping cancel", collabRunId, collabRun.getStatus());
            return;
        }

        List<SessionEntity> runningSessions = sessionRepository.findByCollabRunIdAndRuntimeStatus(collabRunId, "running");
        for (SessionEntity session : runningSessions) {
            cancellationRegistry.cancel(session.getId());
            log.info("CollabRun cancel: cancelling session={} in collab={}", session.getId(), collabRunId);
        }

        collabRun.setStatus("CANCELLED");
        collabRun.setCompletedAt(Instant.now());
        collabRunRepository.save(collabRun);

        log.info("CollabRun cancelled: collabRunId={}, {} sessions cancelled", collabRunId, runningSessions.size());

        if (broadcaster != null) {
            broadcaster.collabRunStatus(collabRunId, "CANCELLED");
        }
    }

    /**
     * Get a CollabRunEntity by ID.
     */
    public CollabRunEntity getRun(String collabRunId) {
        return collabRunRepository.findById(collabRunId).orElse(null);
    }
}
