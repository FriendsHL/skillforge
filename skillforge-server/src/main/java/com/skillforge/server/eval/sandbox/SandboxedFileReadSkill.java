package com.skillforge.server.eval.sandbox;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Sandboxed FileRead that restricts reads to within the sandbox root directory.
 */
class SandboxedFileReadSkill implements Skill {

    private final Path sandboxRoot;

    SandboxedFileReadSkill(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot;
    }

    @Override
    public String getName() { return "FileRead"; }

    @Override
    public String getDescription() {
        return "Reads a file from the filesystem and returns its content with line numbers.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of("type", "string", "description", "Absolute path to the file"));
        properties.put("offset", Map.of("type", "integer", "description", "Starting line number (0-based), default 0"));
        properties.put("limit", Map.of("type", "integer", "description", "Number of lines to read, default 2000"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String filePath = (String) input.get("file_path");
            if (filePath == null || filePath.isBlank()) {
                return SkillResult.error("file_path is required");
            }

            Path resolved = Path.of(filePath).normalize();
            if (!resolved.startsWith(sandboxRoot)) {
                return SkillResult.error("Access denied: path is outside sandbox directory");
            }

            if (!Files.exists(resolved)) {
                return SkillResult.error("File does not exist: " + filePath);
            }
            if (Files.isDirectory(resolved)) {
                return SkillResult.error("Path is a directory, not a file: " + filePath);
            }

            int offset = 0;
            if (input.containsKey("offset") && input.get("offset") != null) {
                offset = ((Number) input.get("offset")).intValue();
            }
            int limit = 2000;
            if (input.containsKey("limit") && input.get("limit") != null) {
                limit = ((Number) input.get("limit")).intValue();
            }

            List<String> allLines = Files.readAllLines(resolved);
            int start = Math.min(offset, allLines.size());
            int end = Math.min(start + limit, allLines.size());
            List<String> lines = allLines.subList(start, end);

            String result = IntStream.range(0, lines.size())
                    .mapToObj(i -> String.format("%3d\t%s", start + i + 1, lines.get(i)))
                    .collect(Collectors.joining("\n"));

            return SkillResult.success(result);
        } catch (Exception e) {
            return SkillResult.error("Failed to read file: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly() { return true; }
}
