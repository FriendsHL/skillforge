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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link MemorySearchTool} pulls userId from {@link SkillContext} and forwards it
 * to {@code MemoryService.searchByFts}; surfaces a clean error when context userId is missing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemorySearchTool — context-userId migration")
class MemorySearchToolUserIdTest {

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
    @DisplayName("execute: userId from context forwarded to MemoryService.searchByFts")
    void execute_userIdFromContext_passesToService() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);
        when(embeddingService.embed("hello")).thenReturn(Optional.empty());
        when(memoryService.searchByFts(eq(42L), eq("hello"), anyInt())).thenReturn(List.of(
                new MemorySearchResult(1L, "knowledge", "title-1", "content-1", 0.0)
        ));

        SkillResult res = tool.execute(Map.of("query", "hello"), ctx);

        assertThat(res.isSuccess()).isTrue();
        verify(memoryService).searchByFts(eq(42L), eq("hello"), anyInt());
    }

    @Test
    @DisplayName("execute: null context userId → error + searchByFts NOT called")
    void execute_nullContextUserId_returnsError() {
        SkillContext ctx = new SkillContext(null, "s1", null);

        SkillResult res = tool.execute(Map.of("query", "hello"), ctx);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("User context is missing");
        verify(memoryService, never()).searchByFts(any(), any(), anyInt());
    }

    @Test
    @DisplayName("execute: missing context entirely → error + searchByFts NOT called")
    void execute_nullContext_returnsError() {
        SkillResult res = tool.execute(Map.of("query", "hello"), null);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("User context is missing");
        verify(memoryService, never()).searchByFts(any(), any(), anyInt());
    }

    @Test
    @DisplayName("execute: blank query → 'query is required' error (userId is fine)")
    void execute_blankQuery_returnsError() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);

        SkillResult res = tool.execute(Map.of("query", "  "), ctx);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("query is required");
        verify(memoryService, never()).searchByFts(any(), any(), anyInt());
    }

    @Test
    @DisplayName("toolSchema: userId is no longer in properties or required (only 'query' is required)")
    @SuppressWarnings("unchecked")
    void toolSchema_userIdRemoved() {
        Map<String, Object> schema = tool.getToolSchema().getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.get("required");

        assertThat(properties).doesNotContainKey("userId");
        assertThat(required).containsExactly("query").doesNotContain("userId");
    }
}
