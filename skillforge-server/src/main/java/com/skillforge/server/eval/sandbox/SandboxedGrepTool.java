package com.skillforge.server.eval.sandbox;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sandboxed Grep that restricts searches to within the sandbox root directory.
 */
class SandboxedGrepTool implements Tool {

    private static final int MAX_RESULTS = 500;
    private final Path sandboxRoot;

    SandboxedGrepTool(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot;
    }

    @Override
    public String getName() { return "Grep"; }

    @Override
    public String getDescription() {
        return "Searches file contents using regex pattern within the sandbox.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", Map.of("type", "string", "description", "Regex pattern to search for"));
        properties.put("path", Map.of("type", "string", "description", "Directory or file to search in"));
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

            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            List<String> results = new ArrayList<>();

            if (Files.isRegularFile(searchRoot)) {
                searchFile(searchRoot, pattern, results);
            } else {
                Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (results.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                        searchFile(file, pattern, results);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            if (results.isEmpty()) {
                return SkillResult.success("No matches found for pattern: " + patternStr);
            }
            return SkillResult.success(String.join("\n", results));
        } catch (Exception e) {
            return SkillResult.error("Grep failed: " + e.getMessage());
        }
    }

    private void searchFile(Path file, Pattern pattern, List<String> results) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size() && results.size() < MAX_RESULTS; i++) {
                Matcher matcher = pattern.matcher(lines.get(i));
                if (matcher.find()) {
                    results.add(file + ":" + (i + 1) + ":" + lines.get(i));
                }
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean isReadOnly() { return true; }
}
