package com.skillforge.server.controller;

import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentPendingResultEntity;
import com.skillforge.server.repository.SubAgentPendingResultRepository;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.CollabRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final SubAgentPendingResultRepository pendingResultRepository;

    public CollabRunController(CollabRunService collabRunService,
                               AgentRoster agentRoster,
                               SessionService sessionService,
                               SubAgentPendingResultRepository pendingResultRepository) {
        this.collabRunService = collabRunService;
        this.agentRoster = agentRoster;
        this.sessionService = sessionService;
        this.pendingResultRepository = pendingResultRepository;
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
}
