package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时扫描 system-skills/ 目录，将所有子目录作为 system skill 加载并注册到 SkillRegistry。
 * Plan r2 §6 + §11 footgun #8：
 * <ul>
 *   <li>Skill 注册到内存 SkillRegistry（既有逻辑）</li>
 *   <li>同时通过原生 SQL {@code INSERT ... ON CONFLICT DO UPDATE} upsert 到 t_skill；
 *       <b>不能用 JpaRepository.save</b> — unique violation 会污染 Spring 默认事务的 EntityManager。</li>
 *   <li>System skill 的 {@code owner_id = NULL} + {@code is_system = true}；UNIQUE NULLS NOT DISTINCT
 *       让 (NULL, name) 视为同一行参与去重（V31 schema）。</li>
 *   <li>System loader 完成后，<b>显式</b>调用 {@link UserSkillLoader#loadAll()}（不依赖
 *       {@code @EventListener} 顺序）。</li>
 * </ul>
 */
@Component
public class SystemSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SystemSkillLoader.class);

    private final SkillRegistry skillRegistry;
    private final SkillPackageLoader packageLoader;
    private final ApplicationContext applicationContext;
    /**
     * Plan r2 B-BE-1 fix — self-reference proxy for {@code upsertSystemSkillRow} so the
     * call goes through Spring AOP (without it, {@code this.upsertSystemSkillRow(...)}
     * inside {@link #loadSystemSkills()} bypasses the proxy and {@code @Transactional}
     * is silently ignored, causing {@code TransactionRequiredException} to be swallowed
     * by our catch and t_skill rows to never be written).
     * <p>{@code @Lazy} avoids the constructor-time self-injection cycle.
     */
    private final SystemSkillLoader self;

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Value("${skillforge.system-skills-dir:system-skills}")
    private String systemSkillsDirConfig;

    public SystemSkillLoader(SkillRegistry skillRegistry, SkillPackageLoader packageLoader,
                             ApplicationContext applicationContext,
                             @Autowired @Lazy SystemSkillLoader self) {
        this.skillRegistry = skillRegistry;
        this.packageLoader = packageLoader;
        this.applicationContext = applicationContext;
        this.self = self;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSystemSkills() {
        Path systemSkillsDir = resolveSystemSkillsDir();
        if (!Files.isDirectory(systemSkillsDir)) {
            log.error("System skills directory not found: {}. System skills (clawhub, github, skillhub) will NOT be available. "
                    + "Set skillforge.system-skills-dir to the correct path.", systemSkillsDir);
        } else {
            int loaded = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(systemSkillsDir)) {
                for (Path subDir : stream) {
                    if (!Files.isDirectory(subDir)) {
                        continue;
                    }
                    try {
                        SkillDefinition definition = packageLoader.loadFromDirectory(subDir);
                        definition.setSystem(true);

                        // System skill 优先：如果 registry 中已有同名 skillDefinition，覆盖
                        skillRegistry.registerSkillDefinition(definition);

                        // V31 schema sync — upsert into t_skill so UI / metrics / view layer
                        // see this system skill as a row. Native ON CONFLICT prevents EntityManager
                        // pollution (footgun #8).
                        // B-BE-1: must call via self proxy so @Transactional applies. Each row
                        // gets its own REQUIRES_NEW tx so a single bad upsert can't poison the
                        // others (matches design intent of footgun #8).
                        self.upsertSystemSkillRow(definition);
                        loaded++;
                        log.info("Registered system skill: {} (path={})", definition.getName(), subDir);
                    } catch (IOException e) {
                        log.error("Failed to load system skill from {}: {}", subDir, e.getMessage());
                    } catch (Exception e) {
                        log.error("Unexpected error loading system skill from {}: {}",
                                subDir, e.getMessage(), e);
                    }
                }
            } catch (IOException e) {
                log.error("Failed to scan system skills directory: {}", e.getMessage());
            }

            log.info("System skill loading complete: {} skills registered", loaded);
        }

        // Plan r2 §6 — explicitly trigger UserSkillLoader after system loader finishes,
        // so we don't depend on @EventListener ordering. Best-effort: if user loader is
        // missing or throws, log and continue (do not fail startup).
        try {
            UserSkillLoader userLoader = applicationContext.getBean(UserSkillLoader.class);
            userLoader.loadAll();
        } catch (Exception e) {
            log.error("UserSkillLoader.loadAll() failed; user skills may not be re-registered "
                    + "until next restart. Error: {}", e.getMessage(), e);
        }
    }

    /**
     * Native upsert for system skill rows. Uses PostgreSQL {@code INSERT ... ON CONFLICT DO UPDATE}
     * on the {@code uq_t_skill_owner_name (owner_id, name) NULLS NOT DISTINCT} index from V31.
     * <p><b>Why native</b>: Spring's default Jpa @Transactional + JpaRepository.save will mark
     * the EntityManager as rollback-only on the first unique-constraint violation, breaking
     * subsequent saves in the same iteration. ON CONFLICT keeps the operation idempotent
     * without that pollution.
     * <p><b>What we update on conflict</b>: only metadata (description / triggers / required_tools).
     * <b>Never touch</b> {@code owner_id} (avoids hijacking a user-owned row that happens to share
     * the name — though UNIQUE NULLS NOT DISTINCT prevents that bucket conflict in normal flow).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertSystemSkillRow(SkillDefinition def) {
        String triggers = def.getTriggers() != null ? String.join(",", def.getTriggers()) : null;
        String requiredTools = def.getRequiredTools() != null
                ? String.join(",", def.getRequiredTools())
                : null;
        // skill_path: leave as system-skills/<name>/ if def has skillPath, else NULL.
        String skillPath = def.getSkillPath();
        // ON CONFLICT must match V31's expression index uq_t_skill_owner_name —
        // (COALESCE(owner_id, -1), name). Using plain (owner_id, name) here would not
        // match an expression index and PostgreSQL would error "no unique or exclusion
        // constraint matching the ON CONFLICT specification".
        String sql = "INSERT INTO t_skill "
                + "(owner_id, name, description, triggers, required_tools, skill_path, "
                + " is_public, is_system, enabled, source, usage_count, success_count, failure_count) "
                + "VALUES (NULL, :name, :description, :triggers, :requiredTools, :skillPath, "
                + "        false, true, true, 'system', 0, 0, 0) "
                + "ON CONFLICT (COALESCE(owner_id, -1), name) DO UPDATE SET "
                + "  description    = EXCLUDED.description, "
                + "  triggers       = EXCLUDED.triggers, "
                + "  required_tools = EXCLUDED.required_tools, "
                + "  is_system      = true, "
                + "  enabled        = true";
        try {
            entityManager.createNativeQuery(sql)
                    .setParameter("name", def.getName())
                    .setParameter("description", def.getDescription())
                    .setParameter("triggers", triggers)
                    .setParameter("requiredTools", requiredTools)
                    .setParameter("skillPath", skillPath)
                    .executeUpdate();
        } catch (Exception e) {
            // Non-fatal — registry already holds the def; row sync is best-effort during startup.
            log.warn("upsertSystemSkillRow failed for '{}': {}", def.getName(), e.getMessage());
        }
    }

    private Path resolveSystemSkillsDir() {
        Path configured = Paths.get(systemSkillsDirConfig).toAbsolutePath().normalize();
        if (Files.isDirectory(configured)) {
            return configured;
        }
        // 本地常见启动路径：在 skillforge-server 模块目录执行 mvn spring-boot:run
        Path siblingDir = Paths.get("..", "system-skills").toAbsolutePath().normalize();
        if (Files.isDirectory(siblingDir)) {
            log.info("System skills fallback directory detected: {}", siblingDir);
            return siblingDir;
        }
        return configured;
    }
}
