package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SKILL-IMPORT — unit tests for {@link ImportSkillTool}. Covers AC-1 input
 * parsing, validation errors for missing fields / invalid source / missing
 * authentication, and the success path that delegates to {@link SkillImportService}.
 */
@ExtendWith(MockitoExtension.class)
class ImportSkillToolTest {

    @Mock
    private SkillImportService importService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ImportSkillTool tool;

    @BeforeEach
    void setUp() {
        tool = new ImportSkillTool(importService, objectMapper);
    }

    @Test
    @DisplayName("execute_clawhubInput_delegatesToService")
    void execute_clawhubInput_delegatesToService() {
        ImportResult expected = new ImportResult(
                42L, "tool-call-retry", "/data/skills/clawhub/tool-call-retry/1.0.0",
                "clawhub", false);
        when(importService.importSkill(any(Path.class), eq(SkillSource.CLAWHUB), eq(7L)))
                .thenReturn(expected);

        Map<String, Object> input = new HashMap<>();
        input.put("sourcePath", "/Users/x/.openclaw/workspace/skills/tool-call-retry");
        input.put("source", "clawhub");

        SkillResult result = tool.execute(input, ctx(7L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"id\":42");
        assertThat(result.getOutput()).contains("\"name\":\"tool-call-retry\"");
        assertThat(result.getOutput()).contains("\"source\":\"clawhub\"");
        assertThat(result.getOutput()).contains("\"conflictResolved\":false");

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(importService).importSkill(pathCaptor.capture(), eq(SkillSource.CLAWHUB), eq(7L));
        assertThat(pathCaptor.getValue().toString())
                .isEqualTo("/Users/x/.openclaw/workspace/skills/tool-call-retry");
    }

    @Test
    @DisplayName("execute_tildePrefixedSourcePath_expandsToUserHome")
    void execute_tildePrefixedSourcePath_expandsToUserHome() {
        when(importService.importSkill(any(Path.class), eq(SkillSource.CLAWHUB), eq(1L)))
                .thenReturn(new ImportResult(1L, "n", "/p", "clawhub", false));

        Map<String, Object> input = new HashMap<>();
        input.put("sourcePath", "~/skills/foo");
        input.put("source", "clawhub");

        SkillResult result = tool.execute(input, ctx(1L));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(importService).importSkill(pathCaptor.capture(), eq(SkillSource.CLAWHUB), eq(1L));
        assertThat(pathCaptor.getValue().toString())
                .isEqualTo(System.getProperty("user.home") + "/skills/foo");
    }

    @Test
    @DisplayName("execute_missingSourcePath_returnsValidationError")
    void execute_missingSourcePath_returnsValidationError() {
        Map<String, Object> input = new HashMap<>();
        input.put("source", "clawhub");

        SkillResult result = tool.execute(input, ctx(1L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        verifyNoInteractions(importService);
    }

    @Test
    @DisplayName("execute_blankSource_returnsValidationError")
    void execute_blankSource_returnsValidationError() {
        Map<String, Object> input = new HashMap<>();
        input.put("sourcePath", "/abs/path");
        input.put("source", "  ");

        SkillResult result = tool.execute(input, ctx(1L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        verifyNoInteractions(importService);
    }

    @Test
    @DisplayName("execute_unknownSource_returnsValidationError")
    void execute_unknownSource_returnsValidationError() {
        Map<String, Object> input = new HashMap<>();
        input.put("sourcePath", "/abs/path");
        input.put("source", "not-a-real-marketplace");

        SkillResult result = tool.execute(input, ctx(1L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("clawhub | github | skillhub | filesystem");
        verifyNoInteractions(importService);
    }

    @Test
    @DisplayName("execute_missingUserId_returnsExecutionError")
    void execute_missingUserId_returnsExecutionError() {
        Map<String, Object> input = new HashMap<>();
        input.put("sourcePath", "/abs/path");
        input.put("source", "clawhub");

        SkillContext ctx = new SkillContext();
        SkillResult result = tool.execute(input, ctx);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.EXECUTION);
        verify(importService, never()).importSkill(any(), any(), anyLong());
    }

    @Test
    @DisplayName("execute_serviceThrowsIllegalArgument_returnsExecutionError")
    void execute_serviceThrowsIllegalArgument_returnsExecutionError() {
        when(importService.importSkill(any(Path.class), eq(SkillSource.CLAWHUB), eq(1L)))
                .thenThrow(new IllegalArgumentException("sourcePath not in allowed roots: /etc"));

        Map<String, Object> input = new HashMap<>();
        input.put("sourcePath", "/etc/passwd");
        input.put("source", "clawhub");

        SkillResult result = tool.execute(input, ctx(1L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.EXECUTION);
        assertThat(result.getError()).contains("sourcePath not in allowed roots");
    }

    @Test
    @DisplayName("getName + getDescription expose intended surface")
    void getName_getDescription_exposeIntendedSurface() {
        assertThat(tool.getName()).isEqualTo("ImportSkill");
        // Description should describe ClawHub / GitHub / SkillHub registration intent.
        assertThat(tool.getDescription()).contains("ClawHub").contains("SkillForge");
        assertThat(tool.getToolSchema().getName()).isEqualTo("ImportSkill");
        assertThat(tool.getToolSchema().getInputSchema()).containsKey("properties");
    }

    private static SkillContext ctx(Long userId) {
        return new SkillContext("/", "session-1", userId);
    }
}
