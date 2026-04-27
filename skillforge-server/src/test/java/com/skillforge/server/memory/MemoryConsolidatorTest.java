package com.skillforge.server.memory;

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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MemoryConsolidator} pre-Memory-v2 behaviour.
 *
 * <p>PR-1 deliberately does NOT change consolidator semantics — these tests pin the
 * current behaviour (tag-based stale marking + title dedup) so PR-5's eviction rewrite
 * has a regression baseline to diff against. Do not add status-column assertions here;
 * status sweeping is a PR-5 concern.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryConsolidator (legacy tag-based behaviour)")
class MemoryConsolidatorTest {

    @Mock
    private MemoryRepository memoryRepository;

    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() {
        consolidator = new MemoryConsolidator(memoryRepository);
    }

    @Test
    @DisplayName("dedup keeps newest by updatedAt and deletes the rest with same title")
    void consolidate_sameTitle_keepsNewest_deletesRest() {
        MemoryEntity newest = memory(1L, "preference", "use Java 17", "v3", null, 0, null, daysAgo(1));
        MemoryEntity middle = memory(2L, "preference", "use Java 17", "v2", null, 0, null, daysAgo(2));
        MemoryEntity oldest = memory(3L, "preference", "use Java 17", "v1", null, 0, null, daysAgo(3));
        // Repository contract: findByUserIdOrderByUpdatedAtDesc returns sorted desc.
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(Arrays.asList(newest, middle, oldest)));

        consolidator.consolidate(7L);

        ArgumentCaptor<MemoryEntity> deleted = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(memoryRepository, times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).containsExactlyInAnyOrder(middle, oldest);
    }

    @Test
    @DisplayName("dedup leaves a single-title group untouched")
    void consolidate_uniqueTitles_noDeletes() {
        MemoryEntity onlyOne = memory(1L, "preference", "unique title", "v1", null, 0, null, daysAgo(1));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(onlyOne)));

        consolidator.consolidate(7L);

        verify(memoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("marks stale when 30+ days no recall, recallCount<3, and created 30+ days ago")
    void consolidate_oldUnusedMemory_marksStale() {
        MemoryEntity stale = memory(1L, "knowledge", "Spring quirks", "details",
                /* tags */ null,
                /* recallCount */ 1,
                /* lastRecalledAt */ instantDaysAgo(40),
                /* updatedAt */ daysAgo(40));
        stale.setCreatedAt(daysAgo(45));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(stale)));

        consolidator.consolidate(7L);

        ArgumentCaptor<MemoryEntity> saved = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(memoryRepository, atLeastOnce()).save(saved.capture());
        assertThat(stale.getTags()).isEqualTo("stale");
        assertThat(saved.getValue().getTags()).contains("stale");
    }

    @Test
    @DisplayName("does not double-add stale tag when already present")
    void consolidate_alreadyStale_idempotent() {
        MemoryEntity already = memory(1L, "knowledge", "Spring quirks", "details",
                /* tags */ "java,stale,spring",
                /* recallCount */ 0,
                /* lastRecalledAt */ instantDaysAgo(60),
                /* updatedAt */ daysAgo(60));
        already.setCreatedAt(daysAgo(80));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(already)));

        consolidator.consolidate(7L);

        verify(memoryRepository, never()).save(any());
        // tags string left untouched (no duplicate "stale,stale")
        assertThat(already.getTags()).isEqualTo("java,stale,spring");
    }

    @Test
    @DisplayName("does not mark stale when recently recalled even if old")
    void consolidate_recentlyRecalled_notStale() {
        MemoryEntity hot = memory(1L, "knowledge", "Spring", "details",
                null, 0,
                /* lastRecalledAt */ instantDaysAgo(5),
                daysAgo(60));
        hot.setCreatedAt(daysAgo(60));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(hot)));

        consolidator.consolidate(7L);

        verify(memoryRepository, never()).save(any());
        assertThat(hot.getTags()).isNull();
    }

    @Test
    @DisplayName("does not mark stale when recallCount >= 3 even if cold")
    void consolidate_frequentlyRecalled_notStale() {
        MemoryEntity popular = memory(1L, "knowledge", "Spring", "details",
                null,
                /* recallCount */ 3,
                /* lastRecalledAt */ instantDaysAgo(60),
                daysAgo(60));
        popular.setCreatedAt(daysAgo(60));
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(new ArrayList<>(List.of(popular)));

        consolidator.consolidate(7L);

        verify(memoryRepository, never()).save(any());
        assertThat(popular.getTags()).isNull();
    }

    // ---------- helpers ----------

    private static MemoryEntity memory(Long id, String type, String title, String content,
                                       String tags, int recallCount,
                                       Instant lastRecalledAt, LocalDateTime updatedAt) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(7L);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        m.setTags(tags);
        m.setRecallCount(recallCount);
        m.setLastRecalledAt(lastRecalledAt);
        m.setUpdatedAt(updatedAt);
        return m;
    }

    private static LocalDateTime daysAgo(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    private static Instant instantDaysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    @SuppressWarnings("unused")
    private static Instant toInstant(LocalDateTime ldt) {
        return ldt.atZone(ZoneId.systemDefault()).toInstant();
    }
}
