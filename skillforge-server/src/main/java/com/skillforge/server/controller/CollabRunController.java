package com.skillforge.server.controller;

import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentPendingResultEntity;
import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SubAgentPendingResultRepository;
import com.skillforge.server.repository.TraceSpanRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.CollabRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/collab-runs")
public class CollabRunController {

    private final CollabRunService collabRunService;
    private final AgentRoster agentRoster;
    private final SessionService sessionService;
    private final AgentService agentService;
    private final SubAgentPendingResultRepository pendingResultRepository;
    private final TraceSpanRepository traceSpanRepository;
    private final CollabRunRepository collabRunRepository;

    public CollabRunController(CollabRunService collabRunService,
                               AgentRoster agentRoster,
                               SessionService sessionService,
                               AgentService agentService,
                               SubAgentPendingResultRepository pendingResultRepository,
                               TraceSpanRepository traceSpanRepository,
                               CollabRunRepository collabRunRepository) {
        this.collabRunService = collabRunService;
        this.agentRoster = agentRoster;
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.pendingResultRepository = pendingResultRepository;
        this.traceSpanRepository = traceSpanRepository;
        this.collabRunRepository = collabRunRepository;
    }

    @GetMapping
    public ResponseEntity<?> listCollabRuns() {
        List<CollabRunEntity> runs = collabRunRepository.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (CollabRunEntity run : runs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("collabRunId", run.getCollabRunId());
            item.put("status", run.getStatus());
            item.put("leaderSessionId", run.getLeaderSessionId());
            item.put("maxDepth", run.getMaxDepth());
            item.put("maxTotalAgents", run.getMaxTotalAgents());
            item.put("memberCount", agentRoster.listMembers(run.getCollabRunId()).size());
            item.put("createdAt", run.getCreatedAt());
            item.put("completedAt", run.getCompletedAt());
            long durationMs = 0;
            if (run.getCreatedAt() != null) {
                java.time.Instant end = run.getCompletedAt() != null ? run.getCompletedAt() : java.time.Instant.now();
                durationMs = java.time.Duration.between(run.getCreatedAt(), end).toMillis();
            }
            item.put("durationMs", durationMs);
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{collabRunId}/members")
    public ResponseEntity<?> getMembers(@PathVariable String collabRunId) {
        CollabRunEntity collabRun = collabRunService.getRun(collabRunId);
        if (collabRun == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, String> rosterMembers = agentRoster.listMembers(collabRunId);
        List<Map<String, Object>> members = new ArrayList<>();

        for (Map.Entry<String, String> entry : rosterMembers.entrySet()) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("handle", entry.getKey());
            member.put("sessionId", entry.getValue());

            try {
                SessionEntity session = sessionService.getSession(entry.getValue());
                member.put("runtimeStatus", session.getRuntimeStatus());
                member.put("agentId", session.getAgentId());
                member.put("depth", session.getDepth());
                member.put("title", session.getTitle());
            } catch (Exception e) {
                member.put("runtimeStatus", "not_found");
            }

            members.add(member);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collabRunId", collabRun.getCollabRunId());
        result.put("status", collabRun.getStatus());
        result.put("leaderSessionId", collabRun.getLeaderSessionId());
        result.put("maxDepth", collabRun.getMaxDepth());
        result.put("maxTotalAgents", collabRun.getMaxTotalAgents());
        result.put("createdAt", collabRun.getCreatedAt());
        result.put("completedAt", collabRun.getCompletedAt());
        result.put("members", members);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{collabRunId}/messages")
    public ResponseEntity<?> getMessages(@PathVariable String collabRunId) {
        CollabRunEntity collabRun = collabRunService.getRun(collabRunId);
        if (collabRun == null) {
            return ResponseEntity.notFound().build();
        }

        // Collect all session IDs in this collab run
        List<SessionEntity> collabSessions = sessionService.listByCollabRunId(collabRunId);
        List<Map<String, Object>> messages = new ArrayList<>();

        for (SessionEntity session : collabSessions) {
            List<SubAgentPendingResultEntity> pending =
                    pendingResultRepository.findByTargetSessionIdOrderBySeqNoAsc(session.getId());
            for (SubAgentPendingResultEntity p : pending) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("id", p.getId());
                msg.put("targetSessionId", p.getTargetSessionId());
                msg.put("messageId", p.getMessageId());
                msg.put("seqNo", p.getSeqNo());
                msg.put("payload", p.getPayload());
                msg.put("createdAt", p.getCreatedAt());
                messages.add(msg);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collabRunId", collabRunId);
        result.put("messageCount", messages.size());
        result.put("messages", messages);

        return ResponseEntity.ok(result);
    }

    /**
     * Get all trace spans across all sessions in a collab run, sorted by startTime ascending.
     */
    @GetMapping("/{collabRunId}/traces")
    public ResponseEntity<?> getCollabTraces(@PathVariable String collabRunId) {
        CollabRunEntity collabRun = collabRunService.getRun(collabRunId);
        if (collabRun == null) {
            return ResponseEntity.notFound().build();
        }

        List<SessionEntity> sessions = sessionService.listByCollabRunId(collabRunId);
        if (sessions.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("collabRunId", collabRunId);
            empty.put("spans", List.of());
            return ResponseEntity.ok(empty);
        }

        List<String> sessionIds = sessions.stream().map(SessionEntity::getId).toList();
        List<TraceSpanEntity> spans = traceSpanRepository.findBySessionIdInOrderByStartTimeAsc(sessionIds);

        // Build session metadata lookup
        Map<String, Map<String, Object>> sessionMeta = new LinkedHashMap<>();
        Map<String, String> rosterMembers = agentRoster.listMembers(collabRunId);
        Map<String, String> sessionToHandle = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rosterMembers.entrySet()) {
            sessionToHandle.put(entry.getValue(), entry.getKey());
        }

        for (SessionEntity session : sessions) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sessionId", session.getId());
            meta.put("handle", sessionToHandle.getOrDefault(session.getId(), null));
            meta.put("agentId", session.getAgentId());
            try {
                meta.put("agentName", agentService.getAgent(session.getAgentId()).getName());
            } catch (Exception e) {
                meta.put("agentName", null);
            }
            meta.put("depth", session.getDepth());
            sessionMeta.put(session.getId(), meta);
        }

        List<Map<String, Object>> spanList = new ArrayList<>();
        for (TraceSpanEntity span : spans) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", span.getId());
            s.put("sessionId", span.getSessionId());
            Map<String, Object> meta = sessionMeta.get(span.getSessionId());
            if (meta != null) {
                s.put("handle", meta.get("handle"));
                s.put("agentName", meta.get("agentName"));
            }
            s.put("parentSpanId", span.getParentSpanId());
            s.put("spanType", span.getSpanType());
            s.put("name", span.getName());
            s.put("input", span.getInput());
            s.put("output", span.getOutput());
            s.put("startTime", span.getStartTime());
            s.put("endTime", span.getEndTime());
            s.put("durationMs", span.getDurationMs());
            s.put("inputTokens", span.getInputTokens());
            s.put("outputTokens", span.getOutputTokens());
            s.put("modelId", span.getModelId());
            s.put("success", span.isSuccess());
            s.put("error", span.getError());
            spanList.add(s);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collabRunId", collabRunId);
        result.put("sessions", sessionMeta.values());
        result.put("spanCount", spanList.size());
        result.put("spans", spanList);

        return ResponseEntity.ok(result);
    }

    /**
     * Get a summary of a collab run: token counts, tool/LLM/peer message counts, per-member breakdown.
     */
    @GetMapping("/{collabRunId}/summary")
    public ResponseEntity<?> getCollabSummary(@PathVariable String collabRunId) {
        CollabRunEntity collabRun = collabRunService.getRun(collabRunId);
        if (collabRun == null) {
            return ResponseEntity.notFound().build();
        }

        List<SessionEntity> sessions = sessionService.listByCollabRunId(collabRunId);
        List<String> sessionIds = sessions.stream().map(SessionEntity::getId).toList();

        // Query all trace spans for counting
        List<TraceSpanEntity> allSpans = sessionIds.isEmpty()
                ? List.of()
                : traceSpanRepository.findBySessionIdInOrderByStartTimeAsc(sessionIds);

        // Build per-session span counts
        Map<String, int[]> sessionSpanCounts = new LinkedHashMap<>();
        // int[0] = toolCalls, int[1] = llmCalls, int[2] = peerMessages
        for (TraceSpanEntity span : allSpans) {
            int[] counts = sessionSpanCounts.computeIfAbsent(span.getSessionId(), k -> new int[3]);
            switch (span.getSpanType()) {
                case "TOOL_CALL" -> counts[0]++;
                case "LLM_CALL" -> counts[1]++;
                case "PEER_MESSAGE" -> counts[2]++;
            }
        }

        // Roster for handle lookup
        Map<String, String> rosterMembers = agentRoster.listMembers(collabRunId);
        Map<String, String> sessionToHandle = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rosterMembers.entrySet()) {
            sessionToHandle.put(entry.getValue(), entry.getKey());
        }

        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        int totalToolCalls = 0;
        int totalLlmCalls = 0;
        int totalPeerMessages = 0;
        List<Map<String, Object>> memberList = new ArrayList<>();

        for (SessionEntity session : sessions) {
            totalInputTokens += session.getTotalInputTokens();
            totalOutputTokens += session.getTotalOutputTokens();

            int[] counts = sessionSpanCounts.getOrDefault(session.getId(), new int[3]);
            totalToolCalls += counts[0];
            totalLlmCalls += counts[1];
            totalPeerMessages += counts[2];

            Map<String, Object> member = new LinkedHashMap<>();
            member.put("handle", sessionToHandle.getOrDefault(session.getId(), null));
            member.put("sessionId", session.getId());
            try {
                member.put("agentName", agentService.getAgent(session.getAgentId()).getName());
            } catch (Exception e) {
                member.put("agentName", null);
            }
            member.put("inputTokens", session.getTotalInputTokens());
            member.put("outputTokens", session.getTotalOutputTokens());
            member.put("toolCalls", counts[0]);
            member.put("llmCalls", counts[1]);
            member.put("peerMessages", counts[2]);
            member.put("status", session.getRuntimeStatus());

            // Per-member duration: from session createdAt to completedAt (or now)
            if (session.getCreatedAt() != null) {
                java.time.Instant sessionStart = session.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
                java.time.Instant sessionEnd = session.getCompletedAt() != null
                        ? session.getCompletedAt() : java.time.Instant.now();
                member.put("durationMs", Duration.between(sessionStart, sessionEnd).toMillis());
            } else {
                member.put("durationMs", 0);
            }
            memberList.add(member);
        }

        // Collab run duration
        long durationMs = 0;
        if (collabRun.getCreatedAt() != null) {
            java.time.Instant end = collabRun.getCompletedAt() != null
                    ? collabRun.getCompletedAt() : java.time.Instant.now();
            durationMs = Duration.between(collabRun.getCreatedAt(), end).toMillis();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collabRunId", collabRun.getCollabRunId());
        result.put("status", collabRun.getStatus());
        result.put("memberCount", sessions.size());
        result.put("totalInputTokens", totalInputTokens);
        result.put("totalOutputTokens", totalOutputTokens);
        result.put("totalToolCalls", totalToolCalls);
        result.put("totalLlmCalls", totalLlmCalls);
        result.put("totalPeerMessages", totalPeerMessages);
        result.put("durationMs", durationMs);
        result.put("members", memberList);

        return ResponseEntity.ok(result);
    }
}
