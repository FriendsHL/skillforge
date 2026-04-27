package com.skillforge.server.tool;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Memory v2 (PR-2) end-to-end status filter test against a real PostgreSQL container.
 *
 * <p>Why not H2: {@code findByFts} relies on {@code tsvector} + {@code plainto_tsquery('simple', ...)}
 * which is PostgreSQL-only syntax — H2 fails at SQL parse time. {@code findByVector} additionally
 * requires the pgvector extension which isn't installed in {@code postgres:16-alpine}, so this
 * test covers only the FTS path (vector path filtering is exercised by mock-based unit test
 * indirectly + Production usage).
 *
 * <p>Setup: {@link AbstractPostgresIT} provides a {@code @DataJpaTest} JPA slice with
 * Testcontainers PG-16, so the {@link MemoryRepository} is autowired but service-layer beans
 * (which we don't need here) are not loaded.
 *
 * <p>Contract verified: STALE memory rows must NOT appear in {@code findByFts} results after
 * the PR-2 {@code AND status='ACTIVE'} filter. ARCHIVED rows similarly excluded.
 */
@DisplayName("MemoryRepository.findByFts status='ACTIVE' filter (Memory v2 PR-2 IT)")
class MemorySearchToolStatusFilterIT extends AbstractPostgresIT {

    @Autowired
    private MemoryRepository memoryRepository;

    @BeforeEach
    void cleanUp() {
        memoryRepository.deleteAll();
    }

    @Test
    @DisplayName("findByFts: STALE memory not returned even when content matches the query")
    void findByFts_excludesStaleRow() {
        // Two rows match "Spring Boot caching"; one ACTIVE (should appear), one STALE (must not).
        memoryRepository.save(memory(1L, "knowledge",
                "Spring Boot caching tips", "Use @Cacheable carefully", "ACTIVE"));
        memoryRepository.save(memory(1L, "knowledge",
                "old Spring Boot caching draft", "Outdated cache notes", "STALE"));

        List<Object[]> rows = memoryRepository.findByFts(1L, "Spring Boot caching", 10);

        assertThat(rows).hasSize(1);
        String returnedTitle = (String) rows.get(0)[2];
        assertThat(returnedTitle).contains("tips"); // the ACTIVE one
    }

    @Test
    @DisplayName("findByFts: ARCHIVED memory not returned even when content matches the query")
    void findByFts_excludesArchivedRow() {
        memoryRepository.save(memory(1L, "knowledge",
                "Spring Boot caching tips", "Use @Cacheable carefully", "ACTIVE"));
        memoryRepository.save(memory(1L, "knowledge",
                "old Spring Boot caching draft", "Outdated cache notes", "ARCHIVED"));

        List<Object[]> rows = memoryRepository.findByFts(1L, "Spring Boot caching", 10);

        assertThat(rows).hasSize(1);
        String returnedTitle = (String) rows.get(0)[2];
        assertThat(returnedTitle).contains("tips");
    }

    @Test
    @DisplayName("findByFts: only ACTIVE rows survive when ALL candidates are non-ACTIVE")
    void findByFts_allNonActive_returnsEmpty() {
        memoryRepository.save(memory(1L, "knowledge",
                "Spring Boot caching draft 1", "old", "STALE"));
        memoryRepository.save(memory(1L, "knowledge",
                "Spring Boot caching draft 2", "old", "ARCHIVED"));

        List<Object[]> rows = memoryRepository.findByFts(1L, "Spring Boot caching", 10);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("findByUserIdAndStatusOrderByUpdatedAtDesc: only ACTIVE rows returned for L0/L1 paths")
    void findByUserIdAndStatus_onlyActive() {
        memoryRepository.save(memory(7L, "preference", "p1", "v1", "ACTIVE"));
        memoryRepository.save(memory(7L, "preference", "p2-stale", "v2", "STALE"));
        memoryRepository.save(memory(7L, "feedback", "f1", "v3", "ACTIVE"));

        List<MemoryEntity> active = memoryRepository
                .findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE");

        assertThat(active).hasSize(2);
        assertThat(active).extracting(MemoryEntity::getStatus).containsOnly("ACTIVE");
    }

    private static MemoryEntity memory(Long userId, String type, String title,
                                       String content, String status) {
        MemoryEntity m = new MemoryEntity();
        m.setUserId(userId);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        m.setStatus(status);
        return m;
    }
}
