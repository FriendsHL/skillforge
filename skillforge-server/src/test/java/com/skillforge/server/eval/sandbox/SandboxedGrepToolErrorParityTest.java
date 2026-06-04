package com.skillforge.server.eval.sandbox;

import com.skillforge.core.skill.SkillResult;
import com.skillforge.tools.GrepTool;
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
 * Anti-drift CI guard: the eval sandbox's Grep MUST emit a byte-identical
 * "Path is not a directory" error to the production {@link GrepTool} for the
 * failure a harvested Grep bad case replays (a search path that resolves to a
 * file rather than a directory). Feeding the same input to both tools and
 * asserting {@code getError()} equality turns the "errors match" promise from a
 * comment into a test — a future prod wording change breaks CI instead of
 * silently making harvested scenarios reproduce a different (or no) failure.
 */
class SandboxedGrepToolErrorParityTest {

    @TempDir
    Path root;

    private final GrepTool prodTool = new GrepTool();

    private SandboxedGrepTool sandboxTool() {
        return new SandboxedGrepTool(root);
    }

    private Map<String, Object> input(Path path) {
        Map<String, Object> m = new HashMap<>();
        m.put("pattern", "anything");
        m.put("path", path.toString());
        return m;
    }

    @Test
    @DisplayName("'Path is not a directory: ...' is byte-identical across both tools (path is a file)")
    void parity_pathIsNotADirectory() throws IOException {
        // A real file inside the sandbox — exists, but is not a directory.
        Path file = root.resolve("notdir.txt");
        Files.writeString(file, "alpha beta", StandardCharsets.UTF_8);

        SkillResult prod = prodTool.execute(input(file), null);
        SkillResult sandbox = sandboxTool().execute(input(file), null);

        assertThat(prod.isSuccess()).isFalse();
        assertThat(sandbox.isSuccess()).isFalse();
        assertThat(sandbox.getError()).isEqualTo(prod.getError());
        assertThat(sandbox.getError()).contains("Path is not a directory");
        assertThat(sandbox.getError()).isEqualTo("Path is not a directory: " + file);
    }
}
