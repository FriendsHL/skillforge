package com.skillforge.server.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * P1-D — Single chokepoint for runtime skill artifact path allocation.
 *
 * <p>All server-side write entries (upload / SkillCreator / SkillDraft.approve /
 * EvolutionService.fork) ask this service for a target {@link Path}, then write
 * the SKILL.md package into it. Centralizing the policy here lets us change the
 * directory layout (or apply path-traversal hardening) in one place.
 *
 * <p>Path-traversal hardening: every input segment is validated to reject
 * {@code ..}, absolute paths, separators, and null bytes. The final allocated
 * path is asserted to start with {@link #getRuntimeRoot()} as a defense-in-depth
 * check.
 */
@Service
public class SkillStorageService {

    private static final Logger log = LoggerFactory.getLogger(SkillStorageService.class);

    private final SkillForgeHomeResolver homeResolver;

    public SkillStorageService(SkillForgeHomeResolver homeResolver) {
        this.homeResolver = homeResolver;
    }

    /**
     * Compute the absolute on-disk destination directory for a new runtime
     * skill artifact. Does not create the directory — call
     * {@link #ensureDirectories(Path)} after validating the package.
     *
     * @throws IllegalArgumentException if any input segment is invalid (path
     *         traversal attempt, missing required field for the source, etc.)
     */
    public Path allocate(SkillSource source, AllocationContext ctx) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ctx, "ctx");

        Path root = getRuntimeRoot();
        Path resolved = switch (source) {
            case UPLOAD -> root.resolve("upload")
                    .resolve(requireSafeSegment(ctx.ownerId(), "ownerId"))
                    .resolve(requireSafeSegment(ctx.uuid(), "uuid"));
            case SKILL_CREATOR -> root.resolve("skill-creator")
                    .resolve(requireSafeSegment(ctx.ownerId(), "ownerId"))
                    .resolve(requireSafeSegment(ctx.uuid(), "uuid"));
            case DRAFT_APPROVE -> root.resolve("draft-approve")
                    .resolve(requireSafeSegment(ctx.ownerId(), "ownerId"))
                    .resolve(requireSafeSegment(ctx.uuid(), "uuid"));
            case EVOLUTION_FORK -> root.resolve("evolution-fork")
                    .resolve(requireSafeSegment(ctx.ownerId(), "ownerId"))
                    .resolve(requireSafeSegment(ctx.parentSkillId(), "parentSkillId"))
                    .resolve(requireSafeSegment(ctx.uuid(), "uuid"));
            case SKILLHUB -> root.resolve("skillhub")
                    .resolve(requireSafeSegment(ctx.slug(), "slug"))
                    .resolve(requireSafeSegment(ctx.version(), "version"));
            case CLAWHUB -> root.resolve("clawhub")
                    .resolve(requireSafeSegment(ctx.slug(), "slug"))
                    .resolve(requireSafeSegment(ctx.version(), "version"));
            case GITHUB -> root.resolve("github")
                    .resolve(requireSafeSegment(ctx.repoSlug(), "repoSlug"))
                    .resolve(requireSafeSegment(ctx.ref(), "ref"));
            case FILESYSTEM -> root.resolve("filesystem")
                    .resolve(requireSafeSegment(ctx.ownerId(), "ownerId"))
                    .resolve(requireSafeSegment(ctx.uuid(), "uuid"));
        };
        Path normalized = resolved.normalize();
        // Defense in depth: ensure the final path is still under the runtime root
        // even if a segment slipped past requireSafeSegment.
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Allocated path escaped runtime root: " + normalized + " (root=" + root + ")");
        }
        return normalized;
    }

    /** Absolute runtime root from the home resolver. */
    public Path getRuntimeRoot() {
        return homeResolver.getRuntimeRoot();
    }

    /** Create the directory and any missing parents. */
    public void ensureDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to create skill artifact directory: " + dir, e);
        }
    }

    /**
     * Reject empty / null / traversal / absolute path / separator / null-byte
     * inputs that could let a caller break out of the runtime root layout.
     */
    private static String requireSafeSegment(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
        if (value.contains("..")
                || value.contains("/")
                || value.contains("\\")
                || value.contains("\0")
                || value.startsWith(".")) {
            throw new IllegalArgumentException(
                    fieldName + " contains illegal characters: " + value);
        }
        return value;
    }
}
