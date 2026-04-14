package com.skillforge.server.service;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
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

    public void createMemoryIfNotDuplicate(Long userId, String type, String title, String content, String tags) {
        List<MemoryEntity> existing = memoryRepository.findByUserIdAndTitle(userId, title);
        if (!existing.isEmpty()) {
            MemoryEntity e = existing.get(0);
            e.setContent(content);
            e.setTags(tags);
            memoryRepository.save(e);
            return;
        }
        MemoryEntity entity = new MemoryEntity();
        entity.setUserId(userId);
        entity.setType(type);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setTags(tags);
        memoryRepository.save(entity);
    }

    public List<MemoryEntity> searchWithRanking(Long userId, String query) {
        if (query == null || query.isBlank()) return listMemories(userId, null);

        String[] terms = query.toLowerCase().split("\\s+");
        List<MemoryEntity> all = memoryRepository.findByUserId(userId);

        return all.stream()
                .map(m -> new AbstractMap.SimpleEntry<>(m, calculateScore(m, terms)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<MemoryEntity, Double>comparingByValue().reversed())
                .limit(15)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private double calculateScore(MemoryEntity m, String[] terms) {
        String text = ((m.getTitle() != null ? m.getTitle() : "") + " "
                + (m.getContent() != null ? m.getContent() : "") + " "
                + (m.getTags() != null ? m.getTags() : "")).toLowerCase();

        long matchCount = Arrays.stream(terms).filter(text::contains).count();
        if (matchCount == 0) return 0;

        double daysSinceUpdate = m.getUpdatedAt() != null
                ? Duration.between(m.getUpdatedAt(), LocalDateTime.now()).toDays()
                : 90;
        if (daysSinceUpdate < 0) daysSinceUpdate = 0;
        double recencyBoost = Math.exp(-Math.log(2) / 30.0 * daysSinceUpdate);

        double recallBoost = 1.0 + Math.min(m.getRecallCount(), 10) * 0.1;

        return matchCount * recencyBoost * recallBoost;
    }

    @Transactional
    public String getMemoriesForPrompt(Long userId) {
        List<MemoryEntity> all = memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (all.isEmpty()) return "";

        Map<String, List<MemoryEntity>> byType = all.stream()
                .collect(Collectors.groupingBy(m -> m.getType() != null ? m.getType() : "knowledge"));

        List<Long> injectedIds = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        appendTypeMemories(sb, byType.get("preference"), "Preferences", 10, injectedIds);
        appendTypeMemories(sb, byType.get("feedback"), "Feedback", 10, injectedIds);

        List<MemoryEntity> kpr = new ArrayList<>();
        if (byType.containsKey("knowledge")) kpr.addAll(byType.get("knowledge"));
        if (byType.containsKey("project")) kpr.addAll(byType.get("project"));
        if (byType.containsKey("reference")) kpr.addAll(byType.get("reference"));
        kpr.sort(Comparator.comparing(MemoryEntity::getUpdatedAt).reversed());
        appendTypeMemories(sb, kpr.subList(0, Math.min(10, kpr.size())), "Knowledge & Context", 10, injectedIds);

        // Update recall counts for injected memories
        Instant now = Instant.now();
        for (Long id : injectedIds) {
            memoryRepository.incrementRecallCount(id, now);
        }

        return sb.toString();
    }

    private static final int MAX_CONTENT_CHARS = 500;
    private static final int MAX_TOTAL_CHARS = 8000;

    private void appendTypeMemories(StringBuilder sb, List<MemoryEntity> memories, String section, int cap, List<Long> injectedIds) {
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
            if (m.getId() != null) injectedIds.add(m.getId());
            count++;
        }
        sb.append("\n");
    }
}
