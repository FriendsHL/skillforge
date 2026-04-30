package com.skillforge.server.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * P1-D — Resolves the SkillForge home directory used as anchor for runtime skill
 * artifacts and system skill discovery.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code SKILLFORGE_HOME} env var (deployment-friendly absolute path)</li>
 *   <li>Walk up from current working dir to find an ancestor that contains both
 *       {@code pom.xml} <b>and</b> a {@code skillforge-server/} subdir
 *       (project-root anchor) — yields {@code <projectRoot>/skillforge-server}.
 *       Both conditions are required so a multi-module repo doesn't false-match
 *       a sub-module pom.</li>
 *   <li>Fail-fast {@link IllegalStateException} (no silent fallback)</li>
 * </ol>
 *
 * <p>Anchor:
 * <ul>
 *   <li>{@link #getRuntimeRoot()} = {@code <home>/data/skills}</li>
 *   <li>{@link #getSystemSkillsDir()} = {@code <home>/../system-skills}</li>
 * </ul>
 *
 * <p>{@link #postConstruct()} ensures the runtime root exists or is creatable —
 * the directory is created eagerly so write paths can rely on its presence.
 * Creation failures are fatal (covers PRD AC-4).
 */
@Component
public class SkillForgeHomeResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillForgeHomeResolver.class);
    private static final String ENV_VAR = "SKILLFORGE_HOME";
    private static final String RUNTIME_DIR_NAME = "data/skills";
    private static final String SYSTEM_SKILLS_REL_PATH = "../system-skills";

    private final Path home;

    public SkillForgeHomeResolver() {
        this(System.getenv(ENV_VAR));
    }

    /** Visible for tests — injects an explicit env-var equivalent. */
    SkillForgeHomeResolver(String envOverride) {
        this(envOverride, Paths.get("").toAbsolutePath().normalize());
    }

    /**
     * Visible for tests — injects both env override and the starting path used
     * for the project-root walk-up. Production callers always start from the
     * JVM's working directory.
     */
    SkillForgeHomeResolver(String envOverride, Path startPath) {
        this.home = resolveHome(envOverride, startPath);
    }

    private static Path resolveHome(String envValue, Path startPath) {
        if (envValue != null && !envValue.isBlank()) {
            return Paths.get(envValue).toAbsolutePath().normalize();
        }
        Path cur = startPath != null ? startPath.toAbsolutePath().normalize() : null;
        while (cur != null) {
            if (Files.exists(cur.resolve("pom.xml"))
                    && Files.isDirectory(cur.resolve("skillforge-server"))) {
                return cur.resolve("skillforge-server").normalize();
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException(
                "Cannot resolve SKILLFORGE_HOME — set the SKILLFORGE_HOME env var "
                        + "or run from the project root (parent dir of skillforge-server/)");
    }

    @PostConstruct
    void postConstruct() {
        Path runtime = getRuntimeRoot();
        log.info("SkillForge home resolved: home={} runtimeRoot={} systemSkillsDir={}",
                home, runtime, getSystemSkillsDir());
        try {
            Files.createDirectories(runtime);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create runtime skills directory: " + runtime, e);
        }
    }

    /** Absolute path to SkillForge home (the anchor for runtime + system roots). */
    public Path resolve() {
        return home;
    }

    /** Absolute path to the runtime skill artifact root: {@code <home>/data/skills}. */
    public Path getRuntimeRoot() {
        return home.resolve(RUNTIME_DIR_NAME).normalize();
    }

    /** Absolute path to the system-skills directory: {@code <home>/../system-skills}. */
    public Path getSystemSkillsDir() {
        return home.resolve(SYSTEM_SKILLS_REL_PATH).normalize();
    }
}
