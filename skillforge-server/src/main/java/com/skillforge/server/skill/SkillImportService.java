package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SKILL-IMPORT — register a third-party-installed skill (ClawHub / GitHub /
 * SkillHub / filesystem) into SkillForge: validate sourcePath whitelist,
 * parse SKILL.md + {@code _meta.json}, allocate target via
 * {@link SkillStorageService}, copy the package, hash with the same algorithm
 * as {@link SkillCatalogReconciler}, UPSERT the {@code t_skill} row, and
 * register the {@link SkillDefinition} with {@link SkillRegistry} so the next
 * agent turn can invoke it.
 */
@Service
public class SkillImportService {

    private static final Logger log = LoggerFactory.getLogger(SkillImportService.class);
    private static final String DEFAULT_VERSION = "latest";
    private static final String DEFAULT_GITHUB_REF = "main";

    private final SkillImportProperties properties;
    private final SkillStorageService storageService;
    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;
    private final SkillPackageLoader packageLoader;
    private final SkillCatalogReconciler reconciler;
    private final ObjectMapper objectMapper;

    public SkillImportService(SkillImportProperties properties,
                              SkillStorageService storageService,
                              SkillRepository skillRepository,
                              SkillRegistry skillRegistry,
                              SkillPackageLoader packageLoader,
                              SkillCatalogReconciler reconciler,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.storageService = storageService;
        this.skillRepository = skillRepository;
        this.skillRegistry = skillRegistry;
        this.packageLoader = packageLoader;
        this.reconciler = reconciler;
        this.objectMapper = objectMapper;
    }

    /**
     * Import an externally-installed skill at {@code sourcePath} into the
     * SkillForge runtime root + catalog.
     *
     * <p>Behaviour outlined in tech-design module B:
     * <ol>
     *   <li>validate {@code sourcePath} against the configured whitelist
     *       (after {@link Path#toRealPath} symlink resolution)</li>
     *   <li>parse {@code SKILL.md} + best-effort {@code _meta.json}</li>
     *   <li>allocate the on-disk target via {@link SkillStorageService}</li>
     *   <li>recursively copy {@code sourcePath} → target</li>
     *   <li>compute SHA-256 of the target's {@code SKILL.md} (same algo as
     *       {@link SkillCatalogReconciler})</li>
     *   <li>UPSERT the {@code t_skill} row (override on existing slug)</li>
     *   <li>register the {@link SkillDefinition} into {@link SkillRegistry}
     *       so the next agent turn can invoke it</li>
     * </ol>
     *
     * @param sourcePath absolute, real path to the directory containing SKILL.md
     * @param source     marketplace source, controls the runtime layout
     * @param ownerId    user that owns the imported row
     * @return immutable {@link ImportResult} describing what was done
     */
    @Transactional
    public ImportResult importSkill(Path sourcePath, SkillSource source, Long ownerId) {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownerId, "ownerId");

        // 1. Resolve symlinks first, then enforce whitelist.
        Path realSource = validateSourcePath(sourcePath);

        // 2. Validate SKILL.md presence + parse definition.
        Path skillMd = realSource.resolve("SKILL.md");
        Path skillMdLower = realSource.resolve("skill.md");
        if (!Files.isRegularFile(skillMd) && !Files.isRegularFile(skillMdLower)) {
            throw new IllegalArgumentException("SKILL.md not found in " + realSource);
        }
        SkillDefinition def;
        try {
            def = packageLoader.loadFromDirectory(realSource);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to parse skill package at " + realSource + ": " + e.getMessage(), e);
        }
        if (def.getName() == null || def.getName().isBlank()) {
            throw new IllegalArgumentException(
                    "Skill package at " + realSource + " has no usable 'name' (frontmatter or directory)");
        }

        // 3. _meta.json best-effort overlay (slug + version).
        SkillMeta meta = readMetaJsonOrFallback(realSource, def);

        // 4. Allocate destination on disk.
        AllocationContext ctx = buildAllocationContext(source, ownerId, meta);
        Path target = storageService.allocate(source, ctx);
        storageService.ensureDirectories(target);

        // 5. Copy recursively (overwrite existing files for the same-version override path).
        try {
            copyDirectoryReplacing(realSource, target);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to copy skill package " + realSource + " → " + target, e);
        }

        // 6. Compute content_hash via the reconciler's exact algorithm.
        String hash = reconciler.hashSkillMd(target);

        // 7. UPSERT t_skill row.
        SkillDefinition reloaded;
        try {
            reloaded = packageLoader.loadFromDirectory(target);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to reload skill package after copy at " + target, e);
        }
        UpsertOutcome outcome = upsertSkillRow(reloaded, source, target, hash, ownerId, meta);

        // 8. Register into SkillRegistry so subsequent agent turns can dispatch the new skill.
        reloaded.setSystem(false);
        reloaded.setOwnerId(String.valueOf(ownerId));
        skillRegistry.registerSkillDefinition(reloaded);

