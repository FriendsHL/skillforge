package com.skillforge.skills;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
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
 * Skill that performs exact string replacement in a file.
 */
public class FileEditSkill implements Skill {

    @Override
    public String getName() {
        return "FileEdit";
    }

    @Override
    public String getDescription() {
        return "Performs exact string replacement in a file.\n\n"
                + "- You must use FileRead at least once before editing a file\n"
                + "- The old_string must be unique in the file; if not, provide more surrounding context to make it unique, or use replace_all\n"
                + "- Prefer editing existing files over creating new ones\n"
                + "- Use this tool instead of sed/awk via Bash";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "Absolute path to the file"
        ));
        properties.put("old_string", Map.of(
                "type", "string",
                "description", "The text to replace"
        ));
        properties.put("new_string", Map.of(
                "type", "string",
                "description", "The replacement text"
        ));
        properties.put("replace_all", Map.of(
                "type", "boolean",
                "description", "Replace all occurrences, default false"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path", "old_string", "new_string"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String filePath = (String) input.get("file_path");
            if (filePath == null || filePath.isBlank()) {
                return SkillResult.error("file_path is required");
            }
            String oldString = (String) input.get("old_string");
            if (oldString == null) {
                return SkillResult.error("old_string is required");
            }
            String newString = (String) input.get("new_string");
            if (newString == null) {
                return SkillResult.error("new_string is required");
            }

            boolean replaceAll = false;
            if (input.containsKey("replace_all") && input.get("replace_all") != null) {
                replaceAll = (Boolean) input.get("replace_all");
            }

            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return SkillResult.error("File does not exist: " + filePath);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);

            if (!content.contains(oldString)) {
                return SkillResult.error("old_string not found in file");
            }

            int occurrences = countOccurrences(content, oldString);
            if (!replaceAll && occurrences > 1) {
                return SkillResult.error("old_string is not unique in the file (found "
                        + occurrences + " occurrences). Use replace_all=true or provide more context.");
            }

            String updated;
            if (replaceAll) {
                updated = content.replace(oldString, newString);
            } else {
                int index = content.indexOf(oldString);
                updated = content.substring(0, index) + newString + content.substring(index + oldString.length());
            }

            Files.writeString(path, updated, StandardCharsets.UTF_8);

            String msg = replaceAll
                    ? "Replaced all " + occurrences + " occurrences in " + filePath
                    : "Replaced 1 occurrence in " + filePath;
            return SkillResult.success(msg);
        } catch (IOException e) {
            return SkillResult.error("Failed to edit file: " + e.getMessage());
        } catch (Exception e) {
            return SkillResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private int countOccurrences(String content, String target) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }
}
