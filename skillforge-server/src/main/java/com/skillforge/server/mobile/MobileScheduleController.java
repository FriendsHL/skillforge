package com.skillforge.server.mobile;

import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.service.ScheduledTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/mobile/client/schedules")
public class MobileScheduleController {

    private static final String SCOPE_SCHEDULE_READ = "schedule:read";
    private static final String SCOPE_SCHEDULE_WRITE = "schedule:write";
    private static final int DEFAULT_RUN_LIMIT = 20;
    private static final int MAX_RUN_LIMIT = 50;

    private final ScheduledTaskService scheduledTaskService;

    public MobileScheduleController(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    @GetMapping
    public ResponseEntity<List<MobileScheduledTaskResponse>> list(HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipalWithScope(request, SCOPE_SCHEDULE_READ);
        return ResponseEntity.ok(scheduledTaskService.listForUser(principal.userId()).stream()
                .map(MobileScheduledTaskResponse::from)
                .toList());
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<MobileScheduledTaskRunResponse>> listRuns(
            @PathVariable Long id,
            @RequestParam(defaultValue = "" + DEFAULT_RUN_LIMIT) int limit,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipalWithScope(request, SCOPE_SCHEDULE_READ);
        int safeLimit = Math.max(1, Math.min(limit, MAX_RUN_LIMIT));
        return ResponseEntity.ok(scheduledTaskService.listRuns(id, principal.userId(), safeLimit, 0).stream()
                .map(MobileScheduledTaskRunResponse::from)
                .toList());
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<Map<String, Object>> trigger(
            @PathVariable Long id,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipalWithScope(request, SCOPE_SCHEDULE_WRITE);
        ScheduledTaskEntity task = scheduledTaskService.triggerNow(id, principal.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "taskId", task.getId(),
                "status", "trigger_requested"));
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<MobileScheduledTaskResponse> updateEnabled(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipalWithScope(request, SCOPE_SCHEDULE_WRITE);
        if (body == null || body.size() != 1 || !body.containsKey("enabled")
                || !(body.get("enabled") instanceof Boolean enabled)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "body must contain only boolean field 'enabled'");
        }
        ScheduledTaskRequest patch = new ScheduledTaskRequest();
        patch.setEnabled(enabled);
        ScheduledTaskEntity updated = scheduledTaskService.update(id, principal.userId(), patch);
        return ResponseEntity.ok(MobileScheduledTaskResponse.from(updated));
    }

    private MobileDevicePrincipal requirePrincipalWithScope(HttpServletRequest request, String scope) {
        Object principal = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof MobileDevicePrincipal mobilePrincipal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Set<String> scopes = mobilePrincipal.scopes() != null ? mobilePrincipal.scopes() : Set.of();
        if (!scopes.contains(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return mobilePrincipal;
    }
}
