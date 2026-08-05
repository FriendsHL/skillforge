package com.skillforge.server.mobile;

import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.service.SessionTaskException;
import com.skillforge.server.service.SessionTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/mobile/client/sessions")
public class MobileSessionTaskController {
    private static final String SCOPE_CHAT_READ = "chat:read";

    private final SessionTaskService taskService;

    public MobileSessionTaskController(SessionTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/{sessionId}/tasks")
    public SessionTaskSnapshotResponse list(@PathVariable String sessionId, HttpServletRequest request) {
        Object value = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof MobileDevicePrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Set<String> scopes = principal.scopes() == null ? Set.of() : principal.scopes();
        if (!scopes.contains(SCOPE_CHAT_READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return taskService.snapshot(sessionId, principal.userId(), true);
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
