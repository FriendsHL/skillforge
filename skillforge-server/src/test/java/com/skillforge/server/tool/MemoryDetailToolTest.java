package com.skillforge.server.tool;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link MemoryDetailTool} enforces ownership using {@link SkillContext#getUserId()}
 * and surfaces a clean error when context userId is missing or the memory belongs to a different user.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryDetailTool — ownership guard via context userId")
class MemoryDetailToolTest {

    @Mock
    private MemoryService memoryService;

    private MemoryDetailTool tool;

    @BeforeEach
    void setUp() {
        tool = new MemoryDetailTool(memoryService);
    }

    @Test
    @DisplayName("execute: owner matches → returns full content")
    void execute_sameUser_returnsContent() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);
        MemoryEntity m = new MemoryEntity();
        m.setId(7L);
        m.setUserId(42L);
        m.setType("knowledge");
        m.setTitle("Spring Boot tips");
        m.setContent("Use @Cacheable carefully");
        when(memoryService.findById(7L)).thenReturn(Optional.of(m));

        SkillResult res = tool.execute(Map.of("memoryId", 7L), ctx);

        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getOutput()).contains("Spring Boot tips");
        assertThat(res.getOutput()).contains("Use @Cacheable carefully");
    }

    @Test
    @DisplayName("execute: cross-user attempt → indistinguishable 'Memory not found' (IDOR guard)")
    void execute_crossUser_rejected() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);
        MemoryEntity m = new MemoryEntity();
        m.setId(7L);
        m.setUserId(99L);
        m.setType("knowledge");
        m.setTitle("Other user secret");
        m.setContent("Confidential");
        when(memoryService.findById(7L)).thenReturn(Optional.of(m));

        SkillResult res = tool.execute(Map.of("memoryId", 7L), ctx);

        assertThat(res.isSuccess()).isFalse();
        // F2: cross-user must surface the same "not found" message as a truly-absent id
        // — defeats IDOR enumeration via response-text divergence.
        assertThat(res.getError()).contains("Memory not found");
        assertThat(res.getError()).doesNotContain("does not belong");
        // Critical: content/title must NOT leak through the error message.
        assertThat(res.getError()).doesNotContain("Confidential");
        assertThat(res.getError()).doesNotContain("Other user secret");
    }

    @Test
    @DisplayName("execute: null context userId → error + findById NOT called")
    void execute_nullContextUserId_returnsError() {
        SkillContext ctx = new SkillContext(null, "s1", null);

        SkillResult res = tool.execute(Map.of("memoryId", 7L), ctx);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("User context is missing");
        verify(memoryService, never()).findById(anyLong());
    }

    @Test
    @DisplayName("execute: missing context entirely → error + findById NOT called")
    void execute_nullContext_returnsError() {
        SkillResult res = tool.execute(Map.of("memoryId", 7L), null);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("User context is missing");
        verify(memoryService, never()).findById(anyLong());
    }

    @Test
    @DisplayName("execute: memory not found → error 'Memory not found'")
    void execute_memoryNotFound_returnsError() {
        SkillContext ctx = new SkillContext(null, "s1", 42L);
        when(memoryService.findById(7L)).thenReturn(Optional.empty());

        SkillResult res = tool.execute(Map.of("memoryId", 7L), ctx);

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getError()).contains("Memory not found: id=7");
    }
}
