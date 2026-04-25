package com.skillforge.tools;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool that writes content to a file, creating parent directories if needed.
 */
public class FileWriteTool implements Tool {

    @Override
    public String getName() {
        return "FileWrite";
    }

    @Override
    public String getDescription() {
        return "Writes content to a file, creating it if it doesn't exist.\n\n"
                + "- If the file already exists, you MUST use FileRead first to read its contents\n"
                + "- Prefer FileEdit for modifying existing files — it only sends the diff\n"
                + "- Only use this tool to create new files or for complete rewrites";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "Absolute path to the file"
        ));
        properties.put("content", Map.of(
                "type", "string",
                "description", "The content to write to the file"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path", "content"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String filePath = (String) input.get("file_path");
            if (filePath == null || filePath.isBlank()) {
                return SkillResult.validationError("file_path is required");
            }
            String content = (String) input.get("content");
            if (content == null) {
                return SkillResult.validationError("content is required");
            }

            Path path = Path.of(filePath);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content, StandardCharsets.UTF_8);
            long bytes = content.getBytes(StandardCharsets.UTF_8).length;

            return SkillResult.success("Successfully wrote " + bytes + " bytes to " + filePath);
        } catch (IOException e) {
            return SkillResult.error("Failed to write file: " + e.getMessage());
        } catch (Exception e) {
            return SkillResult.error("Unexpected error: " + e.getMessage());
        }
    }
}
