package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * P1-D — Reconciles {@code system-skills/} and {@code <runtimeRoot>/} on disk
 * with rows in {@code t_skill}.
 *
 * <p>Three top-level entry points:
 * <ul>
 *   <li>{@link #reconcileSystem()} — scan system root, upsert {@code is_system=true} rows.</li>
 *   <li>{@link #reconcileRuntime()} — scan runtime root, upsert non-system rows
 *       (newly-found = {@code created} with {@code enabled=false} for safety),
 *       hash-track changes, mark missing/invalid.</li>
 *   <li>{@link #resolveConflicts()} — ensure each name has at most one enabled
 *       runtime winner, with system rows shadowing runtime rows of the same
 *       name.</li>
 * </ul>
 *
 * <p>{@link #fullRescan()} composes the three.
 *
 * <p>Per-row mutations during conflict resolution go through
 * {@link SkillConflictResolver} as a separate {@code @Service} so each runs in
 * its own {@code REQUIRES_NEW} transaction (Spring AOP self-invocation footgun).
 */
@Service
public class SkillCatalogReconciler {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalogReconciler.class);

    private final SkillForgeHomeResolver homeResolver;
    private final SkillRepository skillRepository;
    private final SkillPackageLoader packageLoader;
    private final SkillConflictResolver conflictResolver;

    @PersistenceContext
    private EntityManager entityManager;

    public SkillCatalogReconciler(SkillForgeHomeResolver homeResolver,
                                  SkillRepository skillRepository,
                                  SkillPackageLoader packageLoader,
                                  SkillConflictResolver conflictResolver) {
        this.homeResolver = homeResolver;
        this.skillRepository = skillRepository;
        this.packageLoader = packageLoader;
        this.conflictResolver = conflictResolver;
    }

    /** Compose system + runtime + conflict resolution. */
    public RescanReport fullRescan() {
        RescanReport sys = reconcileSystem();
        RescanReport runtime = reconcileRuntime();
        RescanReport conflicts = resolveConflicts();
        RescanReport total = sys.plus(runtime).plus(conflicts);
        log.info("Full rescan complete: {}", total);
        return total;
    }

    /**
     * Scan {@code system-skills/} and upsert {@code is_system=true} rows via
     * native SQL ON CONFLICT. Each upsert runs in its own REQUIRES_NEW
     * transaction (delegated to {@link SkillConflictResolver#upsertSystemRow})
     * so a single bad row cannot poison sibling upserts. {@code created} counts
     * inserts; {@code updated} counts content_hash deltas.
     *
     * <p>Not annotated {@code @Transactional}: the persistent unit of work is
     * the per-row REQUIRES_NEW; an outer tx here would just buffer reads and
     * risk masking the per-row isolation guarantee.
     */
    public RescanReport reconcileSystem() {
        Path systemDir = homeResolver.getSystemSkillsDir();
        if (!Files.isDirectory(systemDir)) {
            log.warn("System skills directory not found: {} — system reconcile skipped", systemDir);
            return RescanReport.empty();
        }
        int created = 0;
        int updated = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(systemDir)) {
            for (Path subDir : stream) {
                if (!Files.isDirectory(subDir)) continue;
                Path skillMd = findSkillMd(subDir);
                if (skillMd == null) continue;
                SkillDefinition def;
                try {
                    def = packageLoader.loadFromDirectory(subDir);
                } catch (IOException e) {
                    log.warn("System skill {} failed to load: {}", subDir, e.getMessage());
                    continue;
                }
                String hash = sha256(readBytes(skillMd));
                Optional<SkillEntity> existing = skillRepository.findByName(def.getName());
                if (existing.isPresent() && Boolean.TRUE.equals(existing.get().isSystem())) {
                    if (!equalsSafe(existing.get().getContentHash(), hash)) {
                        updated++;
                    }
                } else if (existing.isEmpty()) {
                    created++;
                }
                conflictResolver.upsertSystemRow(def, subDir.toAbsolutePath().toString(), hash);
            }
        } catch (IOException e) {
            log.error("System reconcile scan failed: {}", e.getMessage());
        }
        return new RescanReport(created, updated, 0, 0, 0, 0);
    }

    /**
     * Walk {@code runtimeRoot} for SKILL.md leaf dirs, reconcile each with
     * {@code t_skill}, then sweep existing rows for missing/invalid artifacts.
     *
     * <p><b>Not annotated {@code @Transactional}</b>: each per-row write goes
     * through Spring Data JPA's default auto-transactional repository methods
     * ({@code save} starts its own write tx, {@code findX} its own readOnly tx).
     * This way a single row's exception does not mark a shared EM rollback-only
     * and poison sibling writes (cf. footgun: "JPA EM rollback contagion under
     * batch save"). Insert and update paths each wrap their {@code save()} in
     * try/catch to log + skip on failure; sweep mutations go through
     * {@link SkillConflictResolver}'s REQUIRES_NEW methods and are isolated by
     * design.
     */
    public RescanReport reconcileRuntime() {
        Path root = homeResolver.getRuntimeRoot();
        if (!Files.isDirectory(root)) {
            log.warn("Runtime skills directory not found: {} — runtime reconcile skipped", root);
            return RescanReport.empty();
        }
        RescanReport report = RescanReport.empty();

        // Pre-index existing non-system rows by canonical absolute skill_path for O(1) lookup.
        Map<String, SkillEntity> byPath = new HashMap<>();
        for (SkillEntity row : skillRepository.findByIsSystemFalse()) {
            String resolved = resolveStoredPath(row.getSkillPath(), root);
            if (resolved != null) byPath.put(resolved, row);
        }

        // Walk for SKILL.md leaf dirs (limit depth to avoid runaway scans).
        Set<Path> skillDirs = findSkillPackageDirs(root);

        Set<Long> touchedIds = new HashSet<>();

        for (Path skillDir : skillDirs) {
            Path skillMd = findSkillMd(skillDir);
            if (skillMd == null) continue;

            SkillDefinition def;
            try {
                def = packageLoader.loadFromDirectory(skillDir);
            } catch (IOException e) {
                log.warn("Runtime skill at {} failed to load: {}", skillDir, e.getMessage());
                report = report.addInvalid(1);
                continue;
            }

            String hash = sha256(readBytes(skillMd));
            String absPath = skillDir.toAbsolutePath().normalize().toString();

            SkillEntity matched = byPath.get(absPath);
            if (matched == null) {
                // Compat fallback — 4-tuple match on (is_system=false, owner_id, name, source).
                Long ownerIdGuess = inferOwnerIdFromPath(skillDir, root);
                String sourceGuess = inferSourceFromPath(skillDir, root);
                if (sourceGuess != null && def.getName() != null) {
                    Optional<SkillEntity> compat = skillRepository
                            .findFirstByIsSystemFalseAndOwnerIdAndNameAndSource(
                                    ownerIdGuess, def.getName(), sourceGuess);
                    if (compat.isPresent()) {
                        matched = compat.get();
                    }
                }
            }

            if (matched == null) {
                // New artifact — insert disabled (defensive default — admin must enable in UI).
                SkillEntity entity = new SkillEntity();
                entity.setName(def.getName());
                entity.setDescription(def.getDescription());
                entity.setSkillPath(absPath);
                entity.setOwnerId(inferOwnerIdFromPath(skillDir, root));
                entity.setSource(safeOrFallback(inferSourceFromPath(skillDir, root), "filesystem"));
                entity.setEnabled(false);
                entity.setSystem(false);
                entity.setArtifactStatus("active");
                entity.setContentHash(hash);
                entity.setLastScannedAt(Instant.now());
                if (def.getTriggers() != null && !def.getTriggers().isEmpty()) {
                    entity.setTriggers(String.join(",", def.getTriggers()));
                }
                if (def.getRequiredTools() != null && !def.getRequiredTools().isEmpty()) {
                    entity.setRequiredTools(String.join(",", def.getRequiredTools()));
                }
                try {
                    SkillEntity saved = skillRepository.save(entity);
                    touchedIds.add(saved.getId());
                    report = report.addCreated(1);
                    log.info("Reconciler created runtime skill row id={} name={} path={}",
                            saved.getId(), saved.getName(), absPath);
                } catch (Exception ex) {
                    log.warn("Reconciler insert failed for {}: {}", absPath, ex.getMessage());
                }
                continue;
            }

            touchedIds.add(matched.getId());

            // Hash-track for update.
            boolean changed = !equalsSafe(matched.getContentHash(), hash);
            if (changed) {
                matched.setDescription(def.getDescription());
                if (def.getTriggers() != null) {
                    matched.setTriggers(String.join(",", def.getTriggers()));
                }
                if (def.getRequiredTools() != null) {
                    matched.setRequiredTools(String.join(",", def.getRequiredTools()));
                }
                matched.setContentHash(hash);
                report = report.addUpdated(1);
            }
            // Always update skillPath to the canonical absolute (heals legacy relative paths).
            if (!absPath.equals(matched.getSkillPath())) {
                matched.setSkillPath(absPath);
            }
            // If previously flagged missing/invalid, restore to active.
            if ("missing".equals(matched.getArtifactStatus())
                    || "invalid".equals(matched.getArtifactStatus())) {
                matched.setArtifactStatus("active");
            }
            matched.setLastScannedAt(Instant.now());
            try {
                skillRepository.save(matched);
            } catch (Exception ex) {
                // Per-row update failure: log + skip; siblings remain unaffected
                // because reconcileRuntime is no longer @Transactional.
                log.warn("Reconciler update failed for id={} path={}: {}",
                        matched.getId(), absPath, ex.getMessage());
            }
        }

        // Sweep: any non-system, non-touched row whose path no longer resolves on disk → missing/invalid.
        for (SkillEntity row : skillRepository.findByIsSystemFalse()) {
            if (touchedIds.contains(row.getId())) continue;
            String resolved = resolveStoredPath(row.getSkillPath(), root);
            if (resolved == null) {
                conflictResolver.markMissing(row.getId());
                report = report.addMissing(1);
                continue;
            }
            Path resolvedPath = Path.of(resolved);
            if (!Files.isDirectory(resolvedPath)) {
                conflictResolver.markMissing(row.getId());
                report = report.addMissing(1);
                continue;
            }
            // The package exists but wasn't picked up in the walk (e.g. depth exceeded).
            // Treat it as invalid to surface to operators.
            try {
                packageLoader.loadFromDirectory(resolvedPath);
            } catch (IOException e) {
                conflictResolver.markInvalid(row.getId());
                report = report.addInvalid(1);
            } catch (Exception e) {
                conflictResolver.markInvalid(row.getId());
                report = report.addInvalid(1);
            }
        }

        return report;
    }

    /**
     * Resolve same-name conflicts:
     * <ol>
     *   <li>If a system row exists for name N, all runtime rows with name N
     *       become shadowed.</li>
     *   <li>Otherwise, among runtime rows with name N, pick the newest
     *       ({@code created_at desc, id desc}) as winner. Other rows go
     *       shadowed + {@code enabled=false}.</li>
     * </ol>
     */
    @Transactional
    public RescanReport resolveConflicts() {
        // Group all skills by name.
        Map<String, List<SkillEntity>> byName = new LinkedHashMap<>();
        for (SkillEntity row : skillRepository.findAll()) {
            if (row.getName() == null) continue;
            byName.computeIfAbsent(row.getName(), k -> new ArrayList<>()).add(row);
        }

        int shadowed = 0;
        int disabledDuplicates = 0;

        for (Map.Entry<String, List<SkillEntity>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<SkillEntity> rows = entry.getValue();
            if (rows.size() < 2) {
                // Even a single row may need un-shadowing if it was previously shadowed but
                // the conflict cleared. Handle below if status is shadowed.
                if (rows.size() == 1) {
                    SkillEntity only = rows.get(0);
                    if ("shadowed".equals(only.getArtifactStatus())) {
                        try {
                            conflictResolver.clearShadowOrError(only.getId());
                        } catch (Exception ex) {
                            log.warn("clearShadowOrError failed for id={}: {}",
                                    only.getId(), ex.getMessage());
                        }
                    }
                }
                continue;
            }

            Optional<SkillEntity> systemRow = rows.stream()
                    .filter(SkillEntity::isSystem).findFirst();

            if (systemRow.isPresent()) {
                // Every runtime row with this name is shadowed by system.
                // Per-row try/catch so a single tx failure (REQUIRES_NEW
                // commit/rollback boundary) doesn't abort the loop — sibling
                // rows in the same rescan still resolve.
                for (SkillEntity row : rows) {
                    if (row.isSystem()) continue;
                    try {
                        conflictResolver.markShadowedBySystem(row.getId(), name);
                        shadowed++;
                    } catch (Exception ex) {
                        log.warn("markShadowedBySystem failed for row id={} name={}: {}",
                                row.getId(), name, ex.getMessage());
                    }
                }
                continue;
            }

            // Runtime-vs-runtime: pick the newest by (created_at desc, id desc).
            // Use native SQL to avoid LocalDateTime/Instant-mixing comparison risks
            // (createdAt is LocalDateTime per SkillEntity legacy field).
            List<Long> ids = rows.stream().map(SkillEntity::getId).toList();
            String inClause = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            if (inClause.isEmpty()) continue;
            @SuppressWarnings("unchecked")
            List<Number> orderedIds = entityManager.createNativeQuery(
                    "SELECT id FROM t_skill WHERE id IN (" + inClause + ") "
                            + "ORDER BY created_at DESC NULLS LAST, id DESC")
                    .getResultList();
            if (orderedIds.isEmpty()) continue;
            Long winnerId = orderedIds.get(0).longValue();
            for (SkillEntity row : rows) {
                if (row.getId().equals(winnerId)) {
                    if ("shadowed".equals(row.getArtifactStatus())) {
                        try {
                            conflictResolver.clearShadowOrError(row.getId());
                        } catch (Exception ex) {
                            log.warn("clearShadowOrError failed for winner id={}: {}",
                                    row.getId(), ex.getMessage());
                        }
                    }
                    continue;
                }
                try {
                    conflictResolver.markShadowedByRuntime(row.getId(), winnerId);
                    shadowed++;
                } catch (Exception ex) {
                    log.warn("markShadowedByRuntime failed for row id={} winner={}: {}",
                            row.getId(), winnerId, ex.getMessage());
                    continue;
                }
                if (row.isEnabled()) disabledDuplicates++;
            }
        }

        return new RescanReport(0, 0, 0, 0, shadowed, disabledDuplicates);
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** Walk runtime root for directories containing SKILL.md (max depth 6). */
    private Set<Path> findSkillPackageDirs(Path root) {
        Set<Path> out = new HashSet<>();
        try (Stream<Path> stream = Files.walk(root, 6)) {
            stream.filter(p -> Files.isRegularFile(p)
                            && (p.getFileName().toString().equals("SKILL.md")
                                || p.getFileName().toString().equals("skill.md")))
                    .forEach(p -> {
                        Path parent = p.getParent();
                        if (parent != null) out.add(parent);
                    });
        } catch (IOException e) {
            log.warn("Runtime walk failed at {}: {}", root, e.getMessage());
        }
        return out;
    }

    /** Locate SKILL.md (or skill.md) inside a directory; null if absent. */
    private static Path findSkillMd(Path dir) {
        Path cap = dir.resolve("SKILL.md");
        if (Files.isRegularFile(cap)) return cap;
        Path lower = dir.resolve("skill.md");
        if (Files.isRegularFile(lower)) return lower;
        return null;
    }

    /**
     * Resolve a row's stored {@code skill_path} to an absolute string suitable
     * for path-equality lookup. Handles legacy relative paths (e.g.
     * {@code ./data/skills/<ownerId>/<uuid>}) — the legacy cwd was
     * {@code skillforge-server} (= {@code home}), so a path like
     * {@code ./data/skills/1/uuid} corresponds to {@code <home>/data/skills/1/uuid}
     * = {@code <runtimeRoot>/1/uuid}. We anchor against {@code home} (which is
     * {@code runtimeRoot.getParent().getParent()} since
     * {@code runtimeRoot = <home>/data/skills}).
     */
    private static String resolveStoredPath(String stored, Path runtimeRoot) {
        if (stored == null || stored.isBlank()) return null;
        Path p = Path.of(stored);
        if (p.isAbsolute()) {
            return p.normalize().toString();
        }
        // Legacy relative path: walk runtimeRoot up TWO levels to reach <home>
        // (data/skills → data → home). Resolving "./data/skills/X" against home
        // yields the correct <home>/data/skills/X.
        Path home = runtimeRoot.getParent();
        if (home != null) home = home.getParent();
        if (home != null) {
            Path candidate = home.resolve(stored).normalize();
            if (Files.exists(candidate)) return candidate.toString();
        }
        // Final fallback: resolve against runtimeRoot (handles the alternative
        // legacy form where stored is already relative to the runtime root —
        // e.g. "1/uuid" without the "./data/skills/" prefix).
        Path fallback = runtimeRoot.resolve(stored).normalize();
        return fallback.toString();
    }

    /**
     * Best-effort owner-id inference from the directory layout. Returns null
     * when the layer slot is non-numeric (e.g. {@code skillhub/{slug}/...}).
     */
    private static Long inferOwnerIdFromPath(Path skillDir, Path runtimeRoot) {
        Path rel;
        try {
            rel = runtimeRoot.relativize(skillDir.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return null;
        }
        // 3-layer: type/ownerId/uuid → rel.getName(1) is ownerId.
        // 2-layer legacy: ownerId/uuid → rel.getName(0) is ownerId.
        for (int idx : new int[]{1, 0}) {
            if (rel.getNameCount() > idx) {
                String seg = rel.getName(idx).toString();
                try {
                    return Long.parseLong(seg);
                } catch (NumberFormatException ignored) {
                    // not numeric — fall through
                }
            }
        }
        return null;
    }

    /** Infer source name from the first path segment under runtime root. */
    private static String inferSourceFromPath(Path skillDir, Path runtimeRoot) {
        Path rel;
        try {
            rel = runtimeRoot.relativize(skillDir.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (rel.getNameCount() == 0) return null;
        String first = rel.getName(0).toString();
        // Numeric first segment = legacy 2-layer {ownerId}/{uuid} layout (pre-P1-D upload).
        try {
            Long.parseLong(first);
            return "upload";
        } catch (NumberFormatException ignored) {
            return first;
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String safeOrFallback(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
