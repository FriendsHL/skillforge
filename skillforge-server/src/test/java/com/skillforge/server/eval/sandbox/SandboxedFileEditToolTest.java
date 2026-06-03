package com.skillforge.server.eval.sandbox;

import com.skillforge.core.skill.SkillResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BC-M1: the sandboxed Edit must mirror {@link com.skillforge.tools.FileEditTool}
 * error/edit semantics byte-for-byte (so harvested bad cases replay the exact
 * failure signature) PLUS reject out-of-sandbox paths.
 */
class SandboxedFileEditToolTest {

    @TempDir
    Path sandboxRoot;

    private SandboxedFileEditTool tool;

    @BeforeEach
    void setUp() {
        tool = new SandboxedFileEditTool(sandboxRoot);
    }

    private Map<String, Object> input(String path, String oldStr, String newStr) {
        Map<String, Object> m = new HashMap<>();
        m.put("file_path", path);
        m.put("old_string", oldStr);
        m.put("new_string", newStr);
        return m;
    }

    @Test
    @DisplayName("rejects a path outside the sandbox root")
    void execute_outsideSandbox_denied() {
        SkillResult result = tool.execute(
                input("/etc/passwd", "root", "hacked"), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("Access denied: path is outside sandbox directory");
    }

    @Test
    @DisplayName("replaces a unique old_string and writes the file")
    void execute_uniqueMatch_replaces() throws IOException {
        Path file = sandboxRoot.resolve("a.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        SkillResult result = tool.execute(
                input(file.toString(), "world", "sandbox"), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("hello sandbox");
    }

    @Test
    @DisplayName("returns 'old_string not found in file' when missing (mirrors FileEditTool)")
    void execute_notFound_error() throws IOException {
        Path file = sandboxRoot.resolve("b.txt");
        Files.writeString(file, "abc def", StandardCharsets.UTF_8);

        SkillResult result = tool.execute(
                input(file.toString(), "zzz", "qqq"), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("old_string not found in file");
    }

    @Test
    @DisplayName("returns the not-unique error with occurrence count (mirrors FileEditTool)")
    void execute_notUnique_error() throws IOException {
        Path file = sandboxRoot.resolve("c.txt");
        Files.writeString(file, "x x x", StandardCharsets.UTF_8);

        SkillResult result = tool.execute(
                input(file.toString(), "x", "y"), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo(
                "old_string is not unique in the file (found 3 occurrences). "
                        + "Use replace_all=true or provide more context.");
    }

    @Test
    @DisplayName("replace_all rewrites every occurrence")
    void execute_replaceAll_rewritesAll() throws IOException {
        Path file = sandboxRoot.resolve("d.txt");
        Files.writeString(file, "x x x", StandardCharsets.UTF_8);
        Map<String, Object> in = input(file.toString(), "x", "y");
        in.put("replace_all", true);

        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("y y y");
    }

    @Test
    @DisplayName("returns 'File does not exist' for an in-sandbox missing file (mirrors FileEditTool)")
    void execute_missingFile_error() {
        Path file = sandboxRoot.resolve("missing.txt");

        SkillResult result = tool.execute(
                input(file.toString(), "a", "b"), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("File does not exist: " + file);
    }
}
