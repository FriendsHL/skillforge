package com.skillforge.server.tool;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link MemoryTool} pulls userId from {@link SkillContext} (LLM no longer passes it),
 * enforces ownership on delete, and surfaces a clean error when context userId is missing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryTool — context-userId migration + ownership guard")
class MemoryToolTest {

    @Mock
    private MemoryService memoryService;

    private MemoryTool tool;

    @BeforeEach
    void setUp() {
        tool = new MemoryTool(memoryService);
    }

    // ─── save ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("handleSave: userId taken from SkillContext, persisted on the entity")
    void handleSave_userIdFromContext_succeeds() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);

        MemoryEntity saved = new MemoryEntity();
        saved.setId(101L);
        saved.setUserId(42L);
        saved.setTitle("prefers concise output");
        when(memoryService.createMemory(org.mockito.ArgumentMatchers.any(MemoryEntity.class)))
                .thenReturn(saved);

        Map<String, Object> input = new HashMap<>();
        input.put("action", "save");
        input.put("type", "preference");
        input.put("title", "prefers concise output");
        input.put("content", "User likes terse replies");
        // NOTE: no userId in input — context provides it.

        SkillResult res = tool.execute(input, ctx);

        assertThat(res.isSuccess()).isTrue();
        ArgumentCaptor<MemoryEntity> captor = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(memoryService).createMemory(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(42L);
        assertThat(captor.getValue().getType()).isEqualTo("preference");
    }

    @Test
    @DisplayName("handleSave: null context userId → error 'User context is missing'")
    void handleSave_nullContextUserId_returnsError() {
        SkillContext ctx = new SkillContext(null, "s1", null);

        Map<String, Object> input = new HashMap<>();
        input.put("action", "save");
        input.put("type", "preference");
        input.put("title", "x");
        input.put("content", "y");

        SkillResult res = tool.execute(input, ctx);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("User context is missing");
        verify(memoryService, never()).createMemory(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("handleSave: missing context entirely → error 'User context is missing'")
    void handleSave_nullContext_returnsError() {
        Map<String, Object> input = new HashMap<>();
        input.put("action", "save");
        input.put("type", "preference");
        input.put("title", "x");
        input.put("content", "y");

        SkillResult res = tool.execute(input, null);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("User context is missing");
        verify(memoryService, never()).createMemory(org.mockito.ArgumentMatchers.any());
    }

    // ─── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("handleDelete: owner matches → deleteMemory called")
    void handleDelete_sameUser_succeeds() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);

        MemoryEntity existing = new MemoryEntity();
        existing.setId(7L);
        existing.setUserId(42L);
        when(memoryService.findById(7L)).thenReturn(Optional.of(existing));

        SkillResult res = tool.execute(Map.of("action", "delete", "memoryId", 7L), ctx);

        assertThat(res.isSuccess()).isTrue();
        verify(memoryService).deleteMemory(7L);
    }

    @Test
    @DisplayName("handleDelete: cross-user attempt → indistinguishable 'Memory not found' (IDOR guard)")
    void handleDelete_crossUser_rejected() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);

        MemoryEntity existing = new MemoryEntity();
        existing.setId(7L);
        existing.setUserId(99L);
        when(memoryService.findById(7L)).thenReturn(Optional.of(existing));

        SkillResult res = tool.execute(Map.of("action", "delete", "memoryId", 7L), ctx);

        assertThat(res.isSuccess()).isFalse();
        // F2: cross-user must surface the same "not found" message as a truly-absent id
        // — defeats IDOR enumeration via response-text divergence.
        assertThat(res.getError()).contains("Memory not found");
        assertThat(res.getError()).doesNotContain("does not belong");
        verify(memoryService, never()).deleteMemory(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("handleDelete: memory not found → error + deleteMemory NOT called")
    void handleDelete_memoryNotFound_returnsError() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);

        when(memoryService.findById(7L)).thenReturn(Optional.empty());

        SkillResult res = tool.execute(Map.of("action", "delete", "memoryId", 7L), ctx);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("Memory not found: id=7");
        verify(memoryService, never()).deleteMemory(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("handleDelete: null context userId → error + no lookup, no delete")
    void handleDelete_nullContextUserId_returnsError() {
        SkillContext ctx = new SkillContext(null, "s1", null);

        SkillResult res = tool.execute(Map.of("action", "delete", "memoryId", 7L), ctx);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("User context is missing");
        verify(memoryService, never()).findById(org.mockito.ArgumentMatchers.anyLong());
        verify(memoryService, never()).deleteMemory(org.mockito.ArgumentMatchers.anyLong());
    }

    // ─── schema shape ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("toolSchema: userId is no longer in properties or required")
    @SuppressWarnings("unchecked")
    void toolSchema_userIdRemoved() {
        Map<String, Object> schema = tool.getToolSchema().getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.get("required");

        assertThat(properties).doesNotContainKey("userId");
        assertThat(required).containsExactly("action").doesNotContain("userId");
    }
}
