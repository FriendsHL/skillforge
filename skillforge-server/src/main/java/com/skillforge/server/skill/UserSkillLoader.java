package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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

    @Value("${skillforge.skills-dir:./data/skills}")
    private String skillsDir;

    public UserSkillLoader(SkillRepository skillRepository,
                           SkillRegistry skillRegistry,
                           SkillPackageLoader packageLoader) {
        this.skillRepository = skillRepository;
        this.skillRegistry = skillRegistry;
        this.packageLoader = packageLoader;
    }

    /**
     * Reload all user skills from t_skill into SkillRegistry.
     * <p>Read-only transaction — we don't mutate DB rows here. Registry mutation is in-memory
     * and not subject to transaction semantics.
     */
    @Transactional(readOnly = true)
    public void loadAll() {
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
     * Plan r2 §3 case A — orphan directory scan. After approveDraft step 5 fails, the artifact
     * directory was written but the t_skill row never persisted (transaction rollback). Such
     * directories are detectable here and surfaced via WARN. We do NOT auto-delete (avoid wiping
     * dirs an operator may be inspecting).
     * <p>Also includes paths from non-system t_skill rows we already iterated (regardless of enabled),
     * to surface dirs that exist but are owned by a disabled / orphaned row.
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

        Path root = Path.of(skillsDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> ownerDirs = Files.list(root)) {
            ownerDirs.filter(Files::isDirectory).forEach(ownerDir -> {
                try (Stream<Path> skillDirs = Files.list(ownerDir)) {
                    skillDirs.filter(Files::isDirectory).forEach(skillDir -> {
                        String absPath = skillDir.toAbsolutePath().normalize().toString();
                        if (!knownPaths.contains(absPath)) {
                            log.warn("Orphan skill dir (no t_skill row pointing to it): {}", absPath);
                        }
                    });
                } catch (IOException ignored) {
                    // Best-effort: skip unreadable owner dirs.
                }
            });
        } catch (IOException e) {
            log.warn("Orphan dir scan failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Visible for tests. */
    public String getSkillsDir() {
        return skillsDir;
    }

    /** Visible for tests. */
    public void setSkillsDir(String skillsDir) {
        this.skillsDir = Objects.requireNonNullElse(skillsDir, "./data/skills");
    }
}
