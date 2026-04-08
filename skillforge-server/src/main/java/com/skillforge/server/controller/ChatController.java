package com.skillforge.server.controller;

import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.model.Message;
import com.skillforge.server.dto.ChatRequest;
import com.skillforge.server.dto.CreateSessionRequest;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import com.skillforge.server.subagent.SubAgentRegistry.SubAgentRun;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final SessionService sessionService;
    private final PendingAskRegistry pendingAskRegistry;
    private final SubAgentRegistry subAgentRegistry;

    public ChatController(ChatService chatService,
                          SessionService sessionService,
                          PendingAskRegistry pendingAskRegistry,
                          SubAgentRegistry subAgentRegistry) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.pendingAskRegistry = pendingAskRegistry;
        this.subAgentRegistry = subAgentRegistry;
    }

    /**
     * 发送用户消息。立即返回 202,实际结果通过 WebSocket 推送。
     */
    @PostMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> sendMessage(@PathVariable String sessionId,
                                                            @RequestBody ChatRequest request) {
        try {
            chatService.chatAsync(sessionId, request.getMessage(), request.getUserId());
        } catch (RejectedExecutionException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Server is busy, please try again later");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("status", "accepted");
        return ResponseEntity.accepted().body(body);
    }

    /**
     * 前端回答 ask_user 问题。
     */
    @PostMapping("/{sessionId}/answer")
    public ResponseEntity<Map<String, Object>> answer(@PathVariable String sessionId,
                                                       @RequestBody Map<String, String> body) {
        String askId = body.get("askId");
        String answer = body.get("answer");
        if (askId == null || answer == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "askId and answer required"));
        }
        boolean ok = pendingAskRegistry.complete(askId, answer);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "ask has expired or does not exist"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 切换某个 Session 当前的 executionMode(不影响 Agent 默认)。
     */
    @PatchMapping("/sessions/{sessionId}/mode")
    public ResponseEntity<SessionEntity> setSessionMode(@PathVariable String sessionId,
                                                         @RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        if (mode == null || (!mode.equals("ask") && !mode.equals("auto"))) {
            return ResponseEntity.badRequest().build();
        }
        SessionEntity session = sessionService.getSession(sessionId);
        session.setExecutionMode(mode);
        return ResponseEntity.ok(sessionService.saveSession(session));
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionEntity> createSession(@RequestBody CreateSessionRequest request) {
        SessionEntity session = sessionService.createSession(request.getUserId(), request.getAgentId());
        return ResponseEntity.ok(session);
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionEntity>> listSessions(@RequestParam Long userId) {
        List<SessionEntity> sessions = sessionService.listUserSessions(userId);
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<SessionEntity> getSession(@PathVariable String id) {
        return ResponseEntity.ok(sessionService.getSession(id));
    }

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<Message>> getSessionMessages(@PathVariable String id) {
        List<Message> messages = sessionService.getSessionMessages(id);
        return ResponseEntity.ok(messages);
    }

    /**
     * 列出某个父 session 下的所有子 session(SubAgent 派发的)。
     */
    @GetMapping("/sessions/{id}/children")
    public ResponseEntity<List<Map<String, Object>>> getChildSessions(@PathVariable String id) {
        // 验证父 session 存在(与 getSession 的 404 行为一致,getSession 会抛异常)
        try {
            sessionService.getSession(id);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
        List<SessionEntity> children = sessionService.listChildSessions(id);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SessionEntity s : children) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("agentId", s.getAgentId());
            m.put("title", s.getTitle());
            m.put("depth", s.getDepth());
            m.put("runtimeStatus", s.getRuntimeStatus());
            m.put("messageCount", s.getMessageCount());
            m.put("createdAt", s.getCreatedAt());
            m.put("updatedAt", s.getUpdatedAt());
            m.put("subAgentRunId", s.getSubAgentRunId());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 列出某个父 session 派发过的所有 SubAgent run(来自 SubAgentRegistry)。
     */
    @GetMapping("/sessions/{id}/subagent-runs")
    public ResponseEntity<List<Map<String, Object>>> getSubAgentRuns(@PathVariable String id) {
        try {
            sessionService.getSession(id);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
        List<SubAgentRun> runs = subAgentRegistry.listRunsForParent(id);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SubAgentRun r : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.runId);
            m.put("childSessionId", r.childSessionId);
            m.put("childAgentId", r.childAgentId);
            m.put("childAgentName", r.childAgentName);
            m.put("task", r.task);
            m.put("status", r.status);
            m.put("finalMessage", r.finalMessage);
            m.put("spawnedAt", r.spawnedAt);
            m.put("completedAt", r.completedAt);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }
}
