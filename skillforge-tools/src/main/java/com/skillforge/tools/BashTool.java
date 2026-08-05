package com.skillforge.tools;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Tool that executes shell commands via /bin/sh and returns their output.
 */
public class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 600_000;
    private static final int MAX_OUTPUT_BYTES = 50_000;
    private static final long PROCESS_KILL_GRACE_MS = 500;
    private static final long OUTPUT_DRAIN_GRACE_MS = 1_000;
    private static final Pattern SENSITIVE_ENV_NAME = Pattern.compile(
            "(?i).*(API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|PRIVATE_?KEY).*");

    @Override
    public String getName() {
        return "Bash";
    }

    @Override
    public String getDescription() {
        return "Executes one shell command with /bin/sh in the session working directory. "
                + "Use for builds, tests, git, process inspection, and shell-native operations; "
                + "prefer dedicated Read, Grep, Glob, and Edit tools for file operations; "
                + "for browser automation, first load the browser Skill and then run its Playwright workflow. "
                + "Returns merged stdout/stderr when the exit status is 0; a non-zero exit status, timeout, "
                + "or launch failure returns an error. timeout is milliseconds, defaults to 120000, "
                + "and must be between 100 and 600000.";
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
                "minimum", MIN_TIMEOUT_MS,
                "maximum", MAX_TIMEOUT_MS,
                "description", "Timeout in milliseconds (100-600000), default 120000"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("command"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        Process process = null;
        OutputCollector collector = null;
        try {
            Object commandValue = input.get("command");
            if (!(commandValue instanceof String command) || command.isBlank()) {
                return SkillResult.validationError("command is required");
            }

            int timeout = DEFAULT_TIMEOUT_MS;
            if (input.containsKey("timeout") && input.get("timeout") != null) {
                Object timeoutValue = input.get("timeout");
                if (!(timeoutValue instanceof Number number)) {
                    return SkillResult.validationError("timeout must be an integer");
                }
                long requestedTimeout = number.longValue();
                double numericTimeout = number.doubleValue();
                if (!Double.isFinite(numericTimeout) || numericTimeout != requestedTimeout) {
                    return SkillResult.validationError("timeout must be an integer");
                }
                if (requestedTimeout < MIN_TIMEOUT_MS || requestedTimeout > MAX_TIMEOUT_MS) {
                    return SkillResult.validationError(
                            "timeout must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS + " milliseconds");
                }
                timeout = (int) requestedTimeout;
            }

            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            List<String> sensitiveValues = removeSensitiveEnvironment(pb.environment());

            String workDir = context.getWorkingDirectory();
            if (workDir != null && !workDir.isBlank()) {
                pb.directory(new File(workDir));
            }

            process = pb.start();
            collector = new OutputCollector(process.getInputStream());
            collector.start();
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);

            if (!finished) {
                terminateProcessTree(process);
                collector.finish();
                String output = redact(collector.output(), sensitiveValues);
                return SkillResult.error(withOutput("Command timed out after " + timeout + "ms", output));
            }

            collector.finish();
            String output = redact(collector.output(), sensitiveValues);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return SkillResult.error(withOutput("Command failed with exit code " + exitCode, output));
            }

            return SkillResult.success(output);
        } catch (IOException e) {
            return SkillResult.error("Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminateProcessTree(process);
            }
            return SkillResult.error("Command execution interrupted");
        } catch (Exception e) {
            return SkillResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private static List<String> removeSensitiveEnvironment(Map<String, String> environment) {
        List<String> sensitiveValues = new ArrayList<>();
        environment.entrySet().removeIf(entry -> {
            if (!SENSITIVE_ENV_NAME.matcher(entry.getKey()).matches()) {
                return false;
            }
            if (entry.getValue() != null && entry.getValue().length() >= 6) {
                sensitiveValues.add(entry.getValue());
            }
            return true;
        });
        return sensitiveValues;
    }

    private static String redact(String output, List<String> sensitiveValues) {
        String redacted = output;
        for (String value : sensitiveValues) {
            redacted = redacted.replace(value, "[REDACTED]");
        }
        return redacted;
    }

    private static String withOutput(String message, String output) {
        return output == null || output.isBlank() ? message : message + "\n" + output;
    }

    private static void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        process.destroy();
        descendants.forEach(ProcessHandle::destroy);
        waitForTermination(process.toHandle(), descendants);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void waitForTermination(ProcessHandle root, List<ProcessHandle> descendants) {
        long deadline = System.nanoTime() + Duration.ofMillis(PROCESS_KILL_GRACE_MS).toNanos();
        while (System.nanoTime() < deadline
                && (root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final byte[] retained = new byte[MAX_OUTPUT_BYTES];
        private final CountDownLatch completed = new CountDownLatch(1);
        private int retainedLength;
        private boolean truncated;

        private OutputCollector(InputStream input) {
            this.input = input;
        }

        private void start() {
            Thread thread = new Thread(this, "skillforge-bash-output");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = MAX_OUTPUT_BYTES - retainedLength;
                    int copyLength = Math.min(read, Math.max(remaining, 0));
                    if (copyLength > 0) {
                        System.arraycopy(buffer, 0, retained, retainedLength, copyLength);
                        retainedLength += copyLength;
                    }
                    if (copyLength < read) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // A timeout may close the process stream while this thread is draining it.
            } finally {
                completed.countDown();
            }
        }

        private void finish() throws InterruptedException {
            if (!completed.await(OUTPUT_DRAIN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // The process may already have closed the stream.
                }
                completed.await(OUTPUT_DRAIN_GRACE_MS, TimeUnit.MILLISECONDS);
            }
        }

        private String output() {
            int validLength = validUtf8PrefixLength(retained, retainedLength);
            String output = new String(retained, 0, validLength, StandardCharsets.UTF_8);
            if (truncated) {
                return output + "\n... [output truncated, exceeded " + MAX_OUTPUT_BYTES + " bytes]";
            }
            return output;
        }
    }

    private static int validUtf8PrefixLength(byte[] bytes, int length) {
        for (int candidate = length; candidate >= Math.max(0, length - 3); candidate--) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, candidate));
                return candidate;
            } catch (CharacterCodingException ignored) {
                // At most three trailing bytes can form an incomplete UTF-8 code point.
            }
        }
        return 0;
    }
}
