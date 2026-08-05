package com.skillforge.server.controller;

import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.service.SessionTaskException;
import com.skillforge.server.service.SessionTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/chat/sessions")
public class SessionTaskController {
    private final SessionTaskService taskService;
    public SessionTaskController(SessionTaskService taskService) { this.taskService = taskService; }

    @GetMapping("/{sessionId}/tasks")
    public SessionTaskSnapshotResponse list(@PathVariable String sessionId,
                                            @RequestParam Long userId) {
        return taskService.snapshot(sessionId, userId, true);
    }

    @ExceptionHandler(SessionTaskException.class)
    public ResponseEntity<Map<String, Object>> taskError(SessionTaskException e) {
        HttpStatus status = switch (e.getCode()) {
            case "SESSION_NOT_FOUND", "TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TASK_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "code", e.getCode(), "error", e.getMessage(), "retryable", e.isRetryable()));
    }
}
