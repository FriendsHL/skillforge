package com.skillforge.server.controller;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.memory.SessionDigestExtractor;
import com.skillforge.server.service.MemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;
    private final SessionDigestExtractor sessionDigestExtractor;

    public MemoryController(MemoryService memoryService, SessionDigestExtractor sessionDigestExtractor) {
        this.memoryService = memoryService;
        this.sessionDigestExtractor = sessionDigestExtractor;
    }

    @GetMapping
    public ResponseEntity<List<MemoryEntity>> listMemories(
            @RequestParam Long userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        List<MemoryEntity> memories = memoryService.listMemories(userId, type, status);
        return ResponseEntity.ok(memories);
    }

    @GetMapping("/search")
    public ResponseEntity<List<MemoryEntity>> searchMemories(
            @RequestParam Long userId,
            @RequestParam String keyword) {
        List<MemoryEntity> memories = memoryService.searchMemories(userId, keyword);
        return ResponseEntity.ok(memories);
    }

    @PostMapping
    public ResponseEntity<MemoryEntity> createMemory(@RequestBody MemoryEntity memory) {
        MemoryEntity created = memoryService.createMemory(memory);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MemoryEntity> updateMemory(@PathVariable Long id,
                                                      @RequestBody MemoryEntity memory) {
        MemoryEntity updated = memoryService.updateMemory(id, memory);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMemory(@PathVariable Long id) {
        memoryService.deleteMemory(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable Long id,
                                                            @RequestParam Long userId,
                                                            @RequestBody Map<String, Object> body) {
        int changed = memoryService.updateStatus(id, userId, body != null ? (String) body.get("status") : null);
        return ResponseEntity.ok(Map.of("updated", changed));
    }

    @PostMapping("/batch-archive")
    public ResponseEntity<Map<String, Object>> batchArchive(@RequestParam Long userId,
                                                            @RequestBody Map<String, List<Long>> body) {
        int changed = memoryService.batchArchive(body != null ? body.get("ids") : null, userId);
        return ResponseEntity.ok(Map.of("updated", changed));
    }

    @PostMapping("/batch-restore")
    public ResponseEntity<Map<String, Object>> batchRestore(@RequestParam Long userId,
                                                            @RequestBody Map<String, List<Long>> body) {
        int changed = memoryService.batchRestore(body != null ? body.get("ids") : null, userId);
        return ResponseEntity.ok(Map.of("updated", changed));
    }

    @PostMapping("/batch-status")
    public ResponseEntity<Map<String, Object>> batchStatus(@RequestParam Long userId,
                                                           @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = body != null ? (List<Long>) body.get("ids") : null;
        String status = body != null ? (String) body.get("status") : null;
        int changed = memoryService.batchUpdateStatus(ids, userId, status);
        return ResponseEntity.ok(Map.of("updated", changed));
    }

    @DeleteMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchDelete(@RequestParam Long userId,
                                                           @RequestBody Map<String, List<Long>> body) {
        int changed = memoryService.batchDelete(body != null ? body.get("ids") : null, userId);
        return ResponseEntity.ok(Map.of("deleted", changed));
    }

    @GetMapping("/stats")
    public ResponseEntity<MemoryService.MemoryStats> stats(@RequestParam Long userId) {
        return ResponseEntity.ok(memoryService.getStats(userId));
    }

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollbackExtractionBatch(
            @RequestParam Long userId,
            @RequestParam String batchId) {
        MemoryService.RollbackResult result = memoryService.rollbackExtractionBatch(batchId, userId);
        return ResponseEntity.ok(Map.of(
                "status", "rolled_back",
                "batchId", batchId,
                "restored", result.restored(),
                "deleted", result.deleted()));
    }

    /**
     * Manually trigger memory extraction for a specific session.
     * Intended for development/testing — fires the same async extraction that
     * normally runs automatically when a session loop completes.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshSession(@RequestParam String sessionId) {
        sessionDigestExtractor.triggerExtractionAsync(sessionId);
        return ResponseEntity.ok(Map.of("status", "triggered", "sessionId", sessionId));
    }
}