        log.info("ImportSkill: {} skill id={} name={} source={} target={} conflictResolved={}",
                outcome.conflictResolved ? "updated" : "created",
                outcome.row.getId(), outcome.row.getName(),
                source.wireName(), target, outcome.conflictResolved);

        return new ImportResult(
                outcome.row.getId(),
                outcome.row.getName(),
                target.toString(),
                source.wireName(),
                outcome.conflictResolved);
    }

    /**
     * Resolve symlinks in {@code sourcePath} and verify the resulting path
     * starts with at least one configured allowed root.
     *
     * <p>{@link Path#toRealPath()} is used (no NOFOLLOW_LINKS) so that an
     * attacker cannot bypass the whitelist via a symlink whose target lies
     * outside the allowed roots. Whitelist roots are also canonicalised the
     * same way so symlink-vs-real-path mismatches do not produce false
     * negatives.
     */
    private Path validateSourcePath(Path sourcePath) {
        List<Path> roots = properties.resolvedAllowedRoots();
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(
                    "skillforge.skill-import.allowed-source-roots is empty; ImportSkill is disabled");
        }
        Path real;
        try {
            real = sourcePath.toRealPath().toAbsolutePath().normalize();
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("sourcePath does not exist: " + sourcePath);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "sourcePath could not be canonicalised: " + sourcePath + " (" + e.getMessage() + ")");
        }
        for (Path root : roots) {
            Path canonicalRoot;
            try {
                canonicalRoot = root.toRealPath().toAbsolutePath().normalize();
            } catch (IOException e) {
                // Allowed root configured but missing on disk — skip; cannot match anything.
                continue;
            }
            if (real.startsWith(canonicalRoot)) {
                return real;
            }
        }
        throw new IllegalArgumentException(
                "sourcePath not in allowed roots: " + real + " (allowed=" + roots + ")");
    }

    /**
     * Best-effort {@code _meta.json} parse for {@code slug} / {@code version} /
     * {@code repo} / {@code ref}. Missing fields fall back to definition / safe
     * defaults so {@link SkillStorageService}'s segment validator does not
     * reject blank inputs.
     */
    private SkillMeta readMetaJsonOrFallback(Path sourcePath, SkillDefinition def) {
        Path metaJson = sourcePath.resolve("_meta.json");
        String slug = null;
        String version = null;
        String repo = null;
        String ref = null;
        if (Files.isRegularFile(metaJson)) {
            try {
                JsonNode root = objectMapper.readTree(metaJson.toFile());
                slug = readText(root, "slug");
                version = readText(root, "version");
                repo = readText(root, "repo");
                if (repo == null) repo = readText(root, "repoSlug");
                ref = readText(root, "ref");
            } catch (IOException e) {
                log.warn("Failed to parse _meta.json at {}: {}", metaJson, e.getMessage());
            }
        }
        if (slug == null || slug.isBlank()) slug = def.getName();
        if (version == null || version.isBlank()) version = DEFAULT_VERSION;
        if (ref == null || ref.isBlank()) ref = DEFAULT_GITHUB_REF;
        // repo may legitimately stay null for non-github sources; the
        // allocation context builder substitutes it from the source dir name
        // when actually needed.
        return new SkillMeta(slug, version, repo, ref);
    }

    private static String readText(JsonNode root, String field) {
        if (root == null || !root.hasNonNull(field)) return null;
        String value = root.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private AllocationContext buildAllocationContext(SkillSource source, Long ownerId, SkillMeta meta) {
        return switch (source) {
            case CLAWHUB -> AllocationContext.forClawhub(meta.slug(), meta.version());
            case SKILLHUB -> AllocationContext.forSkillhub(meta.slug(), meta.version());
            case GITHUB -> AllocationContext.forGithub(
                    meta.repo() != null ? meta.repo() : meta.slug(),
                    meta.ref());
            case FILESYSTEM -> AllocationContext.forFilesystem(
                    String.valueOf(ownerId), UUID.randomUUID().toString());
            // The other SkillSource values are server-internal allocation
            // sources (upload / skill-creator / draft-approve / evolution-fork);
            // they are not valid ImportSkill inputs.
            default -> throw new IllegalArgumentException(
                    "Source " + source.wireName() + " is not supported by ImportSkill");
        };
    }

    /**
     * Recursively copy {@code source} into {@code target}. Files in
     * {@code target} that already exist are overwritten so that a same-version
     * re-import refreshes content (PRD F2).
     */
    private static void copyDirectoryReplacing(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(dir).toString());
                if (!Files.exists(dest)) {
                    Files.createDirectories(dest);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file).toString());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * UPSERT a non-system {@code t_skill} row for the imported skill. Returns
     * the persisted entity + a {@code conflictResolved} flag that distinguishes
     * "created new" (false) from "overwrote existing" (true).
     *
     * <p>Concurrency: follows the {@code lookup → insertIgnoreConflict →
     * re-lookup} pattern documented in {@link com.skillforge.server.service.ToolResultArchiveService}
     * and {@link com.skillforge.server.repository.ToolResultArchiveRepository#insertIgnoreConflict}.
     * JPA's {@code save()} cannot serve as the conflict-detection point: with
     * deferred SQL flush, the unique violation surfaces at TX commit (not
     * inside the catch block), and once the violation does fire it marks the
     * outer transaction as rollback-only — both kill the retry path. Native
     * {@code INSERT ... ON CONFLICT DO NOTHING} is the only PostgreSQL-safe
     * idempotent insert that does not abort the transaction.
     *
     * <p>Identity is keyed on {@code meta.slug()} (with {@code def.getName()}
     * fallback inside {@link #readMetaJsonOrFallback}) so a frontmatter rename
     * cannot accidentally fork into a duplicate row pointing at the same
     * on-disk artifact.
     */
    private UpsertOutcome upsertSkillRow(SkillDefinition def, SkillSource source, Path target,
                                         String hash, Long ownerId, SkillMeta meta) {
        String wireSource = source.wireName();
        String rowName = meta.slug();
        Optional<SkillEntity> existing = skillRepository
                .findByOwnerIdAndNameAndSourceAndIsSystem(ownerId, rowName, wireSource, false);
        if (existing.isPresent()) {
            return new UpsertOutcome(applyUpdate(existing.get(), def, target, hash, meta), true);
        }
        Instant now = Instant.now();
        String triggers = joinOrNull(def.getTriggers());
        String requiredTools = joinOrNull(def.getRequiredTools());
        String version = (meta.version() != null && !meta.version().isBlank()) ? meta.version() : null;
        int rows = skillRepository.insertImportedSkillIgnoreConflict(
                ownerId,
                rowName,
                def.getDescription(),
                triggers,
                requiredTools,
                target.toString(),
                wireSource,
                version,
                hash,
                now,
                now);
        if (rows == 0) {
            // Concurrent import for the same (owner_id, name) tuple won the race;
            // re-lookup picks up the winner row and we apply our update on top.
            log.info("Concurrent ImportSkill insert collision for owner={} name={} source={}; "
                    + "re-using winner row", ownerId, rowName, wireSource);
            Optional<SkillEntity> winner = skillRepository
                    .findByOwnerIdAndNameAndSourceAndIsSystem(ownerId, rowName, wireSource, false);
            if (winner.isEmpty()) {
                throw new IllegalStateException(
                        "ImportSkill insert reported 0 rows but no existing row found for "
                                + "owner=" + ownerId + " name=" + rowName + " source=" + wireSource);
            }
            return new UpsertOutcome(applyUpdate(winner.get(), def, target, hash, meta), true);
        }
        // We won the insert; re-lookup to pull back the auto-generated identity (the
        // native INSERT path does not populate the entity id back into our local
        // SkillEntity instance).
        Optional<SkillEntity> persisted = skillRepository
                .findByOwnerIdAndNameAndSourceAndIsSystem(ownerId, rowName, wireSource, false);
        if (persisted.isEmpty()) {
            throw new IllegalStateException(
                    "ImportSkill insert reported 1 row but row was not found on re-lookup for "
                            + "owner=" + ownerId + " name=" + rowName + " source=" + wireSource);
        }
        return new UpsertOutcome(persisted.get(), false);
    }

    private SkillEntity applyUpdate(SkillEntity row, SkillDefinition def, Path target,
                                    String hash, SkillMeta meta) {
        // PRD F2: when path changes (e.g. version bump), the previous on-disk
        // directory is left in place so a user mid-inspect won't lose context.
        // Surface it with a single WARN so operators / reconciler logs make the
        // orphan visible without the service auto-deleting anything.
        String oldPath = row.getSkillPath();
        String newPath = target.toString();
        if (oldPath != null && !oldPath.isBlank() && !oldPath.equals(newPath)) {
            log.warn("ImportSkill path change detected for skill id={} name={}; old artifact "
                    + "directory left as orphan (not deleted): old={} new={}. Reconciler will "
                    + "surface orphan; manual cleanup if desired.",
                    row.getId(), row.getName(), oldPath, newPath);
        }
        row.setSkillPath(newPath);
        row.setContentHash(hash);
        row.setLastScannedAt(Instant.now());
        row.setArtifactStatus("active");
        row.setDescription(def.getDescription());
        if (def.getTriggers() != null) {
            row.setTriggers(def.getTriggers().isEmpty() ? null
                    : String.join(",", def.getTriggers()));
        }
        if (def.getRequiredTools() != null) {
            row.setRequiredTools(def.getRequiredTools().isEmpty() ? null
                    : String.join(",", def.getRequiredTools()));
        }
        if (meta.version() != null && !meta.version().isBlank()) {
            row.setVersion(meta.version());
        }
        return skillRepository.save(row);
    }

    private static String joinOrNull(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        return String.join(",", values);
    }

    /** Best-effort {@code _meta.json} fields with safe fallbacks. */
    record SkillMeta(String slug, String version, String repo, String ref) {}

    /** Internal upsert return tuple. */
    private record UpsertOutcome(SkillEntity row, boolean conflictResolved) {}
}
