package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.MemoryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-tests {@link MemoryRepository#findByFts} against a real PostgreSQL container after
 * V29 ran. The intent is twofold:
 *
 * <ul>
 *   <li>Confirm V29's column additions / constraints don't accidentally break the
 *       generated {@code search_vector} STORED column from V7.</li>
 *   <li>Give PR-2 (which adds {@code AND status='ACTIVE'}) a baseline to diff against —
 *       this test should keep passing without modification once that filter lands, since
 *       all rows here are status='ACTIVE' by V29 default.</li>
 * </ul>
 *
 * <p>{@code findByVector} is intentionally NOT covered: the test container is
 * {@code postgres:16-alpine} without the pgvector extension, so V7's embedding column is
 * skipped at migration time (DO/EXCEPTION block). PR-2 should add a vector IT only after
 * the test container image gains pgvector support.
 */
@DisplayName("MemoryRepository FTS smoke test (post-V29)")
class MemoryRepositoryFtsIT extends AbstractPostgresIT {

    @Autowired
    private MemoryRepository memoryRepository;

    @BeforeEach
    void cleanUp() {
        memoryRepository.deleteAll();
    }

    @Test
    @DisplayName("findByFts returns matching rows ranked by relevance")
    void findByFts_returnsMatchingRows() {
        memoryRepository.save(memory(1L, "knowledge", "Spring Boot caching tips", "Use @Cacheable carefully"));
        memoryRepository.save(memory(1L, "knowledge", "Hibernate quirks", "L2 cache on lazy collections"));
        memoryRepository.save(memory(1L, "knowledge", "unrelated note", "totally different topic"));

        List<Object[]> results = memoryRepository.findByFts(1L, "Spring Boot caching", 5);

        assertThat(results).isNotEmpty();
        // First row should reference the matching memory.
        String firstTitle = (String) results.get(0)[2];
        assertThat(firstTitle).containsIgnoringCase("Spring Boot");
    }

    @Test
    @DisplayName("findByFts returns empty list when no row matches the query")
    void findByFts_noMatch_returnsEmpty() {
        memoryRepository.save(memory(1L, "knowledge", "Spring Boot tips", "details"));

        List<Object[]> results = memoryRepository.findByFts(1L, "completelyMadeUpToken", 5);

        assertThat(results).isEmpty();
    }

    private static MemoryEntity memory(Long userId, String type, String title, String content) {
        MemoryEntity m = new MemoryEntity();
        m.setUserId(userId);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        return m;
    }
}
