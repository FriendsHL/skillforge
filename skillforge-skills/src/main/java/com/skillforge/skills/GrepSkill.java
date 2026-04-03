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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Skill that searches file contents using regex, similar to grep.
 */
public class GrepSkill implements Skill {

    private static final int MAX_RESULTS = 500;

    @Override
    public String getName() {
        return "Grep";
    }

    @Override
    public String getDescription() {
        return "Searches file contents using regex pattern";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", Map.of(
                "type", "string",
                "description", "Regular expression pattern to search for"
        ));
        properties.put("path", Map.of(
                "type", "string",
                "description", "Directory to search in, defaults to working directory"
        ));
        properties.put("glob", Map.of(
                "type", "string",
                "description", "File filter pattern, e.g. \"*.java\""
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
            String patternStr = (String) input.get("pattern");
            if (patternStr == null || patternStr.isBlank()) {
                return SkillResult.error("pattern is required");
            }

            Pattern regex;
            try {
                regex = Pattern.compile(patternStr);
            } catch (PatternSyntaxException e) {
                return SkillResult.error("Invalid regex pattern: " + e.getMessage());
            }

            String searchPath = (String) input.get("path");
            if (searchPath == null || searchPath.isBlank()) {
                searchPath = context.getWorkingDirectory();
            }

            Path root = Path.of(searchPath);
            if (!Files.isDirectory(root)) {
                return SkillResult.error("Path is not a directory: " + searchPath);
            }

            String globPattern = (String) input.get("glob");
            PathMatcher globMatcher = (globPattern != null && !globPattern.isBlank())
                    ? FileSystems.getDefault().getPathMatcher("glob:" + globPattern)
                    : null;

            List<String> matches = new ArrayList<>();

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (globMatcher != null && !globMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    // Skip binary/large files
                    try {
                        if (attrs.size() > 5 * 1024 * 1024) {
                            return FileVisitResult.CONTINUE;
                        }
                        String mimeType = Files.probeContentType(file);
                        if (mimeType != null && !mimeType.startsWith("text") && !mimeType.contains("json")
                                && !mimeType.contains("xml") && !mimeType.contains("javascript")) {
                            return FileVisitResult.CONTINUE;
                        }

                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size() && matches.size() < MAX_RESULTS; i++) {
                            Matcher m = regex.matcher(lines.get(i));
                            if (m.find()) {
                                matches.add(file + ":" + (i + 1) + ":" + lines.get(i));
                            }
                        }
                    } catch (IOException e) {
                        // Skip unreadable files
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            if (matches.isEmpty()) {
                return SkillResult.success("No matches found for pattern: " + patternStr);
            }

            String output = String.join("\n", matches);
            if (matches.size() >= MAX_RESULTS) {
                output += "\n... [results truncated at " + MAX_RESULTS + " matches]";
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
