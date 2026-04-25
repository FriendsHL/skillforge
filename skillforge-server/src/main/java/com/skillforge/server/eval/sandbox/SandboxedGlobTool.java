package com.skillforge.server.eval.sandbox;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sandboxed Glob that restricts file search to within the sandbox root directory.
 */
class SandboxedGlobTool implements Tool {

    private static final int MAX_RESULTS = 1000;
    private final Path sandboxRoot;

    SandboxedGlobTool(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot;
    }

    @Override
    public String getName() { return "Glob"; }

    @Override
    public String getDescription() {
        return "Finds files matching a glob pattern within the sandbox.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", Map.of("type", "string", "description", "Glob pattern, e.g. \"**/*.txt\""));
        properties.put("path", Map.of("type", "string", "description", "Directory to search in"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("pattern"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String patternStr = (String) input.get("pattern");
            if (patternStr == null || patternStr.isBlank()) {
                return SkillResult.error("pattern is required");
            }

            String pathStr = (String) input.get("path");
            Path searchRoot = pathStr != null ? Path.of(pathStr).normalize() : sandboxRoot;
            if (!searchRoot.startsWith(sandboxRoot)) {
                return SkillResult.error("Access denied: path is outside sandbox directory");
            }
            if (!Files.exists(searchRoot)) {
                return SkillResult.error("Path does not exist: " + searchRoot);
            }

            PathMatcher matcher = searchRoot.getFileSystem().getPathMatcher("glob:" + patternStr);
            List<String> results = new ArrayList<>();

            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    Path relative = searchRoot.relativize(file);
                    if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                        results.add(file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) {
                return SkillResult.success("No files found matching pattern: " + patternStr);
            }
            return SkillResult.success(String.join("\n", results));
        } catch (Exception e) {
            return SkillResult.error("Glob failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly() { return true; }
}
