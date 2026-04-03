package com.skillforge.skills;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill that finds files matching a glob pattern using FileVisitor.
 */
public class GlobSkill implements Skill {

    private static final int MAX_RESULTS = 1000;

    @Override
    public String getName() {
        return "Glob";
    }

    @Override
    public String getDescription() {
        return "Finds files matching a glob pattern";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", Map.of(
                "type", "string",
                "description", "Glob pattern, e.g. \"**/*.java\""
        ));
        properties.put("path", Map.of(
                "type", "string",
                "description", "Directory to search in, defaults to working directory"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("pattern"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String pattern = (String) input.get("pattern");
            if (pattern == null || pattern.isBlank()) {
                return SkillResult.error("pattern is required");
            }

            String searchPath = (String) input.get("path");
            if (searchPath == null || searchPath.isBlank()) {
                searchPath = context.getWorkingDirectory();
            }

            Path root = Path.of(searchPath);
            if (!Files.isDirectory(root)) {
                return SkillResult.error("Path is not a directory: " + searchPath);
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> results = new ArrayList<>();

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = root.relativize(file);
                    if (matcher.matches(relative)) {
                        results.add(file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) {
                return SkillResult.success("No files matched pattern: " + pattern);
            }

            String output = String.join("\n", results);
            if (results.size() >= MAX_RESULTS) {
                output += "\n... [results truncated at " + MAX_RESULTS + " files]";
            }
            return SkillResult.success(output);
        } catch (IOException e) {
            return SkillResult.error("Failed to search files: " + e.getMessage());
        } catch (Exception e) {
            return SkillResult.error("Unexpected error: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }
}
