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
            @RequestParam(required = false) String type) {
        List<MemoryEntity> memories = memoryService.listMemories(userId, type);
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
