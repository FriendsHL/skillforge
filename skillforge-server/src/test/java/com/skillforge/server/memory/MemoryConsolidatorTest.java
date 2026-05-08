package com.skillforge.server.memory;

import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryConsolidator (PR-5 eviction + capacity)")
class MemoryConsolidatorTest {

    @Mock
    private MemoryRepository memoryRepository;

    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() {
        consolidator = new MemoryConsolidator(memoryRepository);
        MemoryProperties properties = new MemoryProperties();
        properties.getEviction().setMaxActivePerUser(2);
        consolidator.setMemoryProperties(properties);
    }

    @Test
    @DisplayName("30+ day cold active memory becomes STALE and gets scored")
    void consolidate_marksColdActiveMemoryStale() {
        MemoryEntity stale = memory(1L, "ACTIVE", "low", 0, 45, null);
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(stale)));

        consolidator.consolidate(7L);

        verify(memoryRepository, atLeastOnce()).save(stale);
        assertThat(stale.getStatus()).isEqualTo("STALE");
        assertThat(stale.getLastScore()).isNotNull();
        assertThat(stale.getLastScoredAt()).isNotNull();
    }

    @Test
    @DisplayName("60+ day cold memory becomes ARCHIVED and records archivedAt")
    void consolidate_archivesColdMemory() {
        MemoryEntity archived = memory(2L, "STALE", "medium", 1, 75, null);
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(archived)));

        consolidator.consolidate(7L);

        verify(memoryRepository, atLeastOnce()).save(archived);
        assertThat(archived.getStatus()).isEqualTo("ARCHIVED");
        assertThat(archived.getArchivedAt()).isNotNull();
        assertThat(archived.getLastScore()).isNotNull();
    }

    @Test
    @DisplayName("90+ day archived memory is physically deleted")
    void consolidate_deletesExpiredArchivedMemory() {
        MemoryEntity archived = memory(3L, "ARCHIVED", "low", 0, 120,
                Instant.now().minus(95, ChronoUnit.DAYS));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(archived)));

        consolidator.consolidate(7L);

        verify(memoryRepository).delete(archived);
        verify(memoryRepository, never()).save(archived);
    }

    @Test
    @DisplayName("dedup: cosine > threshold archives lower-scored as ARCHIVED with reason")
    void consolidate_archivesDuplicateBelowThreshold() {
        MemoryEntity high = memory(101L, "ACTIVE", "high", 10, 1, null);
        MemoryEntity low = memory(102L, "ACTIVE", "low", 0, 1, null);
        // Identical embeddings → cosine = 1.0 > 0.85 default threshold.
        when(memoryRepository.findEmbeddingsForActiveByUser(7L)).thenReturn(List.of(
                new Object[] { 101L, "[1.0,0.0,0.0]" },
                new Object[] { 102L, "[1.0,0.0,0.0]" }
        ));
        when(memoryRepository.findById(101L)).thenReturn(Optional.of(high));
        when(memoryRepository.findById(102L)).thenReturn(Optional.of(low));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(high, low)));

        consolidator.consolidate(7L);

        // Lower-scored memory archived as duplicate; winner kept ACTIVE.
        assertThat(low.getStatus()).isEqualTo("ARCHIVED");
        assertThat(low.getArchivedReason()).isEqualTo("dedup_merge_with_101");
        assertThat(low.getArchivedAt()).isNotNull();
        assertThat(high.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("dedup: cosine below threshold leaves both ACTIVE")
    void consolidate_leavesBothActiveWhenBelowThreshold() {
        MemoryEntity a = memory(201L, "ACTIVE", "high", 10, 1, null);
        MemoryEntity b = memory(202L, "ACTIVE", "high", 10, 1, null);
        // Orthogonal embeddings → cosine = 0.0 < 0.85.
        when(memoryRepository.findEmbeddingsForActiveByUser(7L)).thenReturn(List.of(
                new Object[] { 201L, "[1.0,0.0,0.0]" },
                new Object[] { 202L, "[0.0,1.0,0.0]" }
        ));
        when(memoryRepository.findById(201L)).thenReturn(Optional.of(a));
        when(memoryRepository.findById(202L)).thenReturn(Optional.of(b));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(a, b)));

        consolidator.consolidate(7L);

        assertThat(a.getStatus()).isEqualTo("ACTIVE");
        assertThat(a.getArchivedReason()).isNull();
        assertThat(b.getStatus()).isEqualTo("ACTIVE");
        assertThat(b.getArchivedReason()).isNull();
    }

    @Test
    @DisplayName("dedup: pgvector unavailable (repo throws) → degraded gracefully, no exception")
    void consolidate_dedupRepoThrows_degradesGracefully() {
        MemoryEntity m = memory(301L, "ACTIVE", "high", 10, 1, null);
        when(memoryRepository.findEmbeddingsForActiveByUser(7L))
                .thenThrow(new RuntimeException("pgvector not installed"));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(m)));

        consolidator.consolidate(7L); // must not throw

        // Memory still scored / saved through normal path despite dedup failure.
        verify(memoryRepository, atLeastOnce()).save(m);
        assertThat(m.getLastScore()).isNotNull();
    }

    @Test
    @DisplayName("90+ day archived memory is physically deleted with reason logged")
    void consolidate_deletesExpiredArchivedAndPreservesReason() {
        MemoryEntity archived = memory(401L, "ARCHIVED", "low", 0, 120,
                Instant.now().minus(95, ChronoUnit.DAYS));
        archived.setArchivedReason("dedup_merge_with_999"); // pre-existing reason
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(archived)));

        consolidator.consolidate(7L);

        verify(memoryRepository).delete(archived);
    }

    @Test
    @DisplayName("transition to ARCHIVED via age sets reason=expired_ttl when not already set")
    void consolidate_ttlArchiveSetsExpiredReason() {
        MemoryEntity old = memory(501L, "STALE", "medium", 1, 75, null);
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(old)));

        consolidator.consolidate(7L);

        assertThat(old.getStatus()).isEqualTo("ARCHIVED");
        assertThat(old.getArchivedReason()).isEqualTo("expired_ttl");
    }

    @Test
    @DisplayName("capacity demote sets archivedReason=capacity_demote on STALE row")
    void consolidate_capacityDemoteSetsReason() {
        MemoryEntity keep1 = memory(601L, "ACTIVE", "high", 10, 1, null);
        MemoryEntity keep2 = memory(602L, "ACTIVE", "medium", 8, 3, null);
        MemoryEntity demote = memory(603L, "ACTIVE", "low", 0, 1, null);
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(keep1, keep2, demote)));

        consolidator.consolidate(7L);

        assertThat(demote.getStatus()).isEqualTo("STALE");
        assertThat(demote.getArchivedReason()).isEqualTo("capacity_demote");
    }

    @Test
    @DisplayName("capacity overflow demotes the lowest scored ACTIVE memories to STALE")
    void consolidate_enforcesCapacityByLowestScore() {
        MemoryEntity keep1 = memory(11L, "ACTIVE", "high", 10, 1, null);
        MemoryEntity keep2 = memory(12L, "ACTIVE", "medium", 8, 3, null);
        MemoryEntity demote = memory(13L, "ACTIVE", "low", 0, 50, null);
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(keep1, keep2, demote)));

        consolidator.consolidate(7L);

        assertThat(keep1.getStatus()).isEqualTo("ACTIVE");
        assertThat(keep2.getStatus()).isEqualTo("ACTIVE");
        assertThat(demote.getStatus()).isEqualTo("STALE");
        ArgumentCaptor<MemoryEntity> saved = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(memoryRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).contains(demote);
    }

    private static MemoryEntity memory(Long id,
                                       String status,
                                       String importance,
                                       int recallCount,
                                       int anchorDaysAgo,
                                       Instant archivedAt) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(7L);
        m.setType("knowledge");
        m.setTitle("memory-" + id);
        m.setContent("content-" + id);
        m.setStatus(status);
        m.setImportance(importance);
        m.setRecallCount(recallCount);
        m.setArchivedAt(archivedAt);
        m.setCreatedAt(LocalDateTime.now().minusDays(anchorDaysAgo));
        m.setUpdatedAt(LocalDateTime.now().minusDays(anchorDaysAgo));
        if (!"ARCHIVED".equals(status)) {
            m.setLastRecalledAt(Instant.now().minus(anchorDaysAgo, ChronoUnit.DAYS));
        }
        return m;
    }
}
