package com.skillforge.server.controller;

import com.skillforge.core.model.Message;
import com.skillforge.server.dto.ChatRequest;
import com.skillforge.server.dto.ChatResponse;
import com.skillforge.server.dto.CreateSessionRequest;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final SessionService sessionService;

    public ChatController(ChatService chatService, SessionService sessionService) {
        this.chatService = chatService;
        this.sessionService = sessionService;
    }

    @PostMapping("/{sessionId}")
    public ResponseEntity<ChatResponse> sendMessage(@PathVariable String sessionId,
                                                     @RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(sessionId, request.getMessage(), request.getUserId());
        return ResponseEntity.ok(response);
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

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<Message>> getSessionMessages(@PathVariable String id) {
        List<Message> messages = sessionService.getSessionMessages(id);
        return ResponseEntity.ok(messages);
    }
}
