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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryService confirmed long-term prompt injection")
class MemoryServiceRenderTest {

    @Mock private MemoryRepository memoryRepository;
    @Mock private MemorySnapshotRepository memorySnapshotRepository;
    @Mock private MemoryEmbeddingWorker embeddingWorker;
    @Mock private EmbeddingService embeddingService;

    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(
                memoryRepository, memorySnapshotRepository, embeddingWorker, embeddingService);
    }

    @Test
    @DisplayName("injects only active confirmed memories with auditable provenance")
    void injectsOnlyConfirmedMemoriesWithProvenance() {
        MemoryEntity confirmed = memory(1L, "preference", "Response style", "Use concise Chinese.");
        confirmed.setConfirmationStatus("CONFIRMED");
        confirmed.setProvenanceSource("USER_EXPLICIT");
        confirmed.setConfidence(1.0d);
        confirmed.setVersion(4L);
        MemoryEntity unverified = memory(2L, "project", "Session digest", "Temporary research detail");
        unverified.setConfirmationStatus("UNVERIFIED");
        unverified.setProvenanceSource("USER_TRANSCRIPT");

        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(confirmed, unverified));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(confirmed));

        MemoryInjection result = memoryService.getMemoriesForPromptInjection(
                7L, "this task text must not trigger automatic memory search");

        assertThat(result.text())
                .contains("### Confirmed Long-term Memory")
                .contains("memory:1")
                .contains("provenance=USER_EXPLICIT")
                .contains("confirmation=CONFIRMED")
                .contains("Response style")
                .doesNotContain("Session digest")
                .doesNotContain("Temporary research detail");
        assertThat(result.injectedIds()).containsExactly(1L);
        assertThat(result.provenance()).singleElement().satisfies(ref -> {
            assertThat(ref.memoryId()).isEqualTo(1L);
            assertThat(ref.version()).isEqualTo(4L);
        });
        verify(memoryRepository).incrementRecallCount(eq(1L), any());
        verify(memoryRepository, never()).incrementRecallCount(eq(2L), any());
        verify(memoryRepository, never()).findByFts(anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("does not auto-inject when all active memories are unverified")
    void allUnverifiedReturnsBlank() {
        MemoryEntity unverified = memory(1L, "knowledge", "Digest", "Transient session detail");
        unverified.setConfirmationStatus("UNVERIFIED");
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(unverified));

        MemoryInjection result = memoryService.getMemoriesForPromptInjection(7L, "research topic");

        assertThat(result.text()).isBlank();
        assertThat(result.injectedIds()).isEmpty();
        assertThat(result.provenance()).isEmpty();
        verify(memoryRepository, never()).incrementRecallCount(anyLong(), any());
    }

    @Test
    @DisplayName("caps automatic injection at six memories in repository recency order")
    void capsAtSixInRecencyOrder() {
        List<MemoryEntity> memories = new ArrayList<>();
        for (long id = 1; id <= 8; id++) {
            MemoryEntity memory = memory(id, "knowledge", "m-" + id, "value-" + id);
            memory.setConfirmationStatus("CONFIRMED");
            memories.add(memory);
        }
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(memories);
        when(memoryRepository.findAllById(any())).thenReturn(memories.subList(0, 6));

        MemoryInjection result = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(result.injectedIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(result.text()).contains("m-1", "m-6").doesNotContain("m-7", "m-8");
    }

    @Test
    @DisplayName("truncates each confirmed memory and keeps the total block bounded")
    void boundsEachEntryAndTotalBlock() {
        List<MemoryEntity> memories = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            MemoryEntity memory = memory(id, "knowledge", "m-" + id, "Q".repeat(1_000));
            memory.setConfirmationStatus("CONFIRMED");
            memories.add(memory);
        }
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(memories);
        when(memoryRepository.findAllById(any())).thenReturn(memories);

        MemoryInjection result = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(result.text()).contains("...[truncated]");
        assertThat(result.text().length()).isLessThanOrEqualTo(3_200);
    }

    @Test
    @DisplayName("preview returns the same confirmed block without recall side effects")
    void previewHasNoRecallSideEffects() {
        MemoryEntity confirmed = memory(1L, "preference", "Language", "Use Chinese.");
        confirmed.setConfirmationStatus("CONFIRMED");
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(confirmed));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(confirmed));

        MemoryInjection preview = memoryService.previewMemoryInjectionForPrompt(7L, "ignored");

        assertThat(preview.text()).contains("Language");
        assertThat(preview.injectedIds()).containsExactly(1L);
        verify(memoryRepository, never()).incrementRecallCount(anyLong(), any());
    }

    @Test
    @DisplayName("null user returns an empty injection without repository access")
    void nullUserReturnsEmpty() {
        MemoryInjection result = memoryService.getMemoriesForPromptInjection(null, "ignored");

        assertThat(result.text()).isBlank();
        assertThat(result.injectedIds()).isEmpty();
        verify(memoryRepository, never())
                .findByUserIdAndStatusOrderByUpdatedAtDesc(anyLong(), any());
    }

    private static MemoryEntity memory(Long id, String type, String title, String content) {
        MemoryEntity memory = new MemoryEntity();
        memory.setId(id);
        memory.setUserId(7L);
        memory.setType(type);
        memory.setTitle(title);
        memory.setContent(content);
        memory.setRecallCount(0);
        memory.setStatus("ACTIVE");
        memory.setProvenanceSource("USER_EXPLICIT");
        memory.setConfirmationStatus("UNVERIFIED");
        memory.setConfidence(1.0d);
        memory.setVersion(1L);
        memory.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        memory.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return memory;
    }
}
