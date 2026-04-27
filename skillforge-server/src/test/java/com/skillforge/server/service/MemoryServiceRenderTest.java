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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Memory v2 (PR-2): unit tests covering the L0/L1 layered prompt-injection rewrite of
 * {@link MemoryService#getMemoriesForPromptInjection} and {@link MemoryService#previewMemoriesForPrompt}.
 *
 * <p>The contract verified here:
 *
 * <ul>
 *   <li>L0 (preference + feedback): always rendered, recency-ordered, capped to L0_BUDGET_CHARS=2048.</li>
 *   <li>L0 per-entry cap = L0_PER_ENTRY_CHARS=200.</li>
 *   <li>L1 (knowledge/project/reference): hybrid (FTS+Vector) RRF top-K=8 when taskContext is non-blank
 *       and ≥3 chars; else recency fallback (skips FTS + embedding I/O).</li>
 *   <li>L1 per-entry cap = L1_PER_ENTRY_CHARS=500; L1 budget = 4096 chars.</li>
 *   <li>STALE memories never returned (status='ACTIVE' filter via repository call).</li>
 *   <li>RRF (K=60) gives overlapping items the sum of both reciprocal-rank scores; the overlap
 *       therefore outranks any single-list-only item that appears at the same rank.</li>
 *   <li>{@code getMemoriesForPromptInjection} bumps recall_count for every injected id; the
 *       returned {@link MemoryInjection#injectedIds()} contains exactly the injected ids.</li>
 *   <li>{@code previewMemoriesForPrompt} renders the same text but does NOT bump recall_count.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryService L0/L1 layered prompt injection (Memory v2 PR-2)")
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

    // ──────────────── L0: preference + feedback always inject ────────────────

    @Test
    @DisplayName("L0: Preferences and Feedback render in order; ids returned via MemoryInjection")
    void l0_rendersPreferencesAndFeedback_inOrder() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(
                        memory(1L, "preference", "use Java", "v1"),
                        memory(2L, "feedback", "be concise", "v1")
                ));

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(mi.text()).contains("### Preferences", "### Feedback");
        assertThat(mi.text().indexOf("### Preferences"))
                .isLessThan(mi.text().indexOf("### Feedback"));
        assertThat(mi.injectedIds()).containsExactlyInAnyOrder(1L, 2L);
        // L0 recall_count bumped for each injected id.
        verify(memoryRepository).incrementRecallCount(eq(1L), any());
        verify(memoryRepository).incrementRecallCount(eq(2L), any());
    }

    @Test
    @DisplayName("L0 budget: preference + feedback per-section ≤ L0_BUDGET_CHARS/2; both sections together ≤ L0_BUDGET_CHARS+overshoot")
    void l0_capsToBudget() {
        // Both 30 prefs + 30 feedback × ~200 char → exceeds per-section budget (1024) on each side.
        // Stub BOTH so the test catches a regression that loosens the per-section equal-split cap.
        List<MemoryEntity> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            all.add(memory((long) i, "preference", "p-" + i, "p".repeat(200)));
        }
        for (int i = 0; i < 30; i++) {
            all.add(memory((long) (100 + i), "feedback", "f-" + i, "f".repeat(200)));
        }
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(all);

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, null);

        // Tightened (Server-W1): per-section cap = L0_BUDGET_CHARS/2 must hold even when the
        // OTHER section is empty. Verify by counting 'p' and 'f' runs separately. Each section
        // contributes ≤ (L0_BUDGET_CHARS/2 + one entry overshoot since cap is checked BEFORE
        // adding) ≈ 1024 + 200 = 1224.
        long pCount = mi.text().chars().filter(c -> c == 'p').count();
        long fCount = mi.text().chars().filter(c -> c == 'f').count();
        assertThat(pCount).isLessThan((L0_BUDGET_CHARS / 2) + L0_PER_ENTRY_CHARS + 50);
        assertThat(fCount).isLessThan((L0_BUDGET_CHARS / 2) + L0_PER_ENTRY_CHARS + 50);
        // Both sections together still ≤ total + one overshoot per section.
        assertThat(mi.text().length()).isLessThan(L0_BUDGET_CHARS + 2 * L0_PER_ENTRY_CHARS + 100);
    }

    @Test
    @DisplayName("L0 per-entry: content beyond L0_PER_ENTRY_CHARS=200 truncates with marker")
    void l0_truncatesPerEntryContent() {
        String huge = "x".repeat(800);
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(memory(1L, "preference", "huge", huge)));

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(mi.text()).contains("...[truncated]");
        // x-run before marker must be exactly L0_PER_ENTRY_CHARS=200.
        int marker = mi.text().indexOf("...[truncated]");
        int firstX = mi.text().indexOf('x');
        assertThat(marker - firstX).isEqualTo(L0_PER_ENTRY_CHARS);
    }

    @Test
    @DisplayName("Empty user (no ACTIVE memories) returns blank text + empty injectedIds")
    void emptyUser_returnsBlank() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of());

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(mi.text()).isEmpty();
        assertThat(mi.injectedIds()).isEmpty();
        verify(memoryRepository, never()).incrementRecallCount(anyLong(), any());
    }

    // ──────────────── L1: taskContext fallback (null/blank/short) ────────────────

    @Test
    @DisplayName("L1 fallback: taskContext=null skips FTS+embedding; recency K/P/R rendered")
    void l1_taskContextNull_recencyFallback() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(
                        memory(1L, "knowledge", "k1", "kc1"),
                        memory(2L, "project", "p1", "pc1"),
                        memory(3L, "reference", "r1", "rc1")
                ));

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(mi.text()).contains("### Knowledge & Context");
        assertThat(mi.text()).doesNotContain("ranked by relevance");
        assertThat(mi.injectedIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
        // No FTS / embedding I/O when taskContext absent.
        verify(memoryRepository, never()).findByFts(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("L1 fallback: taskContext blank string falls back to recency")
    void l1_taskContextBlank_recencyFallback() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(memory(1L, "knowledge", "k1", "kc1")));

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, "   ");

        assertThat(mi.text()).contains("### Knowledge & Context");
        verify(memoryRepository, never()).findByFts(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("L1 fallback: taskContext < 3 chars falls back to recency")
    void l1_taskContextTooShort_recencyFallback() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(memory(1L, "knowledge", "k1", "kc1")));

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, "hi");

        assertThat(mi.text()).contains("### Knowledge & Context");
        verify(memoryRepository, never()).findByFts(anyLong(), anyString(), anyInt());
    }

    // ──────────────── L1: hybrid path (FTS + Vector + RRF) ────────────────

    @Test
    @DisplayName("L1 hybrid: RRF overlapping items outrank single-list items; ranked header rendered")
    void l1_hybrid_rrfOverlapWins() {
        String ctx = "spring boot tips";
        when(embeddingService.embed(eq(ctx)))
                .thenReturn(Optional.of(new float[]{0.1f, 0.2f, 0.3f}));

        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(
                        memory(101L, "knowledge", "fts-only-top", "ftop"),
                        memory(102L, "knowledge", "vec-only-top", "vtop"),
                        memory(103L, "knowledge", "shared-mid", "shared")
                ));
        when(memoryRepository.findByFts(eq(7L), anyString(), anyInt())).thenReturn(Arrays.asList(
                row(101L, "knowledge", "fts-only-top", "ftop", 0.9),
                row(103L, "knowledge", "shared-mid", "shared", 0.5)
        ));
        when(memoryRepository.findByVector(eq(7L), anyString(), anyInt())).thenReturn(Arrays.asList(
                row(102L, "knowledge", "vec-only-top", "vtop", 0.1),
                row(103L, "knowledge", "shared-mid", "shared", 0.2)
        ));
        lenient().doAnswer(inv -> null).when(memoryRepository).incrementRecallCount(anyLong(), any());

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, ctx);
        String out = mi.text();

        assertThat(out).contains("### Knowledge & Context (ranked by relevance)");
        // Score(103) = 1/(60+2) + 1/(60+2) ≈ 0.0322 (top after fusion)
        // Score(101) = 1/(60+1) ≈ 0.0164
        // Score(102) = 1/(60+1) ≈ 0.0164
        int sharedIdx = out.indexOf("shared-mid");
        int ftsIdx = out.indexOf("fts-only-top");
        int vecIdx = out.indexOf("vec-only-top");
        assertThat(sharedIdx).isPositive();
        assertThat(sharedIdx).isLessThan(ftsIdx);
        assertThat(sharedIdx).isLessThan(vecIdx);
        // All three injected ids reported.
        assertThat(mi.injectedIds()).contains(101L, 102L, 103L);
    }

    @Test
    @DisplayName("L1 hybrid: embedding unavailable → falls back to FTS-only ranking")
    void l1_hybrid_noEmbedding_ftsOnly() {
        String ctx = "spring boot tips";
        when(embeddingService.embed(eq(ctx))).thenReturn(Optional.empty());

        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(
                        memory(201L, "knowledge", "fts-top", "a"),
                        memory(202L, "knowledge", "fts-second", "b")
                ));
        when(memoryRepository.findByFts(eq(7L), anyString(), anyInt())).thenReturn(Arrays.asList(
                row(201L, "knowledge", "fts-top", "a", 0.9),
                row(202L, "knowledge", "fts-second", "b", 0.5)
        ));
        lenient().doAnswer(inv -> null).when(memoryRepository).incrementRecallCount(anyLong(), any());

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, ctx);

        int firstIdx = mi.text().indexOf("fts-top");
        int secondIdx = mi.text().indexOf("fts-second");
        assertThat(firstIdx).isPositive();
        assertThat(firstIdx).isLessThan(secondIdx);
    }

    @Test
    @DisplayName("L1 hybrid: top-K=8 enforced even when more candidates ranked")
    void l1_hybrid_topKEnforced() {
        String ctx = "spring boot tips";
        when(embeddingService.embed(eq(ctx))).thenReturn(Optional.empty());

        // 12 active knowledge memories, 12 ranked from FTS — only 8 should make it through.
        List<MemoryEntity> knowledge = new ArrayList<>();
        List<Object[]> ftsRows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long id = 300L + i;
            knowledge.add(memory(id, "knowledge", "k-" + i, "c-" + i));
            ftsRows.add(row(id, "knowledge", "k-" + i, "c-" + i, 0.9 - i * 0.01));
        }
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(knowledge);
        when(memoryRepository.findByFts(eq(7L), anyString(), anyInt())).thenReturn(ftsRows);
        lenient().doAnswer(inv -> null).when(memoryRepository).incrementRecallCount(anyLong(), any());

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, ctx);

        // Count "- **k-N**:" bullets in the L1 ranked section.
        long bulletCount = mi.text().lines().filter(l -> l.startsWith("- **k-")).count();
        assertThat(bulletCount).isEqualTo(8); // L1_TOP_K
    }

    @Test
    @DisplayName("L1 hybrid: per-entry content > L1_PER_ENTRY_CHARS=500 truncates with marker")
    void l1_hybrid_perEntryTruncates() {
        String ctx = "spring boot tips";
        when(embeddingService.embed(eq(ctx))).thenReturn(Optional.empty());

        // Use a unique sentinel marker char ('Q' — uppercase, never appears in headers/titles)
        // to anchor the truncated run precisely. 'x'/'y' collide with 'Context' / 'by' / etc.
        String huge = "Q".repeat(900);
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(memory(401L, "knowledge", "huge-k", huge)));
        when(memoryRepository.findByFts(eq(7L), anyString(), anyInt())).thenReturn(List.<Object[]>of(
                row(401L, "knowledge", "huge-k", huge, 0.9)
        ));
        lenient().doAnswer(inv -> null).when(memoryRepository).incrementRecallCount(anyLong(), any());

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, ctx);

        assertThat(mi.text()).contains("...[truncated]");
        int marker = mi.text().indexOf("...[truncated]");
        int firstQ = mi.text().indexOf('Q');
        assertThat(marker - firstQ).isEqualTo(L1_PER_ENTRY_CHARS);
    }

    // ──────────────── preview vs injection: side-effect contract ────────────────

    @Test
    @DisplayName("previewMemoriesForPrompt: same text but no recall_count increment")
    void preview_noRecallIncrement() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(memory(1L, "preference", "use Java", "v1")));

        String preview = memoryService.previewMemoriesForPrompt(7L, null);

        assertThat(preview).contains("### Preferences");
        verify(memoryRepository, never()).incrementRecallCount(anyLong(), any());
    }

    @Test
    @DisplayName("getMemoriesForPromptInjection: every injected id triggers exactly one recall bump")
    void injection_recallBumpExactlyOnce() {
        when(memoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(7L, "ACTIVE"))
                .thenReturn(List.of(
                        memory(1L, "preference", "p1", "v"),
                        memory(2L, "feedback", "f1", "v"),
                        memory(3L, "knowledge", "k1", "v")
                ));

        MemoryInjection mi = memoryService.getMemoriesForPromptInjection(7L, null);

        assertThat(mi.injectedIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
        verify(memoryRepository, times(1)).incrementRecallCount(eq(1L), any());
        verify(memoryRepository, times(1)).incrementRecallCount(eq(2L), any());
        verify(memoryRepository, times(1)).incrementRecallCount(eq(3L), any());
    }

    // ──────────────── helpers ────────────────

    /** Mirror of the production constants — kept here so tests stay independent of access modifier. */
    private static final int L0_BUDGET_CHARS = 2048;
    private static final int L0_PER_ENTRY_CHARS = 200;
    private static final int L1_PER_ENTRY_CHARS = 500;

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

    /** FTS / Vector native query rows: [id, type, title, content, tags, recall_count, score]. */
    private static Object[] row(long id, String type, String title, String content, double score) {
        return new Object[]{id, type, title, content, "", 0, score};
    }
}
