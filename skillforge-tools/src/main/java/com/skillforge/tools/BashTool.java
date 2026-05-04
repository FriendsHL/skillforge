package com.skillforge.tools;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tool that executes shell commands via /bin/sh and returns their output.
 */
public class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_MS = 120000;
    private static final int MAX_OUTPUT_LENGTH = 50000;

    @Override
    public String getName() {
        return "Bash";
    }

    @Override
    public String getDescription() {
        return "Executes a shell command and returns its output.\n\n"
                + "IMPORTANT: Do NOT use Bash to run these commands when a dedicated tool is available:\n"
                + "- Use Read instead of cat, head, tail\n"
                + "- Use Glob instead of find or ls for file searching\n"
                + "- Use Grep instead of grep or rg for content searching\n"
                + "- Use Edit instead of sed or awk for file modifications\n"
                + "- Use Write instead of echo/cat heredoc for creating files\n\n"
                + "Command chaining guidelines:\n"
                + "- Independent commands: make separate parallel tool calls\n"
                + "- Dependent commands: chain with && in a single call\n"
                + "- Do NOT use newlines to separate commands\n\n"
                + "Git safety:\n"
                + "- Never use --no-verify or --force flags without explicit user approval\n"
                + "- Never force push to main/master\n"
                + "- Prefer new commits over amending existing ones\n\n"
                + "Avoid unnecessary sleep commands.\n"
                + "Default timeout is 120 seconds. For long-running commands (builds, tests, package installs), "
                + "pass a larger timeout value (e.g., 300000 for 5 minutes). Maximum allowed is 600 seconds.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of(
                "type", "string",
                "description", "The command to execute"
        ));
        properties.put("timeout", Map.of(
                "type", "integer",
                "description", "Timeout in milliseconds, default 120000"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("command"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String command = (String) input.get("command");
            if (command == null || command.isBlank()) {
                return SkillResult.error("command is required");
            }

            int timeout = DEFAULT_TIMEOUT_MS;
            if (input.containsKey("timeout") && input.get("timeout") != null) {
                timeout = ((Number) input.get("timeout")).intValue();
            }

            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.redirectErrorStream(true);

            String workDir = context.getWorkingDirectory();
            if (workDir != null && !workDir.isBlank()) {
                pb.directory(new File(workDir));
            }

            Process process = pb.start();
            byte[] outputBytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                return SkillResult.error("Command timed out after " + timeout + "ms");
            }

            String output = new String(outputBytes, StandardCharsets.UTF_8);
            if (output.length() > MAX_OUTPUT_LENGTH) {
                output = output.substring(0, MAX_OUTPUT_LENGTH)
                        + "\n... [output truncated, exceeded " + MAX_OUTPUT_LENGTH + " characters]";
            }

            return SkillResult.success(output);
        } catch (IOException e) {
            return SkillResult.error("Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SkillResult.error("Command execution interrupted");
        } catch (Exception e) {
            return SkillResult.error("Unexpected error: " + e.getMessage());
        }
    }
}
