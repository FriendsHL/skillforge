package com.skillforge.server.mobile;

import com.skillforge.server.entity.MediaGenerationJobEntity;
import com.skillforge.server.media.MediaJobResponse;
import com.skillforge.server.media.MediaJobStore;
import com.skillforge.server.media.MediaGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping("/api/mobile/client/sessions/{sessionId}/media/jobs")
public class MobileMediaJobController {
    private final MediaJobStore store;
    private final MediaGenerationService generationService;
    public MobileMediaJobController(MediaJobStore store, MediaGenerationService generationService) {
        this.store = store; this.generationService = generationService;
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<MediaJobResponse> get(@PathVariable String sessionId, @PathVariable String jobId,
                                                HttpServletRequest request) {
        MobileDevicePrincipal principal = principal(request);
        if (principal == null || !principal.scopes().contains("chat:read")) return ResponseEntity.status(403).build();
        MediaGenerationJobEntity row;
        try { row = store.require(jobId); } catch (RuntimeException e) { return ResponseEntity.notFound().build(); }
        if (!row.getSessionId().equals(sessionId) || !row.getUserId().equals(principal.userId())) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(MediaJobResponse.from(row));
    }

    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<MediaJobResponse> cancel(@PathVariable String sessionId, @PathVariable String jobId,
                                                   HttpServletRequest request) {
        MobileDevicePrincipal principal = principal(request);
        if (principal == null || !principal.scopes().contains("chat:write")) return ResponseEntity.status(403).build();
        MediaGenerationJobEntity row;
        try { row = store.require(jobId); } catch (RuntimeException e) { return ResponseEntity.notFound().build(); }
        if (!row.getSessionId().equals(sessionId) || !row.getUserId().equals(principal.userId())) return ResponseEntity.notFound().build();
        try { return ResponseEntity.ok(MediaJobResponse.from(generationService.cancel(jobId))); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(MediaJobResponse.from(store.require(jobId))); }
    }

    private static MobileDevicePrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        return value instanceof MobileDevicePrincipal principal ? principal : null;
    }
}
