package com.skillforge.server.service;

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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests covering {@code MemoryService.getMemoriesForPrompt} (legacy, time-based fallback)
 * and the private {@code mergeWithRrf} fusion (exercised via the semantic path).
 *
 * <p>Pinning these two behaviours in PR-1 gives PR-2 a clean diff target when L0/L1 layered
 * recall replaces them. The current contract verified here:
 *
 * <ul>
 *   <li>Sections render in fixed order: <b>Preferences</b> → <b>Feedback</b> →
 *       <b>Knowledge &amp; Context</b>.</li>
 *   <li>Each section caps at 10 entries.</li>
 *   <li>Per-content truncation at {@code MAX_CONTENT_CHARS=500}.</li>
 *   <li>Total truncation at {@code MAX_TOTAL_CHARS=8000}.</li>
 *   <li>RRF (K=60) gives overlapping items the sum of both reciprocal-rank scores; the
 *       overlap therefore outranks any single-list-only item that appears at the same rank.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryService.renderMemoriesForPrompt + mergeWithRrf (pre-Memory-v2 baseline)")
class MemoryServiceRenderTest {

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

    // ---------------- time-based fallback (taskContext == null) ----------------

    @Test
    @DisplayName("sections rendered in order: Preferences → Feedback → Knowledge & Context")
    void getMemoriesForPrompt_noContext_rendersInExpectedOrder() {
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(new ArrayList<>(List.of(
                memory(1L, "preference", "use Java", "v1"),
                memory(2L, "feedback", "be concise", "v1"),
                memory(3L, "knowledge", "Spring quirks", "v1")
        )));

        String out = memoryService.getMemoriesForPrompt(7L, null);

        assertThat(out).contains("### Preferences", "### Feedback", "### Knowledge & Context");
        int prefIdx = out.indexOf("### Preferences");
        int feedIdx = out.indexOf("### Feedback");
        int knowIdx = out.indexOf("### Knowledge & Context");
        assertThat(prefIdx).isLessThan(feedIdx);
        assertThat(feedIdx).isLessThan(knowIdx);
    }

    @Test
    @DisplayName("each typed section caps at 10 entries")
    void getMemoriesForPrompt_capsEachSectionAtTen() {
        List<MemoryEntity> prefs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            prefs.add(memory((long) i, "preference", "title-" + i, "content-" + i));
        }
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(prefs);

        String out = memoryService.getMemoriesForPrompt(7L, null);

