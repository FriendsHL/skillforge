package com.skillforge.server.service;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;
    private final SkillPackageLoader skillPackageLoader;

    @Value("${skillforge.skills-dir:./data/skills}")
    private String skillsDir;

    public SkillService(SkillRepository skillRepository,
                        SkillRegistry skillRegistry,
                        SkillPackageLoader skillPackageLoader) {
        this.skillRepository = skillRepository;
        this.skillRegistry = skillRegistry;
        this.skillPackageLoader = skillPackageLoader;
    }

    /**
     * 上传并注册 Skill zip 包。
     * 1. 生成 skillId
     * 2. 创建目标目录: {skillsDir}/{ownerId}/{skillId}/
     * 3. 调用 SkillPackageLoader 解压解析
     * 4. 保存 SkillEntity 到数据库
     * 5. 注册到 SkillRegistry
     * 6. 返回 SkillEntity
     */
    public SkillEntity uploadSkill(MultipartFile file, Long ownerId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }

        String skillId = UUID.randomUUID().toString();
        Path targetDir = Path.of(skillsDir, String.valueOf(ownerId), skillId);

        try {
            // 解压并解析
            SkillDefinition definition = skillPackageLoader.loadFromZip(file.getInputStream(), targetDir);
            definition.setId(skillId);
            definition.setOwnerId(String.valueOf(ownerId));

            // 构建 SkillEntity
            SkillEntity entity = new SkillEntity();
            entity.setName(definition.getName());
            entity.setDescription(definition.getDescription());
            entity.setSkillPath(targetDir.toAbsolutePath().toString());
            entity.setOwnerId(ownerId);
            entity.setPublic(definition.isPublic());

            // triggers 和 requiredTools 存为逗号分隔字符串
            if (definition.getTriggers() != null && !definition.getTriggers().isEmpty()) {
                entity.setTriggers(String.join(",", definition.getTriggers()));
            }
            if (definition.getRequiredTools() != null && !definition.getRequiredTools().isEmpty()) {
                entity.setRequiredTools(String.join(",", definition.getRequiredTools()));
            }

            // 保存到数据库
            SkillEntity saved = skillRepository.save(entity);
            log.info("Skill saved to database: id={}, name={}", saved.getId(), saved.getName());

            // 注册到 SkillRegistry
            skillRegistry.registerSkillDefinition(definition);
            log.info("Skill registered to SkillRegistry: name={}", definition.getName());

            return saved;

        } catch (IOException e) {
            // 清理已创建的目录
            deleteDirectoryQuietly(targetDir);
            throw new RuntimeException("Failed to process skill package: " + e.getMessage(), e);
        }
    }

    /**
     * 获取指定 owner 的 Skill 列表。
     */
    public List<SkillEntity> listSkills(Long ownerId) {
        return skillRepository.findByOwnerId(ownerId);
    }

    /**
     * 获取公共 Skill 列表。
     */
    public List<SkillEntity> listPublicSkills() {
        return skillRepository.findByIsPublicTrue();
    }

    /**
     * 删除 Skill，包括文件和数据库记录。
     */
    public void deleteSkill(Long id) {
        SkillEntity entity = skillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + id));

        // 从 SkillRegistry 注销
        skillRegistry.unregisterSkillDefinition(entity.getName());

        // 删除文件目录
        if (entity.getSkillPath() != null) {
            deleteDirectoryQuietly(Path.of(entity.getSkillPath()));
        }

        // 删除数据库记录
        skillRepository.deleteById(id);
        log.info("Skill deleted: id={}, name={}", id, entity.getName());
    }

    /**
     * 获取 Skill 的 SKILL.md 内容。
     */
    public String getSkillPromptContent(Long id) {
        SkillEntity entity = skillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + id));

        if (entity.getSkillPath() == null) {
            throw new RuntimeException("Skill path not set for skill: " + id);
        }

        Path skillMdPath = Path.of(entity.getSkillPath(), "SKILL.md");
        try {
            return Files.readString(skillMdPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SKILL.md for skill " + id + ": " + e.getMessage(), e);
        }
    }

    private void deleteDirectoryQuietly(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    log.warn("Failed to delete: {}", path, e);
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean up directory: {}", dir, e);
        }
    }
}
