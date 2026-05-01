package com.skillforge.server.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SKILL-IMPORT — configuration for the {@code ImportSkill} tool whitelist.
 *
 * <p>{@code allowedSourceRoots} entries support a leading {@code ~} which is
 * expanded to {@code System.getProperty("user.home")} at resolve time. Each
 * entry is normalised to an absolute path; callers compare {@code sourcePath}
 * against this list using {@link Path#startsWith(Path)}.
 *
 * <p>Configured under {@code skillforge.skill-import.allowed-source-roots}.
 * Adding a new root only requires a config + restart, no code change.
 */
@ConfigurationProperties(prefix = "skillforge.skill-import")
public class SkillImportProperties {

    private List<String> allowedSourceRoots = new ArrayList<>();

    public List<String> getAllowedSourceRoots() {
        return allowedSourceRoots;
    }

    public void setAllowedSourceRoots(List<String> allowedSourceRoots) {
        this.allowedSourceRoots = allowedSourceRoots != null
                ? allowedSourceRoots : new ArrayList<>();
    }

    /**
     * Resolve the configured roots: expand leading {@code ~} to the JVM user
     * home, convert to absolute paths, normalise. Blank / null entries are
     * skipped. Returns an unmodifiable list so callers cannot mutate the bean.
     */
    public List<Path> resolvedAllowedRoots() {
        if (allowedSourceRoots == null || allowedSourceRoots.isEmpty()) {
            return Collections.emptyList();
        }
        String home = System.getProperty("user.home");
        List<Path> resolved = new ArrayList<>(allowedSourceRoots.size());
        for (String raw : allowedSourceRoots) {
            if (raw == null || raw.isBlank()) continue;
            String expanded;
            if (raw.startsWith("~/") || raw.equals("~")) {
                expanded = home + raw.substring(1);
            } else {
                expanded = raw;
            }
            resolved.add(Paths.get(expanded).toAbsolutePath().normalize());
        }
        return Collections.unmodifiableList(resolved);
    }
}
