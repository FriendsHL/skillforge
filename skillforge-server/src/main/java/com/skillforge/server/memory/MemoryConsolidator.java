package com.skillforge.server.memory;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    private final MemoryRepository memoryRepository;

    public MemoryConsolidator(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /**
     * Consolidate memories for a user: deduplicate by title and mark stale entries.
     */
    public void consolidate(Long userId) {
        List<MemoryEntity> all = memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId);

        // 1. Deduplicate: same title → keep the newest, delete the rest
        Map<String, List<MemoryEntity>> byTitle = all.stream()
                .filter(m -> m.getTitle() != null)
                .collect(Collectors.groupingBy(MemoryEntity::getTitle));

        for (Map.Entry<String, List<MemoryEntity>> entry : byTitle.entrySet()) {
            List<MemoryEntity> dups = entry.getValue();
            if (dups.size() > 1) {
                // List is already ordered by updatedAt desc, so first is newest
                for (int i = 1; i < dups.size(); i++) {
                    memoryRepository.delete(dups.get(i));
                }
                log.info("Merged {} duplicate memories with title '{}'", dups.size() - 1, entry.getKey());
            }
        }

        // 2. Mark stale: 30 days without recall + recallCount < 3
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        for (MemoryEntity m : all) {
            if (m.getRecallCount() < 3
                    && (m.getLastRecalledAt() == null || m.getLastRecalledAt().isBefore(thirtyDaysAgo))
                    && m.getCreatedAt() != null
                    && m.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().isBefore(thirtyDaysAgo)) {
                String tags = m.getTags() != null ? m.getTags() : "";
                if (!tags.contains("stale")) {
                    m.setTags(tags.isEmpty() ? "stale" : tags + ",stale");
                    memoryRepository.save(m);
                    log.info("Marked memory as stale: id={}, title={}", m.getId(), m.getTitle());
                }
            }
        }
    }
}
