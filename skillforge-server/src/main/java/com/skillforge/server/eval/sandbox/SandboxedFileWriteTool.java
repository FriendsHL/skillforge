package com.skillforge.server.eval.sandbox;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sandboxed Write that restricts writes to within the sandbox root directory.
 */
class SandboxedFileWriteTool implements Tool {

    private final Path sandboxRoot;

    SandboxedFileWriteTool(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot;
    }

    @Override
    public String getName() { return "Write"; }

    @Override
    public String getDescription() {
        return "Writes content to a file, creating it if it doesn't exist.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of("type", "string", "description", "Absolute path to the file"));
        properties.put("content", Map.of("type", "string", "description", "The content to write to the file"));
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
                return SkillResult.error("file_path is required");
            }
            String content = (String) input.get("content");
            if (content == null) {
                return SkillResult.error("content is required");
            }

            Path resolved = Path.of(filePath).normalize();
            if (!resolved.startsWith(sandboxRoot)) {
                return SkillResult.error("Access denied: path is outside sandbox directory");
            }

            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            long bytes = content.getBytes(StandardCharsets.UTF_8).length;
            return SkillResult.success("Successfully wrote " + bytes + " bytes to " + filePath);
        } catch (Exception e) {
            return SkillResult.error("Failed to write file: " + e.getMessage());
        }
    }
}
