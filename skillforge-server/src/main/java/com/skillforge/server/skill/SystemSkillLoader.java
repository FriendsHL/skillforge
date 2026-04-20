package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时扫描 system-skills/ 目录，将所有子目录作为 system skill 加载并注册到 SkillRegistry。
 * System skill 标记 system=true，不可被用户删除。
 */
@Component
public class SystemSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SystemSkillLoader.class);

    private final SkillRegistry skillRegistry;
    private final SkillPackageLoader packageLoader;

    @org.springframework.beans.factory.annotation.Value("${skillforge.system-skills-dir:system-skills}")
    private String systemSkillsDirConfig;

    public SystemSkillLoader(SkillRegistry skillRegistry, SkillPackageLoader packageLoader) {
        this.skillRegistry = skillRegistry;
        this.packageLoader = packageLoader;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSystemSkills() {
        Path systemSkillsDir = resolveSystemSkillsDir();
        if (!Files.isDirectory(systemSkillsDir)) {
            log.error("System skills directory not found: {}. System skills (clawhub, github, skillhub) will NOT be available. "
                    + "Set skillforge.system-skills-dir to the correct path.", systemSkillsDir);
            return;
        }

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
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan system skills directory: {}", e.getMessage());
        }

        log.info("System skill loading complete: {} skills registered", loaded);
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
