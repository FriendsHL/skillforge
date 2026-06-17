package com.skillforge.server.eval.sandbox;

import com.skillforge.core.skill.SkillResult;
import com.skillforge.tools.FileEditTool;
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
 * BC-M1 anti-drift CI guard: the eval sandbox's Edit MUST emit byte-identical
 * error strings to the production {@link FileEditTool} for the failure modes a
 * harvested bad case replays. Feeding the same input to both tools and asserting
 * {@code getError()} equality turns the "errors match" promise from a comment
 * into a test — a future prod wording change breaks CI instead of silently
 * making harvested scenarios reproduce a different (or no) failure.
 */
class SandboxedFileEditToolErrorParityTest {

    @TempDir
    Path root;

    private final FileEditTool prodTool = new FileEditTool();

    private SandboxedFileEditTool sandboxTool() {
        return new SandboxedFileEditTool(root);
    }

    private Map<String, Object> input(Path file, String oldStr, String newStr) {
        Map<String, Object> m = new HashMap<>();
        m.put("file_path", file.toString());
        m.put("old_string", oldStr);
        m.put("new_string", newStr);
        return m;
    }

    @Test
    @DisplayName("'old_string not found in file' is byte-identical across both tools")
    void parity_notFound() throws IOException {
        Path file = root.resolve("nf.txt");
        Files.writeString(file, "alpha beta", StandardCharsets.UTF_8);

        SkillResult prod = prodTool.execute(input(file, "zzz", "qqq"), null);
        // Re-create the file: prod run did not mutate it (error path), but be explicit.
        Files.writeString(file, "alpha beta", StandardCharsets.UTF_8);
        SkillResult sandbox = sandboxTool().execute(input(file, "zzz", "qqq"), null);

        assertThat(prod.isSuccess()).isFalse();
        assertThat(sandbox.isSuccess()).isFalse();
        assertThat(sandbox.getError()).isEqualTo(prod.getError());
        assertThat(sandbox.getError()).isEqualTo("old_string not found in file");
    }

    @Test
    @DisplayName("'old_string is not unique ...' is byte-identical across both tools")
    void parity_notUnique() throws IOException {
        Path file = root.resolve("nu.txt");
        Files.writeString(file, "x x x", StandardCharsets.UTF_8);

        SkillResult prod = prodTool.execute(input(file, "x", "y"), null);
        Files.writeString(file, "x x x", StandardCharsets.UTF_8);
        SkillResult sandbox = sandboxTool().execute(input(file, "x", "y"), null);

        assertThat(prod.isSuccess()).isFalse();
        assertThat(sandbox.isSuccess()).isFalse();
        assertThat(sandbox.getError()).isEqualTo(prod.getError());
        assertThat(sandbox.getError()).isEqualTo(
                "old_string is not unique in the file (found 3 occurrences). "
                        + "Use replace_all=true or provide more context.");
    }

    @Test
    @DisplayName("'File does not exist: ...' is byte-identical across both tools")
    void parity_fileDoesNotExist() {
        Path file = root.resolve("missing.txt");

        SkillResult prod = prodTool.execute(input(file, "a", "b"), null);
        SkillResult sandbox = sandboxTool().execute(input(file, "a", "b"), null);

        assertThat(prod.isSuccess()).isFalse();
        assertThat(sandbox.isSuccess()).isFalse();
        assertThat(sandbox.getError()).isEqualTo(prod.getError());
        assertThat(sandbox.getError()).isEqualTo("File does not exist: " + file);
    }
}
