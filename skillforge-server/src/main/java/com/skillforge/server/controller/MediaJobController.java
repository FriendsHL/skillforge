package com.skillforge.server.controller;

import com.skillforge.server.entity.MediaGenerationJobEntity;
import com.skillforge.server.media.MediaJobResponse;
import com.skillforge.server.media.MediaJobStore;
import com.skillforge.server.media.MediaGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/media/jobs")
public class MediaJobController {
    private final MediaJobStore store;
    private final MediaGenerationService generationService;
    public MediaJobController(MediaJobStore store, MediaGenerationService generationService) {
        this.store = store;
        this.generationService = generationService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<MediaJobResponse> get(@PathVariable String id, @RequestParam Long userId) {
        MediaGenerationJobEntity row;
        try { row = store.require(id); } catch (RuntimeException e) { return ResponseEntity.notFound().build(); }
        if (!row.getUserId().equals(userId)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(MediaJobResponse.from(row));
    }

    @GetMapping
    public List<MediaJobResponse> list(@RequestParam String sessionId, @RequestParam Long userId) {
        return store.listSession(sessionId, userId).stream().map(MediaJobResponse::from).toList();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<MediaJobResponse> cancel(@PathVariable String id, @RequestParam Long userId) {
        MediaGenerationJobEntity row;
        try { row = store.require(id); } catch (RuntimeException e) { return ResponseEntity.notFound().build(); }
        if (!row.getUserId().equals(userId)) return ResponseEntity.notFound().build();
        try { return ResponseEntity.ok(MediaJobResponse.from(generationService.cancel(id))); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(MediaJobResponse.from(store.require(id))); }
    }
}
