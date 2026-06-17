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
 * Sandboxed Edit that restricts edits to within the sandbox root directory.
 *
 * <p>Mirrors {@link com.skillforge.tools.FileEditTool} byte-for-byte on edit
 * semantics AND error messages — this is a hard requirement: the eval sandbox
 * must reproduce the EXACT same failure signatures the production Edit tool
 * emits ("old_string not found in file", "old_string is not unique in the
 * file (found N occurrences)...", "File does not exist: ...") so a harvested
 * bad case replays the same error a real session hit. The only addition over
 * the production tool is the sandbox-root boundary check (same wording as
 * {@link SandboxedFileWriteTool}: "Access denied: path is outside sandbox
 * directory").
 */
class SandboxedFileEditTool implements Tool {

    private final Path sandboxRoot;

    SandboxedFileEditTool(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot;
    }

    @Override
    public String getName() { return "Edit"; }

    @Override
    public String getDescription() {
        // Intentionally minimal — the fidelity that matters for reproducing a
        // harvested bad case is the edit/error SEMANTICS (below) + error message
        // text, NOT the description prose. The usage-guidance bullets that the
        // production tool carries are deliberately omitted so the eval
        // environment stays neutral and doesn't hand the agent any extra
        // procedural hint. Omitting guidance cannot suppress a failure (it only
        // removes help), so reproduction fidelity is preserved.
        return "Performs exact string replacement in a file. "
                + "Provide file_path, old_string (the text to replace), and new_string.";
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
                return SkillResult.validationError("file_path is required");
            }
            String oldString = (String) input.get("old_string");
            if (oldString == null) {
                return SkillResult.validationError("old_string is required");
            }
            String newString = (String) input.get("new_string");
            if (newString == null) {
                return SkillResult.validationError("new_string is required");
            }

            boolean replaceAll = false;
            if (input.containsKey("replace_all") && input.get("replace_all") != null) {
                replaceAll = (Boolean) input.get("replace_all");
            }

            // Sandbox boundary check (the only addition over FileEditTool).
            Path path = Path.of(filePath).normalize();
            if (!path.startsWith(sandboxRoot)) {
                return SkillResult.error("Access denied: path is outside sandbox directory");
            }

            // ── From here on, byte-identical to FileEditTool error/edit semantics ──
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
        } catch (Exception e) {
            return SkillResult.error("Failed to edit file: " + e.getMessage());
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
