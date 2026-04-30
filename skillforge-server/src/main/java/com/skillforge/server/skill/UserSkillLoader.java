package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Plan r2 §6 — UserSkillLoader：启动时按 t_skill 行重新注册非 system / enabled 的 user skill。
 * <p>调用时机：由 {@link SystemSkillLoader#loadSystemSkills()} 末尾显式调用，不依赖
 * {@code @EventListener} 顺序。
 * <p>容错策略：
 * <ul>
 *   <li>skillPath null / 目录缺失 → log.warn 跳过</li>
 *   <li>同名 system skill 优先 → 跳过 user skill（plan §6）</li>
 *   <li>坏包（loadFromDirectory 抛异常）→ log.error，继续下一个，不阻断启动</li>
 *   <li>顺扫孤儿目录（{@code skillsDir/<owner>/<skillId>}）→ 无对应 t_skill 行 → log.warn（不删）</li>
 * </ul>
 */
@Component
public class UserSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(UserSkillLoader.class);

    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;
    private final SkillPackageLoader packageLoader;
    private final SkillForgeHomeResolver homeResolver;
    private final SkillCatalogReconciler reconciler;

    /**
     * Test-only override for the runtime root used by the orphan dir scan.
     * {@code null} in production — {@link SkillForgeHomeResolver#getRuntimeRoot()}
     * is the source of truth.
     */
    private String skillsDir = null;

    public UserSkillLoader(SkillRepository skillRepository,
                           SkillRegistry skillRegistry,
                           SkillPackageLoader packageLoader,
                           SkillForgeHomeResolver homeResolver,
                           SkillCatalogReconciler reconciler) {
        this.skillRepository = skillRepository;
        this.skillRegistry = skillRegistry;
        this.packageLoader = packageLoader;
        this.homeResolver = homeResolver;
        this.reconciler = reconciler;
    }

    /**
     * Reload all user skills from t_skill into SkillRegistry.
     * <p>P1-D §T6 — first delegates to {@link SkillCatalogReconciler} so the runtime root
     * is reconciled with t_skill (creates new rows for newfound artifacts, marks
     * missing/invalid, resolves same-name conflicts) before re-registering enabled rows.
     * <p>Registry mutation is in-memory and not subject to transaction semantics; the
     * reconciler runs its own transactional units inside.
     */
    public void loadAll() {
        try {
            RescanReport runtimeReport = reconciler.reconcileRuntime();
            RescanReport conflictReport = reconciler.resolveConflicts();
            log.info("UserSkillLoader runtime reconcile: {} | conflicts: {}",
                    runtimeReport, conflictReport);
        } catch (Exception e) {
            log.error("UserSkillLoader: reconcile failed (skipping reconcile, will register "
                    + "from existing rows): {}", e.getMessage(), e);
        }
        registerEnabledRuntimeRows();
    }

    /**
     * Re-register enabled non-system rows into {@link SkillRegistry}. Each repository
     * call is independently transactional via Spring Data's auto-{@code @Transactional};
     * we don't need an outer tx since the registry mutation is in-memory.
     */
    private void registerEnabledRuntimeRows() {
        int registered = 0;
        int skipped = 0;
        Set<String> registeredAbsPaths = new HashSet<>();

        List<SkillEntity> rows = skillRepository.findByIsSystemFalseAndEnabledTrue();
        for (SkillEntity entity : rows) {
            String name = entity.getName();
            try {
                String pathStr = entity.getSkillPath();
                if (pathStr == null || pathStr.isBlank()) {
                    log.warn("UserSkillLoader: skill id={} name={} has null skillPath; skipping",
                            entity.getId(), name);
                    skipped++;
                    continue;
                }
                Path skillDir = Path.of(pathStr);
                if (!Files.isDirectory(skillDir)) {
                    log.warn("UserSkillLoader: skill id={} name={} dir missing on disk: {}",
                            entity.getId(), name, pathStr);
                    skipped++;
                    continue;
                }

                // System skill of the same name wins (plan §6) — don't shadow.
                if (skillRegistry.getSkillDefinition(name)
                        .map(SkillDefinition::isSystem)
                        .orElse(false)) {
                    log.warn("UserSkillLoader: user skill '{}' shadowed by system skill — skipping registration",
                            name);
                    skipped++;
                    registeredAbsPaths.add(skillDir.toAbsolutePath().normalize().toString());
                    continue;
                }

                SkillDefinition def = packageLoader.loadFromDirectory(skillDir);
                def.setSystem(false);
                if (entity.getOwnerId() != null) {
                    def.setOwnerId(String.valueOf(entity.getOwnerId()));
                }
                skillRegistry.registerSkillDefinition(def);
                registeredAbsPaths.add(skillDir.toAbsolutePath().normalize().toString());
                registered++;
            } catch (IOException e) {
                log.error("UserSkillLoader: bad skill package id={} name={}: {}",
                        entity.getId(), name, e.getMessage());
                skipped++;
            } catch (Exception e) {
                log.error("UserSkillLoader: unexpected error for skill id={} name={}: {}",
                        entity.getId(), name, e.getMessage(), e);
                skipped++;
            }
        }

        log.info("UserSkillLoader: registered {} user skill(s), skipped {} (rows scanned: {})",
                registered, skipped, rows.size());

        scanOrphanDirs(registeredAbsPaths);
    }

    /**
     * Plan r2 §3 case A + P1-D §T6 — orphan directory scan. After approveDraft step 5
     * fails, the artifact directory was written but the t_skill row never persisted
     * (transaction rollback). Such directories are detectable here and surfaced via
     * WARN. We do NOT auto-delete (avoid wiping dirs an operator may be inspecting).
     *
     * <p>P1-D: the runtime root is now 3-layer ({@code type/ownerId/uuid}) instead of
     * the legacy 2-layer ({@code ownerId/uuid}). We walk for SKILL.md leaves rather
     * than assuming a fixed depth — this also handles legacy 2-layer artifacts the
     * reconciler may have left in place. Walk depth capped at 6 to bound IO.
     */
    private void scanOrphanDirs(Set<String> registeredAbsPaths) {
        // Include disabled non-system skills' paths so we don't false-flag them as orphans.
        Set<String> knownPaths = new HashSet<>(registeredAbsPaths);
        for (SkillEntity entity : skillRepository.findByIsSystemFalse()) {
            String p = entity.getSkillPath();
            if (p != null && !p.isBlank()) {
                knownPaths.add(Path.of(p).toAbsolutePath().normalize().toString());
            }
        }

        Path root = (skillsDir != null && !skillsDir.isBlank())
                ? Path.of(skillsDir).toAbsolutePath().normalize()
                : homeResolver.getRuntimeRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walker = Files.walk(root, 6)) {
            walker.filter(p -> Files.isRegularFile(p)
                            && (p.getFileName().toString().equals("SKILL.md")
                                || p.getFileName().toString().equals("skill.md")))
                    .map(Path::getParent)
                    .filter(Objects::nonNull)
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .distinct()
                    .forEach(absPath -> {
                        if (!knownPaths.contains(absPath)) {
                            log.warn("Orphan skill dir (no t_skill row pointing to it): {}", absPath);
                        }
                    });
        } catch (IOException e) {
            log.warn("Orphan dir scan failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Visible for tests. */
    public String getSkillsDir() {
        return skillsDir != null ? skillsDir : homeResolver.getRuntimeRoot().toString();
    }

    /** Visible for tests — overrides runtime root for orphan dir scan. */
    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir;
    }
}
