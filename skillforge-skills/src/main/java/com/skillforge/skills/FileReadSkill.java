package com.skillforge.skills;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Skill that reads file content with line numbers, supporting offset/limit pagination.
 */
public class FileReadSkill implements Skill {

    private static final int DEFAULT_OFFSET = 0;
    private static final int DEFAULT_LIMIT = 2000;

    @Override
    public String getName() {
        return "FileRead";
    }

    @Override
    public String getDescription() {
        return "Reads a file from the filesystem and returns its content with line numbers.\n\n"
                + "- The file_path parameter must be an absolute path, not a relative path\n"
                + "- When you already know which part of the file you need, use offset and limit to read only that part\n"
                + "- Always read a file before editing or overwriting it\n"
                + "- Use this tool instead of running cat/head/tail via Bash";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "Absolute path to the file"
        ));
        properties.put("offset", Map.of(
                "type", "integer",
                "description", "Starting line number (0-based), default 0"
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "Number of lines to read, default 2000"
        ));

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

            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return SkillResult.error("File does not exist: " + filePath);
            }
            if (Files.isDirectory(path)) {
                return SkillResult.error("Path is a directory, not a file: " + filePath);
            }

            int offset = DEFAULT_OFFSET;
            if (input.containsKey("offset") && input.get("offset") != null) {
                offset = ((Number) input.get("offset")).intValue();
            }
            int limit = DEFAULT_LIMIT;
            if (input.containsKey("limit") && input.get("limit") != null) {
                limit = ((Number) input.get("limit")).intValue();
            }

            List<String> allLines = Files.readAllLines(path);
            int start = Math.min(offset, allLines.size());
            int end = Math.min(start + limit, allLines.size());
            List<String> lines = allLines.subList(start, end);

            String result = IntStream.range(0, lines.size())
                    .mapToObj(i -> String.format("%3d\t%s", start + i + 1, lines.get(i)))
                    .collect(Collectors.joining("\n"));

            // FileRead dedup removed — cross-session cache + compaction conflict issues

            return SkillResult.success(result);
        } catch (IOException e) {
            return SkillResult.error("Failed to read file: " + e.getMessage());
        } catch (Exception e) {
            return SkillResult.error("Unexpected error: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }
}
