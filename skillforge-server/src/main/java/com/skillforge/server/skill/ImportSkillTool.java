package com.skillforge.server.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.security.skill.SkillScanResult;
import com.skillforge.server.security.skill.SkillScanSeverity;
import com.skillforge.server.security.skill.SkillSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SKILL-IMPORT — Java tool exposed to the agent loop. Lets the agent register a
 * skill it has already installed externally (ClawHub / GitHub / SkillHub /
 * filesystem) into SkillForge so the skill becomes visible to the dashboard
 * catalog and dispatchable in subsequent agent turns.
 *
 * <p>Tool surface (description + schema) is intentionally Chinese to align
 * with the marketplace system-skill prompts that direct the agent here.
 */
public class ImportSkillTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ImportSkillTool.class);

    private final SkillImportService importService;
    private final ObjectMapper objectMapper;

    public ImportSkillTool(SkillImportService importService, ObjectMapper objectMapper) {
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ImportSkill";
    }

    @Override
    public String getDescription() {
        return "将一个已经安装且包含 SKILL.md 的本地 skill 目录导入 SkillForge。"
                + "来源可以是 ClawHub、GitHub、SkillHub 或 filesystem；"
                + "导入时会校验路径、执行安全扫描、复制到 SkillForge runtime root、持久化并注册。"
                + "本工具不负责搜索、下载或安装 skill；如果只有 marketplace 名称或 Git 地址，"
                + "应先走需要用户确认的受控安装流程，得到本地目录后再调用本工具。"
                + "不要使用 Bash、cp 或 mv 绕过受控安装流程。";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourcePath", Map.of(
                "type", "string",
                "description", "已由受控流程安装好的 skill 目录绝对路径；该目录必须直接包含 SKILL.md。"
                        + "不要传仓库根目录、marketplace 名称或 Git URL，也不要自行复制目录绕过安装。"));
        properties.put("source", Map.of(
                "type", "string",
                "description", "Skill 来源：clawhub | github | skillhub | filesystem"));
        properties.put("allowMediumRisk", Map.of(
                "type", "boolean",
                "description", "仅当用户明确理解并接受中风险扫描结果时设为 true；HIGH 风险不能用此参数绕过。默认 false"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("sourcePath", "source"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (input == null) {
            return validationFailure(
                    "INPUT_REQUIRED", "input", true,
                    "提供 sourcePath 和 source 后重试。");
        }
        Object sourcePathRaw = input.get("sourcePath");
        Object sourceRaw = input.get("source");
        if (!(sourcePathRaw instanceof String sourcePathStr) || sourcePathStr.isBlank()) {
            return validationFailure(
                    "SOURCE_PATH_REQUIRED", "sourcePath", true,
                    "传入受控流程已安装、且直接包含 SKILL.md 的 skill 目录绝对路径。");
        }
        if (!(sourceRaw instanceof String sourceStr) || sourceStr.isBlank()) {
            return validationFailure(
                    "SOURCE_REQUIRED", "source", true,
                    "source 必须是 clawhub、github、skillhub 或 filesystem 之一。");
        }

        Long ownerId = context != null ? context.getUserId() : null;
        if (ownerId == null) {
            return executionFailure(
                    "AUTHENTICATED_USER_REQUIRED", "context.userId", false,
                    "请在已认证用户的会话中调用 ImportSkill。");
        }

        SkillSource sourceEnum;
        try {
            sourceEnum = SkillSource.valueOf(sourceStr.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return validationFailure(
                    "SOURCE_UNSUPPORTED", "source", true,
                    "source 必须是 clawhub | github | skillhub | filesystem 之一。");
        }

        Path sourcePath;
        try {
            sourcePath = Path.of(expandHome(sourcePathStr));
        } catch (Exception e) {
            return validationFailure(
                    "SOURCE_PATH_INVALID", "sourcePath", true,
                    "传入合法的本地绝对路径；目录必须直接包含 SKILL.md。");
        }

        boolean allowMediumRisk = Boolean.TRUE.equals(input.get("allowMediumRisk"));

        try {
            ImportResult result = importService.importSkill(sourcePath, sourceEnum, ownerId, allowMediumRisk);
            return SkillResult.success(objectMapper.writeValueAsString(result));
        } catch (SkillSecurityException e) {
            SkillScanResult scanResult = e.getScanResult();
            log.warn("ImportSkill blocked by security scan sourcePath={} source={} severity={} findings={}",
                    sourcePathStr,
                    sourceStr,
                    scanResult.highestSeverity(),
                    scanResult.findings().size());
            return securityFailure(scanResult);
        } catch (IllegalArgumentException e) {
            log.warn("ImportSkill rejected request sourcePath={} source={}: {}",
                    sourcePathStr, sourceStr, e.getMessage());
            return mapValidationFailure(e.getMessage());
        } catch (JsonProcessingException e) {
            log.error("ImportSkill succeeded but JSON serialisation failed", e);
            return executionFailure(
                    "SKILL_RESULT_SERIALIZATION_FAILED", "result", false,
                    "Skill 已完成导入，但结果无法序列化；不要重复导入，请检查 Skill 列表确认结果。");
        } catch (RuntimeException e) {
            log.error("ImportSkill failed for sourcePath={} source={}", sourcePathStr, sourceStr, e);
            return executionFailure(
                    "SKILL_IMPORT_FAILED", "sourcePath", true,
                    "导入执行失败。请检查服务端日志和 Skill 安装状态后再重试。");
        }
    }

    private SkillResult mapValidationFailure(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("is empty") && normalized.contains("importskill is disabled")) {
            return executionFailure(
                    "SKILL_IMPORT_DISABLED", "sourcePath", false,
                    "当前服务未配置 Skill 导入目录，请联系管理员配置受控安装根目录。");
        }
        if (normalized.contains("not in allowed roots")) {
            return executionFailure(
                    "SKILL_NOT_INSTALLED", "sourcePath", false,
                    "请先通过需要用户确认的受控安装流程安装 Skill；安装完成后，仅传入直接包含 "
                            + "SKILL.md 的目录。不要使用 Bash、cp 或 mv 绕过来源目录限制。");
        }
        if (normalized.contains("does not exist")) {
            return validationFailure(
                    "SKILL_SOURCE_NOT_FOUND", "sourcePath", true,
                    "确认 Skill 已由受控流程安装，并传入仍然存在且直接包含 SKILL.md 的目录。");
        }
        if (normalized.contains("skill.md not found")) {
            return validationFailure(
                    "SKILL_MANIFEST_MISSING", "sourcePath", true,
                    "传入 Skill 根目录本身；该目录必须直接包含 SKILL.md，不能传仓库根目录或父目录。");
        }
        if (normalized.contains("parse")
                || normalized.contains("usable name")
                || normalized.contains("skill name")) {
            return executionFailure(
                    "SKILL_PACKAGE_INVALID", "sourcePath", false,
                    "检查 SKILL.md 的 frontmatter、名称和目录结构，修复后再通过受控流程安装。");
        }
        return executionFailure(
                "SKILL_IMPORT_INVALID", "sourcePath", false,
                "输入目录不符合 Skill 导入契约；确认它已受控安装并直接包含有效的 SKILL.md。");
    }

    private SkillResult securityFailure(SkillScanResult scanResult) {
        SkillScanSeverity severity = scanResult.highestSeverity();
        boolean approvalRequired = severity == SkillScanSeverity.MEDIUM;
        String errorCode = approvalRequired
                ? "SKILL_SECURITY_APPROVAL_REQUIRED"
                : "SKILL_SECURITY_BLOCKED";
        String suggestedAction = approvalRequired
                ? "先向用户说明存在中风险扫描结果并取得用户明确确认；确认后才可使用 "
                        + "allowMediumRisk=true 重新调用。"
                : "安全扫描已阻止导入。不要绕过扫描；请审查并修复 Skill 安装包后重新安装。";
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("highestSeverity", severity != null ? severity.name() : "UNKNOWN");
        diagnostic.put("findingCount", scanResult.findings().size());
        diagnostic.put("ruleIds", scanResult.findings().stream()
                .map(finding -> finding.ruleId())
                .filter(ruleId -> ruleId != null && !ruleId.isBlank())
                .distinct()
                .toList());
        return SkillResult.error(errorPayload(
                errorCode,
                "execution",
                "sourcePath",
                false,
                suggestedAction,
                diagnostic));
    }

    private SkillResult validationFailure(
            String errorCode,
            String failedField,
            boolean retryable,
            String suggestedAction) {
        return SkillResult.validationError(errorPayload(
                errorCode, "validation", failedField, retryable, suggestedAction));
    }

    private SkillResult executionFailure(
            String errorCode,
            String failedField,
            boolean retryable,
            String suggestedAction) {
        return SkillResult.error(errorPayload(
                errorCode, "execution", failedField, retryable, suggestedAction));
    }

    private String errorPayload(
            String errorCode,
            String errorType,
            String failedField,
            boolean retryable,
            String suggestedAction) {
        return errorPayload(
                errorCode, errorType, failedField, retryable, suggestedAction, Map.of());
    }

    private String errorPayload(
            String errorCode,
            String errorType,
            String failedField,
            boolean retryable,
            String suggestedAction,
            Map<String, Object> diagnostic) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("errorCode", errorCode);
        payload.put("errorType", errorType);
        payload.put("retryable", retryable);
        payload.put("failedField", failedField);
        payload.put("suggestedAction", suggestedAction);
        payload.putAll(diagnostic);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ImportSkill error payload code={}", errorCode, e);
            return "{\"success\":false,\"errorCode\":\"IMPORT_SKILL_ERROR_SERIALIZATION_FAILED\","
                    + "\"errorType\":\"execution\",\"retryable\":false}";
        }
    }

    /** Expand a leading {@code ~} to the JVM user home; everything else passes through. */
    private static String expandHome(String raw) {
        if (raw.equals("~")) return System.getProperty("user.home");
        if (raw.startsWith("~/")) return System.getProperty("user.home") + raw.substring(1);
        return raw;
    }
}
