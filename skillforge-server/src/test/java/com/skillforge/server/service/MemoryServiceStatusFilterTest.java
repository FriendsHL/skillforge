package com.skillforge.server.service;

import com.skillforge.core.engine.MemoryInjection;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.repository.MemorySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Memory v2 (PR-2): verify that L0 / L1 prompt-injection paths only ever ask the repository for
 * status='ACTIVE' rows. This is a service-layer parameter contract test — the actual SQL filter
 * is exercised end-to-end by {@code MemorySearchToolStatusFilterIT} (Testcontainers PG).
 *
 * <p>Service layer responsibility: pass {@code "ACTIVE"} into
 * {@link MemoryRepository#findByUserIdAndStatusOrderByUpdatedAtDesc(Long, String)}. If a future
 * refactor accidentally drops or changes the status arg (e.g. accepts user input), this test
 * fails immediately.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryService status='ACTIVE' parameter contract (Memory v2 PR-2)")
class MemoryServiceStatusFilterTest {

    @Mock
    private MemoryRepository memoryRepository;
    @Mock
    private MemorySnapshotRepository memorySnapshotRepository;
    @Mock
    private MemoryEmbeddingWorker embeddingWorker;
    @Mock
    private EmbeddingService embeddingService;

    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(
                memoryRepository, memorySnapshotRepository, embeddingWorker, embeddingService);
    }

    @Test
    @DisplayName("getMemoriesForPromptInjection always queries with status='ACTIVE'")
    void injection_alwaysQueriesActive() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(memory(1L, "preference", "p1", "v")));

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(mi.injectedIds()).containsExactly(1L);
        // Verify exact status arg and confirm legacy non-status method is never called.
        verify(memoryRepository).findByUserIdAndStatusOrderByUpdatedAtDesc(eq(7L), eq("ACTIVE"));
        verify(memoryRepository, never()).findByUserIdOrderByUpdatedAtDesc(7L);
    }

    @Test
    @DisplayName("previewMemoriesForPrompt always queries with status='ACTIVE'")
    void preview_alwaysQueriesActive() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(memory(1L, "knowledge", "k1", "v")));

        String preview = memoryService.previewMemoriesForPrompt(7L, null);

        assertThat(preview).contains("k1");
        verify(memoryRepository).findByUserIdAndStatusOrderByUpdatedAtDesc(eq(7L), eq("ACTIVE"));
        verify(memoryRepository, never()).findByUserIdOrderByUpdatedAtDesc(7L);
    }

    @Test
    @DisplayName("STALE memory not returned by repository → does not appear in injection")
    void staleMemory_notInjected() {
        // The repository contract guarantees status='ACTIVE' filter; mock returns ONLY the active
        // row. If we ever regress and call a non-status variant, the test would surface no rows
        // (no behavioural impact here) but the never() asserts above would also fire.
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(memory(1L, "preference", "active-pref", "v")));

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(mi.text()).contains("active-pref");
        // Stale ids shouldn't sneak in via any other code path.
        assertThat(mi.injectedIds()).containsExactly(1L);
    }

    private static MemoryEntity memory(Long id, String type, String title, String content) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(7L);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        m.setRecallCount(0);
        m.setStatus("ACTIVE");
        m.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        m.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return m;
    }
}
