package com.skillforge.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.skillforge.core.model.SkillDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skill 包加载器，支持 Claude Code 标准格式：
 * <pre>
 * my-skill.zip (或目录)
 * ├── SKILL.md              # 必需：frontmatter(元数据) + 指令内容
 * ├── reference.md           # 可选：详细参考文档
 * ├── examples.md            # 可选：示例
 * ├── template.md            # 可选：模板
 * ├── scripts/               # 可选：可执行脚本
 * │   └── helper.py
 * └── docs/                  # 可选：扩展文档
 *     └── api-reference.md
 * </pre>
 *
 * SKILL.md 格式：
 * <pre>
 * ---
 * name: skill-name
 * description: What this skill does
 * allowed-tools: Read Grep Bash
 * ---
 *
 * # Skill instructions here...
 * </pre>
 *
 * 同时向后兼容旧格式（独立的 skill.yaml + SKILL.md）。
 */
public class SkillPackageLoader {

    private static final String SKILL_MD = "SKILL.md";
    private static final String SKILL_MD_LOWER = "skill.md";
    private static final String SKILL_YAML = "skill.yaml";
    private static final String FRONTMATTER_DELIMITER = "---";

    private final ObjectMapper yamlMapper = new YAMLMapper();

    /**
     * 从 zip 流解压并加载 Skill。
     */
    public SkillDefinition loadFromZip(InputStream zipInputStream, Path targetDir) throws IOException {
        if (zipInputStream == null) throw new IllegalArgumentException("zipInputStream must not be null");
        if (targetDir == null) throw new IllegalArgumentException("targetDir must not be null");

        Files.createDirectories(targetDir);
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();

        // 解压 zip，保留目录结构
        extractZip(zipInputStream, normalizedTarget);

        // 加载 Skill
        return loadFromDirectory(normalizedTarget);
    }

