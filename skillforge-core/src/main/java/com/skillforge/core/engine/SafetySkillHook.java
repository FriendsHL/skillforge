package com.skillforge.core.engine;

import com.skillforge.core.engine.confirm.InstallTargetParser;
import com.skillforge.core.engine.confirm.RootSessionLookup;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
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
 *
 * <p>r2 重构:install-pattern 命令不再由本 hook 阻塞 —— engine 在 dispatch 循环里
 * 用专门的 install confirmation 分支处理(主线程阻塞等用户决策,不入 supplyAsync)。
 * 此 hook 现在只做两件事:
 * <ol>
 *   <li>install 命令 cache 命中 → 放行</li>
 *   <li>install 命令 cache 未命中 → fail-closed(防御 engine gate 缺失的情况)</li>
 * </ol>
 */
public class SafetySkillHook implements SkillHook {

    private static final Logger log = LoggerFactory.getLogger(SafetySkillHook.class);

    /** 需要用户确认才能执行的高风险命令模式。委托给 {@link DangerousCommandChecker}。 */
    private static final List<Pattern> CONFIRMATION_REQUIRED_PATTERNS =
            DangerousCommandChecker.CONFIRMATION_REQUIRED_PATTERNS;

    /** 危险模式共享:保证 Bash Tool 与 ScriptHandlerRunner 扫描规则一致。 */
    private static final List<Pattern> DANGEROUS_PATTERNS = DangerousCommandChecker.DANGEROUS_PATTERNS;

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

    /**
     * r2:install cache + root resolver 通过构造器注入。null 时 install 命令一律 fail-closed
     * (保持历史行为 "拦截 → null → engine 返 error tool_result"),与旧版 SafetySkillHook 等价。
     */
    private final SessionConfirmCache sessionConfirmCache;
    private final RootSessionLookup rootSessionLookup;

    /** 无参构造器:用于没有 confirm 基础设施的测试 / 独立场景(等价 legacy 行为)。 */
    public SafetySkillHook() {
        this(null, null);
    }

    public SafetySkillHook(SessionConfirmCache sessionConfirmCache, RootSessionLookup rootSessionLookup) {
        this.sessionConfirmCache = sessionConfirmCache;
        this.rootSessionLookup = rootSessionLookup;
    }

    @Override
    public Map<String, Object> beforeSkillExecute(String skillName, Map<String, Object> input, SkillContext context) {
        if (input == null) {
            return input;
        }

        switch (skillName) {
            case "Bash":
                return checkBashSafety(input, context);
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
        log.info("[SafetyHook] Tool executed: skillName={}, status={}", skillName, status);
    }

    private Map<String, Object> checkBashSafety(Map<String, Object> input, SkillContext context) {
        Object commandObj = input.get("command");
        if (commandObj == null) {
            return input;
        }
        String command = commandObj.toString();

        for (Pattern pattern : CONFIRMATION_REQUIRED_PATTERNS) {
            if (pattern.matcher(command).find()) {
                // engine 主线程 install confirmation 分支已经决策过,cache 命中即放行
                InstallTargetParser.Parsed parsed = InstallTargetParser.parse(command);
                String sid = context != null ? context.getSessionId() : null;
                String rootSid = resolveRoot(sid);
                if (sessionConfirmCache != null && rootSid != null
                        && sessionConfirmCache.isApproved(rootSid,
                                parsed.toolName(), parsed.installTarget())) {
                    return input;
                }
                // 防御:engine 未经 install confirmation 分支直接调到了这里 — 不应发生,fail-closed
                log.error("[SafetyHook] install pattern reached SafetyHook without engine gate; "
                        + "rejecting fail-closed: rootSid={} tool={} target={} cmd={}",
                        rootSid, parsed.toolName(), parsed.installTarget(),
                        command.length() > 120 ? command.substring(0, 120) + "…" : command);
                return null;
            }
        }

        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                log.warn("[SafetyHook] Blocked dangerous command: {}", command);
                return null;
            }
        }
        return input;
    }

    private String resolveRoot(String sid) {
        if (sid == null) return null;
        if (rootSessionLookup == null) return sid;
        try {
            String r = rootSessionLookup.resolveRoot(sid);
            return r != null ? r : sid;
        } catch (RuntimeException e) {
            log.warn("[SafetyHook] rootSessionLookup failed for sid={}: {}", sid, e.toString());
            return sid;
        }
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
