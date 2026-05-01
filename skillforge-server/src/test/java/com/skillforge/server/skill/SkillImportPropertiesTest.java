package com.skillforge.server.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL-IMPORT — covers AC-8: tilde expansion + multi-root + blank-skip behaviour
 * of {@link SkillImportProperties#resolvedAllowedRoots()}.
 */
class SkillImportPropertiesTest {

    private final String userHome = System.getProperty("user.home");

    @Test
    @DisplayName("resolvedAllowedRoots expands ~ to user home")
    void resolvedAllowedRoots_tildeRoot_expandsToUserHome() {
        SkillImportProperties props = new SkillImportProperties();
        props.setAllowedSourceRoots(List.of("~/.openclaw/workspace/skills"));

        List<Path> resolved = props.resolvedAllowedRoots();

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0))
                .isEqualTo(Paths.get(userHome, ".openclaw/workspace/skills").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("resolvedAllowedRoots returns multiple normalised roots")
    void resolvedAllowedRoots_multipleRoots_allResolved() {
        SkillImportProperties props = new SkillImportProperties();
        props.setAllowedSourceRoots(List.of(
                "~/.openclaw/workspace/skills",
                "/var/lib/skills",
                "~/.skill-hub/skills"));

        List<Path> resolved = props.resolvedAllowedRoots();

        assertThat(resolved).hasSize(3);
        assertThat(resolved.get(0))
                .isEqualTo(Paths.get(userHome, ".openclaw/workspace/skills").toAbsolutePath().normalize());
        assertThat(resolved.get(1))
                .isEqualTo(Paths.get("/var/lib/skills").toAbsolutePath().normalize());
        assertThat(resolved.get(2))
                .isEqualTo(Paths.get(userHome, ".skill-hub/skills").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("resolvedAllowedRoots skips blank / null entries")
    void resolvedAllowedRoots_blankEntries_skipped() {
        SkillImportProperties props = new SkillImportProperties();
        props.setAllowedSourceRoots(java.util.Arrays.asList("", "   ", null, "/tmp/skills"));

        List<Path> resolved = props.resolvedAllowedRoots();

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isEqualTo(Paths.get("/tmp/skills").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("resolvedAllowedRoots returns empty list when no roots configured")
    void resolvedAllowedRoots_emptyConfig_emptyList() {
        SkillImportProperties props = new SkillImportProperties();
        // setter with null collapses to empty list per the bean contract.
        props.setAllowedSourceRoots(null);

        assertThat(props.resolvedAllowedRoots()).isEmpty();
    }

    @Test
    @DisplayName("setAllowedSourceRoots(null) does not NPE on subsequent reads")
    void setAllowedSourceRoots_null_doesNotThrow() {
        SkillImportProperties props = new SkillImportProperties();
        props.setAllowedSourceRoots(null);

        assertThat(props.getAllowedSourceRoots()).isNotNull().isEmpty();
        assertThat(props.resolvedAllowedRoots()).isEmpty();
    }
}
