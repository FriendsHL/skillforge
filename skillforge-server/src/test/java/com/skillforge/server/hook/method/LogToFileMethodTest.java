package com.skillforge.server.hook.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogToFileMethod}.
 */
class LogToFileMethodTest {

    @TempDir
    Path tempDir;

    private LogToFileMethod method;
    private ObjectMapper objectMapper;

    private static final HookExecutionContext CTX = new HookExecutionContext(
            "sess-1", 7L, HookEvent.SESSION_START,
            Map.of("_hook_origin", "lifecycle:SessionStart"));

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        method = new LogToFileMethod(objectMapper, tempDir.toString());
    }

    @Test
    @DisplayName("ref returns builtin.log.file")
    void ref_returnsExpected() {
        assertThat(method.ref()).isEqualTo("builtin.log.file");
    }

    @Test
    @DisplayName("execute writes JSON line to file")
    void execute_happyPath_writesJsonLine() throws Exception {
        Map<String, Object> args = Map.of("path", "test.log");

        HookRunResult result = method.execute(args, CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("logged to");
        Path logFile = tempDir.resolve("test.log");
        assertThat(Files.exists(logFile)).isTrue();
        String content = Files.readString(logFile);
        assertThat(content).contains("\"timestamp\"");
        assertThat(content).contains("\"sessionId\":\"sess-1\"");
        assertThat(content).contains("\"event\":\"SessionStart\"");
    }

    @Test
    @DisplayName("execute creates parent directories if needed")
    void execute_createsParentDirs() throws Exception {
        Map<String, Object> args = Map.of("path", "subdir/deep/test.log");

        HookRunResult result = method.execute(args, CTX);

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(tempDir.resolve("subdir/deep/test.log"))).isTrue();
    }

    @Test
    @DisplayName("execute rejects path with ..")
    void execute_pathTraversal_rejected() {
        Map<String, Object> args = Map.of("path", "../escape.log");

        HookRunResult result = method.execute(args, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("path traversal rejected");
    }

    @Test
    @DisplayName("execute rejects missing path argument")
    void execute_missingPath_returnsFailure() {
        Map<String, Object> args = Map.of();

        HookRunResult result = method.execute(args, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("path is required");
    }

    @Test
    @DisplayName("execute rejects blank path argument")
    void execute_blankPath_returnsFailure() {
        Map<String, Object> args = Map.of("path", "  ");

        HookRunResult result = method.execute(args, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("path is required");
    }

    @Test
    @DisplayName("execute appends to existing file on multiple calls")
    void execute_appendsToExistingFile() throws Exception {
        Map<String, Object> args = Map.of("path", "append.log");

        method.execute(args, CTX);
        method.execute(args, CTX);

        String content = Files.readString(tempDir.resolve("append.log"));
        long lineCount = content.lines().count();
        assertThat(lineCount).isEqualTo(2);
    }
}
