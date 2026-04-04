package com.skillforge.server.service;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
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
        List<MemoryEntity> memories = memoryRepository.findByUserId(userId);
        if (memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## User Memories\n\n");

        for (MemoryEntity m : memories) {
            sb.append("- [").append(m.getType()).append("] ");
            sb.append("**").append(m.getTitle()).append("**: ");
            sb.append(m.getContent()).append("\n");
        }

        return sb.toString();
    }
}