        long bulletCount = out.lines().filter(l -> l.startsWith("- ")).count();
        assertThat(bulletCount).isEqualTo(10);
    }

    @Test
    @DisplayName("per-entry content truncates beyond MAX_CONTENT_CHARS (500) with marker")
    void getMemoriesForPrompt_longContent_truncates() {
        String huge = "x".repeat(800);
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(new ArrayList<>(List.of(
                memory(1L, "preference", "huge", huge)
        )));

        String out = memoryService.getMemoriesForPrompt(7L, null);

        assertThat(out).contains("...[truncated]");
        // The 'x' run before the truncate marker must be exactly MAX_CONTENT_CHARS=500.
        // Tightened (BE-r1-W3): catches any silent doubling of the cap.
        int marker = out.indexOf("...[truncated]");
        int firstX = out.indexOf('x');
        assertThat(marker - firstX).isEqualTo(500);
    }

    @Test
    @DisplayName("returns empty string when user has no memories")
    void getMemoriesForPrompt_noMemories_returnsEmpty() {
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(new ArrayList<>());

        String out = memoryService.getMemoriesForPrompt(7L, null);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("global MAX_TOTAL_CHARS halts rendering before runaway output")
    void getMemoriesForPrompt_totalCapStops() {
        // 50 prefs * ~500 char content each → exceeds 8000 char total cap.
        List<MemoryEntity> bigList = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            bigList.add(memory((long) i, "preference", "t-" + i, "y".repeat(500)));
        }
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(bigList);

        String out = memoryService.getMemoriesForPrompt(7L, null);

        // Cap is 8000; truncation triggers AFTER appending an entry → allow at most one
        // additional entry's worth (~600 chars) of overrun. Tightened (BE-r1-W3):
        // catches silent regression from 8000 → 50% overrun (12_000) which would have passed.
        assertThat(out.length()).isLessThan(8_000 + 800);
    }

    // ---------------- mergeWithRrf via semantic path ----------------

    @Test
    @DisplayName("mergeWithRrf: items appearing in both lists outrank single-list items")
    void mergeWithRrf_overlapBeatsSingleList() {
        // taskContext triggers semantic path; embedding present so vector recall runs.
        String ctx = "spring boot tips";
        when(embeddingService.embed(eq(ctx))).thenReturn(Optional.of(new float[]{0.1f, 0.2f, 0.3f}));

        // Stub knowledge memories so the semantic branch runs.
        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(new ArrayList<>(List.of(
                memory(101L, "knowledge", "fts-only-top", "ftop"),
                memory(102L, "knowledge", "vec-only-top", "vtop"),
                memory(103L, "knowledge", "shared-mid", "shared")
        )));

        // FTS list ranks (by index): [101 -> rank 0, 103 -> rank 1]
        when(memoryRepository.findByFts(eq(7L), anyString(), anyInt())).thenReturn(Arrays.asList(
                row(101L, "knowledge", "fts-only-top", "ftop", 0.9),
                row(103L, "knowledge", "shared-mid", "shared", 0.5)
        ));
        // Vector list ranks (by index): [102 -> rank 0, 103 -> rank 1]
        when(memoryRepository.findByVector(eq(7L), anyString(), anyInt())).thenReturn(Arrays.asList(
                row(102L, "knowledge", "vec-only-top", "vtop", 0.1),
                row(103L, "knowledge", "shared-mid", "shared", 0.2)
        ));
        // recall increment is fire-and-forget; lenient stub satisfies any param shape.
        lenient().doAnswer(inv -> null).when(memoryRepository).incrementRecallCount(anyLong(), org.mockito.ArgumentMatchers.any());

        String out = memoryService.getMemoriesForPrompt(7L, ctx);

        assertThat(out).contains("Knowledge & Context (ranked by relevance)");
        // Score(103) = 1/(60+2) + 1/(60+2) = 2/62 ≈ 0.0322 (top after fusion)
        // Score(101) = 1/(60+1) ≈ 0.0164
        // Score(102) = 1/(60+1) ≈ 0.0164
        int sharedIdx = out.indexOf("shared-mid");
        int ftsIdx = out.indexOf("fts-only-top");
        int vecIdx = out.indexOf("vec-only-top");
        assertThat(sharedIdx).isPositive();
        assertThat(sharedIdx).isLessThan(ftsIdx);
        assertThat(sharedIdx).isLessThan(vecIdx);
    }

    @Test
    @DisplayName("mergeWithRrf: when embedding unavailable, falls back to FTS-only ranking")
    void mergeWithRrf_noEmbedding_fallsBackToFts() {
        String ctx = "spring boot tips";
        when(embeddingService.embed(eq(ctx))).thenReturn(Optional.empty());

        when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(new ArrayList<>(List.of(
                memory(201L, "knowledge", "fts-top", "a"),
                memory(202L, "knowledge", "fts-second", "b")
        )));
        when(memoryRepository.findByFts(eq(7L), anyString(), anyInt())).thenReturn(Arrays.asList(
                row(201L, "knowledge", "fts-top", "a", 0.9),
                row(202L, "knowledge", "fts-second", "b", 0.5)
        ));
        lenient().doAnswer(inv -> null).when(memoryRepository).incrementRecallCount(anyLong(), org.mockito.ArgumentMatchers.any());

        String out = memoryService.getMemoriesForPrompt(7L, ctx);

        // Only FTS items are present; their relative order is preserved.
        int firstIdx = out.indexOf("fts-top");
        int secondIdx = out.indexOf("fts-second");
        assertThat(firstIdx).isPositive();
        assertThat(firstIdx).isLessThan(secondIdx);
    }

    // ---------------- helpers ----------------

    private static MemoryEntity memory(Long id, String type, String title, String content) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(7L);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        m.setRecallCount(0);
        m.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        m.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return m;
    }

    /** FTS / Vector native query rows: [id, type, title, content, tags, recall_count, score]. */
    private static Object[] row(long id, String type, String title, String content, double score) {
        return new Object[]{id, type, title, content, "", 0, score};
    }
}
