package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

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
    private final SkillForgeHomeResolver homeResolver;
    private final SkillCatalogReconciler reconciler;

    public SystemSkillLoader(SkillRegistry skillRegistry, SkillPackageLoader packageLoader,
                             ApplicationContext applicationContext,
                             SkillForgeHomeResolver homeResolver,
                             SkillCatalogReconciler reconciler) {
        this.skillRegistry = skillRegistry;
        this.packageLoader = packageLoader;
        this.applicationContext = applicationContext;
        this.homeResolver = homeResolver;
        this.reconciler = reconciler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSystemSkills() {
        Path systemSkillsDir = homeResolver.getSystemSkillsDir();
        if (!Files.isDirectory(systemSkillsDir)) {
            log.error("System skills directory not found: {}. System skills (clawhub, github, skillhub, "
                    + "browser, skill-creator, grill-me) will NOT be available. Verify SKILLFORGE_HOME "
                    + "or run from project root.", systemSkillsDir);
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

        // P1-D §T6 — delegate t_skill row upsert + content_hash tracking to the reconciler.
        // Logs RescanReport for operator visibility.
        try {
            RescanReport sysReport = reconciler.reconcileSystem();
            log.info("System reconcile report: {}", sysReport);
        } catch (Exception e) {
            log.error("System reconcile failed; system skills are still in registry but t_skill "
                    + "rows may be stale. Error: {}", e.getMessage(), e);
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

}
