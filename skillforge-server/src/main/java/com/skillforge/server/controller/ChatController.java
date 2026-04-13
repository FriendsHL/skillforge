package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.model.Message;
import com.skillforge.server.dto.ChatRequest;
import com.skillforge.server.dto.CreateSessionRequest;
import com.skillforge.server.entity.CompactionEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.dto.SessionReplayDto;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.ReplayService;
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
    private final CancellationRegistry cancellationRegistry;
    private final CompactionService compactionService;
    private final ReplayService replayService;

    public ChatController(ChatService chatService,
                          SessionService sessionService,
                          PendingAskRegistry pendingAskRegistry,
                          SubAgentRegistry subAgentRegistry,
                          CancellationRegistry cancellationRegistry,
                          CompactionService compactionService,
                          ReplayService replayService) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.pendingAskRegistry = pendingAskRegistry;
        this.subAgentRegistry = subAgentRegistry;
        this.cancellationRegistry = cancellationRegistry;
        this.compactionService = compactionService;
        this.replayService = replayService;
    }

    /**
     * 内部: 校验 session 存在 + 属于 userId。
     * 返回状态码:
     *   - 200 OK + SessionEntity : session 存在且归属匹配
     *   - 400 Bad Request        : userId 缺失
     *   - 403 Forbidden          : session 存在但归属不匹配
     *   - 404 Not Found          : session 不存在
     */
    private ResponseEntity<SessionEntity> requireOwnedSession(String sessionId, Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        SessionEntity session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
        if (session.getUserId() == null || !session.getUserId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(session);
    }

    /**
     * 发送用户消息。立即返回 202,实际结果通过 WebSocket 推送。
     */
    @PostMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> sendMessage(@PathVariable String sessionId,
                                                            @RequestBody ChatRequest request) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, request.getUserId());
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
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
     * 取消正在运行的 loop。200 已取消, 409 当前无 loop 运行。
     */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelChat(@PathVariable String sessionId,
                                                           @RequestParam Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        boolean ok = cancellationRegistry.cancel(sessionId);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "No running loop for this session"));
        }
        return ResponseEntity.ok(Map.of("status", "cancelling"));
    }

    /**
     * 前端回答 ask_user 问题。
     */
    @PostMapping("/{sessionId}/answer")
    public ResponseEntity<Map<String, Object>> answer(@PathVariable String sessionId,
                                                       @RequestBody Map<String, Object> body) {
        Object userIdObj = body.get("userId");
        Long userId = null;
        if (userIdObj instanceof Number) {
            userId = ((Number) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
            try { userId = Long.parseLong((String) userIdObj); } catch (NumberFormatException ignored) {}
        }
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        Object askIdObj = body.get("askId");
        Object answerObj = body.get("answer");
        String askId = askIdObj == null ? null : askIdObj.toString();
        String answer = answerObj == null ? null : answerObj.toString();
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
                                                         @RequestParam(required = false) Long userId,
                                                         @RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        if (mode == null || (!mode.equals("ask") && !mode.equals("auto"))) {
            return ResponseEntity.badRequest().build();
        }
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        SessionEntity session = check.getBody();
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
    public ResponseEntity<SessionEntity> getSession(@PathVariable String id,
                                                     @RequestParam(required = false) Long userId) {
        return requireOwnedSession(id, userId);
    }

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<Message>> getSessionMessages(@PathVariable String id,
                                                             @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        List<Message> messages = sessionService.getSessionMessages(id);
        return ResponseEntity.ok(messages);
    }

    /**
     * 列出某个父 session 下的所有子 session(SubAgent 派发的)。
     */
    @GetMapping("/sessions/{id}/children")
    public ResponseEntity<List<Map<String, Object>>> getChildSessions(@PathVariable String id,
                                                                       @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
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
     * 用户主动触发全量压缩 (C1 user-manual)。
     * Body: {"level": "full", "reason": "optional"}。
     * 409 当 session runtimeStatus=running。
     */
    @PostMapping("/sessions/{id}/compact")
    public ResponseEntity<?> compactSession(@PathVariable String id,
                                             @RequestParam Long userId,
                                             @RequestBody Map<String, Object> body) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        String level = body.get("level") != null ? body.get("level").toString() : "full";
        if (!"full".equalsIgnoreCase(level)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only level=full is supported for manual compaction"));
        }
        String reason = body.get("reason") != null ? body.get("reason").toString() : "user manual";
        try {
            CompactionEventEntity event = compactionService.compact(id, "full", "user-manual", reason);
            return ResponseEntity.ok(event);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出该 session 的压缩历史事件, 按 id 降序 (最新在前)。
     */
    @GetMapping("/sessions/{id}/compactions")
    public ResponseEntity<List<CompactionEventEntity>> listCompactions(@PathVariable String id,
                                                                        @RequestParam Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        return ResponseEntity.ok(compactionService.listEvents(id));
    }

    /**
     * Session Replay：返回结构化的 turn → iteration → tool call 时间线。
     */
    @GetMapping("/sessions/{id}/replay")
    public ResponseEntity<SessionReplayDto> getSessionReplay(@PathVariable String id,
                                                              @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        SessionReplayDto replay = replayService.buildReplay(id);
        return ResponseEntity.ok(replay);
    }

    /**
     * 列出某个父 session 派发过的所有 SubAgent run(来自 SubAgentRegistry)。
     */
    @GetMapping("/sessions/{id}/subagent-runs")
    public ResponseEntity<List<Map<String, Object>>> getSubAgentRuns(@PathVariable String id,
                                                                      @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
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
