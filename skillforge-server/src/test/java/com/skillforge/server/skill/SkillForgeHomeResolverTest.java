package com.skillforge.server.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillForgeHomeResolverTest {

    @Test
    @DisplayName("env var override takes precedence")
    void resolve_envVarSet_returnsEnvPath(@TempDir Path tmp) {
        Path forced = tmp.resolve("skillforge-home").toAbsolutePath();
        SkillForgeHomeResolver resolver = new SkillForgeHomeResolver(forced.toString());

        assertThat(resolver.resolve()).isEqualTo(forced.normalize());
        assertThat(resolver.getRuntimeRoot()).isEqualTo(forced.resolve("data/skills").normalize());
        assertThat(resolver.getSystemSkillsDir())
                .isEqualTo(forced.resolveSibling("system-skills").normalize());
    }

    @Test
    @DisplayName("project-root auto-detect: pom.xml + skillforge-server/ both required")
    void resolve_envBlank_findsProjectRoot() {
        // Running inside the SkillForge repo itself: cwd is the project root which
        // satisfies both anchors (pom.xml + skillforge-server/). Resolution must
        // succeed and yield <projectRoot>/skillforge-server.
        SkillForgeHomeResolver resolver = new SkillForgeHomeResolver("");
        assertThat(resolver.resolve().getFileName().toString()).isEqualTo("skillforge-server");
        assertThat(Files.exists(resolver.resolve().resolve("pom.xml"))
                || Files.exists(resolver.resolve().getParent().resolve("pom.xml"))).isTrue();
    }

    @Test
    @DisplayName("postConstruct creates runtime root if missing")
    void postConstruct_createsRuntimeRoot(@TempDir Path tmp) {
        Path forced = tmp.resolve("home");
        SkillForgeHomeResolver resolver = new SkillForgeHomeResolver(forced.toString());
        // runtime root not yet created
        assertThat(Files.exists(resolver.getRuntimeRoot())).isFalse();

        resolver.postConstruct();

        assertThat(Files.isDirectory(resolver.getRuntimeRoot())).isTrue();
    }

    @Test
    @DisplayName("fail-fast: env var blank AND no pom.xml ancestor → IllegalStateException")
    void resolve_envBlankNoProjectRoot_throws(@TempDir Path tmp) throws IOException {
        // Use an isolated temp dir as start path. JUnit's @TempDir is under
        // /var/folders/... or equivalent — none of those ancestors contain
        // pom.xml + skillforge-server/, so the walk-up exhausts and throws.
        Path isolated = Files.createDirectory(tmp.resolve("isolated"));

        assertThatThrownBy(() -> new SkillForgeHomeResolver(null, isolated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SKILLFORGE_HOME");
    }

    @Test
    @DisplayName("fail-fast: pom.xml present but skillforge-server/ missing → continues walking, throws if exhausted")
    void resolve_pomXmlOnly_skipsAndContinues(@TempDir Path tmp) throws IOException {
        // Anchor must be BOTH pom.xml AND skillforge-server/ — verify pom.xml
        // alone is insufficient (defends against multi-module false-match).
        Path fakeRoot = Files.createDirectory(tmp.resolve("fake-root"));
        Files.createFile(fakeRoot.resolve("pom.xml"));
        // No skillforge-server/ subdir — should continue walking up.
        Path isolated = Files.createDirectory(fakeRoot.resolve("inner"));

        assertThatThrownBy(() -> new SkillForgeHomeResolver(null, isolated))
                .isInstanceOf(IllegalStateException.class);
    }
}