    /**
     * 从已存在的目录加载 Skill。
     */
    public SkillDefinition loadFromDirectory(Path skillDir) throws IOException {
        Path normalizedDir = skillDir.toAbsolutePath().normalize();

        // 1. 找到 SKILL.md
        Path skillMdPath = findSkillMd(normalizedDir);
        if (skillMdPath == null) {
            throw new IOException("Missing required file: SKILL.md (or skill.md) in skill package");
        }

        String rawContent = Files.readString(skillMdPath, StandardCharsets.UTF_8);

        // 2. 解析 frontmatter + 正文
        SkillDefinition definition;
        String promptContent;

        if (rawContent.startsWith(FRONTMATTER_DELIMITER)) {
            // 有 frontmatter -> Claude Code 标准格式
            String[] parts = parseFrontmatter(rawContent);
            String frontmatterYaml = parts[0];
            promptContent = parts[1];

            try {
                definition = yamlMapper.readValue(frontmatterYaml, SkillDefinition.class);
            } catch (Exception e) {
                throw new IOException("Failed to parse SKILL.md frontmatter: " + e.getMessage(), e);
            }
        } else {
            // 无 frontmatter -> 检查是否有独立的 skill.yaml（向后兼容）
            Path skillYamlPath = normalizedDir.resolve(SKILL_YAML);
            if (Files.exists(skillYamlPath)) {
                try {
                    definition = yamlMapper.readValue(skillYamlPath.toFile(), SkillDefinition.class);
                } catch (Exception e) {
                    throw new IOException("Failed to parse skill.yaml: " + e.getMessage(), e);
                }
            } else {
                // 都没有 -> 从 Markdown 内容自动提取
                definition = new SkillDefinition();
                definition.setName(extractNameFromMarkdown(rawContent));
                definition.setDescription(extractDescriptionFromMarkdown(rawContent));
            }
            promptContent = rawContent;
        }

        // 3. 兜底 name
        if (definition.getName() == null || definition.getName().isBlank()) {
            // 用目录名作为 skill name
            definition.setName(normalizedDir.getFileName().toString());
        }

        definition.setPromptContent(promptContent);
        definition.setSkillPath(normalizedDir.toString());

        // 4. 加载辅助文件 (reference.md, examples.md, template.md, docs/*.md)
        Map<String, String> references = new HashMap<>();
        loadReferenceFile(normalizedDir, "reference.md", references);
        loadReferenceFile(normalizedDir, "examples.md", references);
        loadReferenceFile(normalizedDir, "template.md", references);

        // docs/ 目录下的所有 .md 文件
        Path docsDir = normalizedDir.resolve("docs");
        if (Files.isDirectory(docsDir)) {
            try (var stream = Files.list(docsDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .forEach(p -> {
                            try {
                                references.put("docs/" + p.getFileName(), Files.readString(p, StandardCharsets.UTF_8));
                            } catch (IOException ignored) {}
                        });
            }
        }
        definition.setReferences(references);

        // 5. 收集 scripts/ 目录下的脚本路径
        List<String> scriptPaths = new ArrayList<>();
        Path scriptsDir = normalizedDir.resolve("scripts");
        if (Files.isDirectory(scriptsDir)) {
            try (var stream = Files.walk(scriptsDir)) {
                stream.filter(Files::isRegularFile)
                        .forEach(p -> scriptPaths.add(p.toAbsolutePath().toString()));
            }
        }
        definition.setScriptPaths(scriptPaths);

        // 6. 处理 allowed-tools -> requiredTools 映射
        if (definition.getAllowedTools() != null && definition.getRequiredTools().isEmpty()) {
            definition.setRequiredTools(definition.getAllowedTools());
        }

        return definition;
    }

    /**
     * 解压 zip 到目标目录，保留子目录结构。
     */
    private void extractZip(InputStream zipInputStream, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipInputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 去掉可能的顶层单目录包裹（如 my-skill/SKILL.md -> SKILL.md）
                String entryName = stripTopLevelDir(entry.getName());
                if (entryName.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                Path destPath = targetDir.resolve(entryName).normalize();
                if (!destPath.startsWith(targetDir)) {
                    throw new IOException("Zip slip detected: " + entryName);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(destPath);
                } else {
                    Files.createDirectories(destPath.getParent());
                    Files.copy(zis, destPath);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 如果 zip 内所有文件都在同一个顶层目录下，去掉这层目录。
     * 例如 "my-skill/SKILL.md" -> "SKILL.md", "my-skill/scripts/a.py" -> "scripts/a.py"
     */
    private String stripTopLevelDir(String entryName) {
        // 简单策略：如果路径包含 /，检查第一段是否像目录名
        if (entryName.contains("/")) {
            int idx = entryName.indexOf('/');
            String rest = entryName.substring(idx + 1);
            // 如果去掉第一段后仍有内容，且第一段不是 scripts/docs 等已知子目录
            String first = entryName.substring(0, idx);
            if (!rest.isEmpty() && !first.equals("scripts") && !first.equals("docs")) {
                return rest;
            }
        }
        return entryName;
    }

    /**
     * 查找 SKILL.md 文件（大小写兼容）。
     */
    private Path findSkillMd(Path dir) {
        Path p = dir.resolve(SKILL_MD);
        if (Files.exists(p)) return p;
        p = dir.resolve(SKILL_MD_LOWER);
        if (Files.exists(p)) return p;
        return null;
    }

    /**
     * 解析 YAML frontmatter。返回 [frontmatterYaml, bodyContent]。
     */
    private String[] parseFrontmatter(String content) {
        // 第一行必须是 ---
        int firstDelim = content.indexOf(FRONTMATTER_DELIMITER);
        int secondDelim = content.indexOf(FRONTMATTER_DELIMITER, firstDelim + 3);

        if (secondDelim == -1) {
            // 没有结束的 ---，整个内容当做正文
            return new String[]{"", content};
        }

        String frontmatter = content.substring(firstDelim + 3, secondDelim).trim();
        String body = content.substring(secondDelim + 3).trim();
        return new String[]{frontmatter, body};
    }

    private void loadReferenceFile(Path dir, String fileName, Map<String, String> refs) {
        Path file = dir.resolve(fileName);
        if (Files.exists(file)) {
            try {
                refs.put(fileName, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        }
    }

    private String extractNameFromMarkdown(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).trim();
                return title.toLowerCase()
                        .replaceAll("[^a-z0-9\\u4e00-\\u9fff\\s-]", "")
                        .replaceAll("\\s+", "-")
                        .replaceAll("-+", "-")
                        .replaceAll("^-|-$", "");
            }
        }
        return null;
    }

    private String extractDescriptionFromMarkdown(String content) {
        boolean foundTitle = false;
        StringBuilder desc = new StringBuilder();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) { foundTitle = true; continue; }
            if (foundTitle && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                desc.append(trimmed);
                if (desc.length() > 200) break;
            }
            if (foundTitle && desc.length() > 0 && (trimmed.isEmpty() || trimmed.startsWith("#"))) {
                break;
            }
        }
        return desc.length() > 0 ? desc.toString() : null;
    }
}
