package com.skillforge.server.memory.context;

import com.skillforge.core.engine.MemoryInjection;
import com.skillforge.server.service.MemoryService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class MemoryContextProvider {

    private final MemoryService memoryService;

    public MemoryContextProvider(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public MemoryContextSnapshot load(Long userId, String taskContext) {
        MemoryInjection injection = memoryService.previewMemoryInjectionForPrompt(userId, taskContext);
        String rendered = injection != null && injection.text() != null ? injection.text() : "";
        Set<Long> ids = new LinkedHashSet<>();
        if (injection != null && injection.injectedIds() != null) {
            ids.addAll(injection.injectedIds());
        }
        return new MemoryContextSnapshot(
                userId,
                taskContext,
                rendered,
                ids,
                sha256(rendered),
                injection != null ? injection.provenance() : java.util.List.of());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
