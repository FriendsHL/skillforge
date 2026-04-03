package com.skillforge.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.skillforge.core.model.SkillDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 解压 Skill zip 包，解析 skill.yaml 和 SKILL.md，返回 SkillDefinition。
 */
public class SkillPackageLoader {

    private static final String SKILL_YAML = "skill.yaml";
    private static final String SKILL_MD = "SKILL.md";

    private final ObjectMapper yamlMapper = new YAMLMapper();

    /**
     * 解压 zip 到目标目录，解析并返回 SkillDefinition。
     *
     * @param zipInputStream zip 文件输入流
     * @param targetDir      解压目标目录 (如 /data/skills/{userId}/{skillId}/)
     * @return 解析后的 SkillDefinition
     * @throws IOException 如果解压、解析失败或缺少必须文件
     */
    public SkillDefinition loadFromZip(InputStream zipInputStream, Path targetDir) throws IOException {
        if (zipInputStream == null) {
            throw new IllegalArgumentException("zipInputStream must not be null");
        }
        if (targetDir == null) {
            throw new IllegalArgumentException("targetDir must not be null");
        }

        // 确保目标目录存在
        Files.createDirectories(targetDir);
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();

        // 解压 zip
        try (ZipInputStream zis = new ZipInputStream(zipInputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                // 获取文件名（去掉可能的目录前缀，只取最后一级文件名）
                String entryName = entry.getName();
                String fileName = Path.of(entryName).getFileName().toString();

                // 计算目标路径并防止 zip slip 攻击
                Path destPath = normalizedTarget.resolve(fileName).normalize();
                if (!destPath.startsWith(normalizedTarget)) {
                    throw new IOException("Zip slip detected: entry '" + entryName
                            + "' resolves outside target directory");
                }

                // 写出文件
                Files.copy(zis, destPath);
                zis.closeEntry();
            }
        }

        // 验证必须文件存在
        Path skillYamlPath = normalizedTarget.resolve(SKILL_YAML);
        Path skillMdPath = normalizedTarget.resolve(SKILL_MD);

        if (!Files.exists(skillYamlPath)) {
            throw new IOException("Missing required file: " + SKILL_YAML + " in skill package");
        }
        if (!Files.exists(skillMdPath)) {
            throw new IOException("Missing required file: " + SKILL_MD + " in skill package");
        }

        // 解析 skill.yaml
        SkillDefinition definition;
        try {
            definition = yamlMapper.readValue(skillYamlPath.toFile(), SkillDefinition.class);
        } catch (Exception e) {
            throw new IOException("Failed to parse " + SKILL_YAML + ": " + e.getMessage(), e);
        }

        // 校验必要字段
        if (definition.getName() == null || definition.getName().isBlank()) {
            throw new IOException("skill.yaml must contain a non-empty 'name' field");
        }

        // 读取 SKILL.md
        String promptContent = Files.readString(skillMdPath, StandardCharsets.UTF_8);
        definition.setPromptContent(promptContent);

        // 设置 skillPath
        definition.setSkillPath(normalizedTarget.toString());

        return definition;
    }
}
