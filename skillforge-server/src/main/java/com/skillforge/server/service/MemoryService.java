package com.skillforge.server.service;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private final MemoryRepository memoryRepository;

    public MemoryService(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    public List<MemoryEntity> listMemories(Long userId, String type) {
        if (type != null && !type.isBlank()) {
            return memoryRepository.findByUserIdAndType(userId, type);
        }
        return memoryRepository.findByUserId(userId);
    }

    public List<MemoryEntity> searchMemories(Long userId, String keyword) {
        return memoryRepository.findByUserIdAndContentContaining(userId, keyword);
    }

    public MemoryEntity createMemory(MemoryEntity memory) {
        return memoryRepository.save(memory);
    }

    public MemoryEntity updateMemory(Long id, MemoryEntity memory) {
        MemoryEntity existing = memoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Memory not found: " + id));
        existing.setType(memory.getType());
        existing.setTitle(memory.getTitle());
        existing.setContent(memory.getContent());
        existing.setTags(memory.getTags());
        return memoryRepository.save(existing);
    }

    public void deleteMemory(Long id) {
        memoryRepository.deleteById(id);
    }

    public String getMemoriesForPrompt(Long userId) {
        List<MemoryEntity> all = memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (all.isEmpty()) return "";

        Map<String, List<MemoryEntity>> byType = all.stream()
                .collect(Collectors.groupingBy(m -> m.getType() != null ? m.getType() : "knowledge"));

        StringBuilder sb = new StringBuilder();
        appendTypeMemories(sb, byType.get("preference"), "Preferences", 10);
        appendTypeMemories(sb, byType.get("feedback"), "Feedback", 10);

        List<MemoryEntity> kpr = new ArrayList<>();
        if (byType.containsKey("knowledge")) kpr.addAll(byType.get("knowledge"));
        if (byType.containsKey("project")) kpr.addAll(byType.get("project"));
        if (byType.containsKey("reference")) kpr.addAll(byType.get("reference"));
        kpr.sort(Comparator.comparing(MemoryEntity::getUpdatedAt).reversed());
        appendTypeMemories(sb, kpr.subList(0, Math.min(10, kpr.size())), "Knowledge & Context", 10);

        return sb.toString();
    }

    private static final int MAX_CONTENT_CHARS = 500;
    private static final int MAX_TOTAL_CHARS = 8000;

    private void appendTypeMemories(StringBuilder sb, List<MemoryEntity> memories, String section, int cap) {
        if (memories == null || memories.isEmpty()) return;
        sb.append("### ").append(section).append("\n");
        int count = 0;
        for (MemoryEntity m : memories) {
            if (count >= cap || sb.length() >= MAX_TOTAL_CHARS) break;
            sb.append("- ");
            if (m.getTitle() != null) sb.append("**").append(m.getTitle()).append("**: ");
            String content = m.getContent() != null ? m.getContent() : "";
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS) + "...[truncated]";
            }
            sb.append(content).append("\n");
            count++;
        }
        sb.append("\n");
    }
}
