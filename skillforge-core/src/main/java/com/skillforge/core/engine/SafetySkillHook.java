package com.skillforge.core.engine;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Safety interceptor hook that blocks dangerous commands and file operations.
 */
public class SafetySkillHook implements SkillHook {

    private static final Logger log = LoggerFactory.getLogger(SafetySkillHook.class);

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // Destructive delete commands
            Pattern.compile("rm\\s+-rf\\s+/(?:\\s|$)"),
            Pattern.compile("rm\\s+-rf\\s+~(?:\\s|$|/)"),
            Pattern.compile("rm\\s+-rf\\s+\\.(?:\\s|$)"),
            // Privilege escalation
            Pattern.compile("\\bsudo\\b"),
            // Disk formatting
            Pattern.compile("\\bmkfs\\b"),
            // Disk operations
            Pattern.compile("\\bdd\\s+if="),
            // Fork bomb
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
            // Write to disk device
            Pattern.compile(">\\s*/dev/sda"),
            // Global permission change
            Pattern.compile("chmod\\s+-R\\s+777\\s+/(?:\\s|$)"),
            // System shutdown/reboot
            Pattern.compile("\\bshutdown\\b"),
            Pattern.compile("\\breboot\\b"),
            Pattern.compile("\\bhalt\\b"),
            Pattern.compile("\\bpoweroff\\b"),
            // Remote code execution
            Pattern.compile("curl\\s+.*\\|\\s*sh"),
            Pattern.compile("wget\\s+.*\\|\\s*sh"),
            Pattern.compile("curl\\s+.*\\|\\s*bash")
    );

    private static final List<String> PROTECTED_SYSTEM_DIRS = List.of(
            "/etc/", "/usr/", "/bin/", "/sbin/", "/boot/", "/sys/", "/proc/"
    );

    private static final String HOME_DIR = System.getProperty("user.home");

    private static final List<String> PROTECTED_USER_PATHS = List.of(
            HOME_DIR + "/.ssh/",
            HOME_DIR + "/.bashrc",
            HOME_DIR + "/.bash_profile",
            HOME_DIR + "/.zshrc"
    );

    private static final List<String> SENSITIVE_READ_PATHS = List.of(
            HOME_DIR + "/.ssh/id_rsa",
            HOME_DIR + "/.ssh/id_ed25519",
            HOME_DIR + "/.ssh/id_ecdsa",
            HOME_DIR + "/.ssh/id_dsa"
    );

    @Override
    public Map<String, Object> beforeSkillExecute(String skillName, Map<String, Object> input, SkillContext context) {
        if (input == null) {
            return input;
        }

        switch (skillName) {
            case "Bash":
                return checkBashSafety(input);
            case "FileWrite":
            case "FileEdit":
                return checkWritePathSafety(skillName, input);
            case "FileRead":
                return checkReadPathSafety(input);
            default:
                return input;
        }
    }

    @Override
    public void afterSkillExecute(String skillName, Map<String, Object> input, SkillResult result, SkillContext context) {
        String status = result != null && result.isSuccess() ? "success" : "fail";
        log.info("[SafetyHook] Skill executed: skillName={}, status={}", skillName, status);
    }

    private Map<String, Object> checkBashSafety(Map<String, Object> input) {
        Object commandObj = input.get("command");
        if (commandObj == null) {
            return input;
        }
        String command = commandObj.toString();

        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                log.warn("[SafetyHook] Blocked dangerous command: {}", command);
                return null;
            }
        }
        return input;
    }

    private Map<String, Object> checkWritePathSafety(String skillName, Map<String, Object> input) {
        Object pathObj = input.get("file_path");
        if (pathObj == null) {
            return input;
        }
        String filePath = pathObj.toString();
        String normalizedPath = normalizePath(filePath);

        for (String dir : PROTECTED_SYSTEM_DIRS) {
            if (normalizedPath.startsWith(dir)) {
                log.warn("[SafetyHook] Blocked {} to system directory: {}", skillName, filePath);
                return null;
            }
        }

        for (String protectedPath : PROTECTED_USER_PATHS) {
            if (normalizedPath.startsWith(protectedPath) || normalizedPath.equals(protectedPath)) {
                log.warn("[SafetyHook] Blocked {} to protected user file: {}", skillName, filePath);
                return null;
            }
        }

        return input;
    }

    private Map<String, Object> checkReadPathSafety(Map<String, Object> input) {
        Object pathObj = input.get("file_path");
        if (pathObj == null) {
            return input;
        }
        String filePath = pathObj.toString();
        String normalizedPath = normalizePath(filePath);

        for (String sensitive : SENSITIVE_READ_PATHS) {
            if (normalizedPath.equals(sensitive)) {
                log.warn("[SafetyHook] Blocked read of sensitive file: {}", filePath);
                return null;
            }
        }

        return input;
    }

    private String normalizePath(String filePath) {
        String expanded = filePath;
        if (expanded.startsWith("~/")) {
            expanded = HOME_DIR + expanded.substring(1);
        }
        try {
            Path path = Paths.get(expanded).normalize().toAbsolutePath();
            return path.toString();
        } catch (Exception e) {
            return expanded;
        }
    }
}
