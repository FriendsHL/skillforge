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
 * Anti-drift CI guard: production and eval Grep both accept a concrete file path.
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
        m.put("pattern", "alpha");
        m.put("path", path.toString());
        return m;
    }

    @Test
    @DisplayName("concrete file search succeeds identically across production and sandbox tools")
    void parity_filePathIsSearchable() throws IOException {
        Path file = root.resolve("notdir.txt");
        Files.writeString(file, "alpha beta", StandardCharsets.UTF_8);

        SkillResult prod = prodTool.execute(input(file), null);
        SkillResult sandbox = sandboxTool().execute(input(file), null);

        assertThat(prod.isSuccess()).isTrue();
        assertThat(sandbox.isSuccess()).isTrue();
        assertThat(sandbox.getOutput()).isEqualTo(prod.getOutput());
    }
}
