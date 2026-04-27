package com.skillforge.server.memory;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.repository.MemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@Component
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    private final MemoryRepository memoryRepository;
    private MemoryProperties memoryProperties = new MemoryProperties();

    public MemoryConsolidator(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Autowired(required = false)
    public void setMemoryProperties(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties != null ? memoryProperties : new MemoryProperties();
    }

    /**
     * Memory v2 PR-5: status lifecycle sweep + capacity enforcement.
     */
    public void consolidate(Long userId) {
        if (memoryRepository == null || userId == null) {
            return;
        }
        List<MemoryEntity> all = memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (all.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (MemoryEntity memory : all) {
            if (isExpiredArchived(memory, now)) {
                memoryRepository.delete(memory);
                log.info("Deleted expired archived memory: id={}, title={}", memory.getId(), memory.getTitle());
                continue;
            }
            memory.setLastScore(scoreMemory(memory, now));
            memory.setLastScoredAt(now);
            transitionStatus(memory, now);
            memoryRepository.save(memory);
        }
        enforceCapacity(all);
    }

    private void enforceCapacity(List<MemoryEntity> all) {
        int cap = memoryProperties.getEviction().getMaxActivePerUser();
        List<MemoryEntity> active = all.stream()
                .filter(memory -> "ACTIVE".equals(memory.getStatus()))
                .sorted(Comparator
                        .comparing(MemoryEntity::getLastScore, Comparator.nullsLast(Double::compareTo))
                        .thenComparing(MemoryEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (active.size() <= cap) {
            return;
        }
        int overflow = active.size() - cap;
        for (int i = 0; i < overflow; i++) {
            MemoryEntity demoted = active.get(i);
            demoted.setStatus("STALE");
            demoted.setArchivedAt(null);
            memoryRepository.save(demoted);
            log.info("Demoted memory due to capacity pressure: id={}, title={}, score={}",
                    demoted.getId(), demoted.getTitle(), demoted.getLastScore());
        }
    }

    private void transitionStatus(MemoryEntity memory, Instant now) {
        long ageDays = ageDays(memory, now);
        if (!"ARCHIVED".equals(memory.getStatus())
                && ageDays >= memoryProperties.getEviction().getArchiveAfterDays()) {
            memory.setStatus("ARCHIVED");
            if (memory.getArchivedAt() == null) {
                memory.setArchivedAt(now);
            }
            return;
        }
        if ("ACTIVE".equals(memory.getStatus())
                && ageDays >= memoryProperties.getEviction().getStaleAfterDays()
                && memory.getRecallCount() < 3) {
            memory.setStatus("STALE");
            memory.setArchivedAt(null);
        }
    }

    private boolean isExpiredArchived(MemoryEntity memory, Instant now) {
        if (!"ARCHIVED".equals(memory.getStatus()) || memory.getArchivedAt() == null) {
            return false;
        }
        return memory.getArchivedAt()
                .isBefore(now.minus(memoryProperties.getEviction().getDeleteAfterDays(), ChronoUnit.DAYS));
    }

    private static long ageDays(MemoryEntity memory, Instant now) {
        Instant anchor = memory.getLastRecalledAt();
        if (anchor == null && memory.getCreatedAt() != null) {
            anchor = memory.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (anchor == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(anchor, now);
        return Math.max(days, 0);
    }

    private static double scoreMemory(MemoryEntity memory, Instant now) {
        double importanceScore = switch (memory.getImportance() == null ? "medium" : memory.getImportance()) {
            case "high" -> 1.0;
            case "low" -> 0.3;
            default -> 0.6;
        };
        long ageDays = ageDays(memory, now);
        double recencyScore = Math.exp(-ageDays / 30.0);
        double recallScore = Math.log(1 + Math.max(memory.getRecallCount(), 0)) / Math.log(11);
        double freshnessScore = 0.5 + 0.5 * recencyScore;
        double usageScore = 0.3 + 0.7 * recallScore;
        return 0.45 * importanceScore
                + 0.35 * freshnessScore
                + 0.20 * usageScore;
    }
}
