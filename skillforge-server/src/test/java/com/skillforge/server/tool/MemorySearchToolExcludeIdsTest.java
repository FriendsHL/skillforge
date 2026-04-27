package com.skillforge.server.tool;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.dto.MemorySearchResult;
import com.skillforge.server.service.EmbeddingService;
import com.skillforge.server.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Memory v2 (PR-2): verify {@link MemorySearchTool} reads
 * {@link SkillContext#getInjectedMemoryIds()} and filters them out of RRF results
 * BEFORE the topK limit (filter-then-limit, "writing B"). This guarantees deeper
 * candidates surface to fill the topK slot when the top of RRF overlaps with the
 * L0/L1 prompt injection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemorySearchTool excludeIds filter-then-limit (Memory v2 PR-2)")
class MemorySearchToolExcludeIdsTest {

    @Mock
    private MemoryService memoryService;
    @Mock
    private EmbeddingService embeddingService;

    private MemorySearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new MemorySearchTool(memoryService, embeddingService);
    }

    @Test
    @DisplayName("excludeIds drops matching candidates AFTER RRF, BEFORE topK limit")
    void excludeIds_filterThenLimit_dropsThenTakesTopK() {
        // 5 FTS candidates ranked 1..5. None present in vector results (embedding empty).
        when(embeddingService.embed("q")).thenReturn(Optional.empty());
        when(memoryService.searchByFts(eq(7L), eq("q"), anyInt())).thenReturn(List.of(
                result(1L, "knowledge", "t1", "c1"),
                result(2L, "knowledge", "t2", "c2"),
                result(3L, "knowledge", "t3", "c3"),
                result(4L, "knowledge", "t4", "c4"),
                result(5L, "knowledge", "t5", "c5")
        ));

        SkillContext ctx = new SkillContext(null, "s1", 7L);
        ctx.setInjectedMemoryIds(Set.of(1L, 2L)); // top-2 of FTS already injected

        SkillResult sr = tool.execute(Map.of("userId", 7L, "query", "q", "topK", 5), ctx);

        String out = sr.getOutput();
        assertThat(out).isNotNull();
        // Drops 1 and 2; remaining 3,4,5 returned (only 3 results since pool has 5 total).
        assertThat(out).contains("Found 3 memories");
        assertThat(out).contains("id=3");
        assertThat(out).contains("id=4");
        assertThat(out).contains("id=5");
        assertThat(out).doesNotContain("id=1");
        assertThat(out).doesNotContain("id=2");
    }

    @Test
    @DisplayName("excludeIds=empty: behaves identically to legacy RRF (no filter)")
    void excludeIds_empty_noFilter() {
        when(embeddingService.embed("q")).thenReturn(Optional.empty());
        when(memoryService.searchByFts(eq(7L), eq("q"), anyInt())).thenReturn(List.of(
                result(1L, "knowledge", "t1", "c1"),
                result(2L, "knowledge", "t2", "c2")
        ));

        SkillContext ctx = new SkillContext(null, "s1", 7L);
        // injectedMemoryIds defaults to empty set — no exclusion.

        SkillResult sr = tool.execute(Map.of("userId", 7L, "query", "q", "topK", 5), ctx);

        String out = sr.getOutput();
        assertThat(out).isNotNull();
        assertThat(out).contains("Found 2 memories");
        assertThat(out).contains("id=1");
        assertThat(out).contains("id=2");
    }

    @Test
    @DisplayName("excludeIds covering all candidates → returns 'No memories found'")
    void excludeIds_coversAll_emptyResult() {
        when(embeddingService.embed("q")).thenReturn(Optional.empty());
        when(memoryService.searchByFts(eq(7L), eq("q"), anyInt())).thenReturn(List.of(
                result(1L, "knowledge", "t1", "c1"),
                result(2L, "knowledge", "t2", "c2")
        ));

        SkillContext ctx = new SkillContext(null, "s1", 7L);
        ctx.setInjectedMemoryIds(Set.of(1L, 2L));

        SkillResult sr = tool.execute(Map.of("userId", 7L, "query", "q", "topK", 5), ctx);

        assertThat(sr.getOutput()).contains("No memories found");
    }

    @Test
    @DisplayName("filter-then-limit: deep candidate surfaces when top of RRF is excluded")
    void filterThenLimit_deepCandidatesSurface() {
        when(embeddingService.embed("q")).thenReturn(Optional.empty());
        // Top FTS rank = id=10 (excluded), rank 2 = id=20 (not excluded).
        // With filter-then-limit and topK=1, we expect id=20 (NOT empty).
        when(memoryService.searchByFts(eq(7L), eq("q"), anyInt())).thenReturn(List.of(
                result(10L, "knowledge", "tA", "cA"),
                result(20L, "knowledge", "tB", "cB")
        ));

        SkillContext ctx = new SkillContext(null, "s1", 7L);
        ctx.setInjectedMemoryIds(Set.of(10L));

        SkillResult sr = tool.execute(Map.of("userId", 7L, "query", "q", "topK", 1), ctx);

        String out = sr.getOutput();
        assertThat(out).isNotNull();
        // Critical assertion: id=20 shows up because filter ran BEFORE limit.
        assertThat(out).contains("id=20");
        assertThat(out).doesNotContain("id=10");
    }

    private static MemorySearchResult result(long id, String type, String title, String content) {
        return new MemorySearchResult(id, type, title, content, 0.0);
    }
}
