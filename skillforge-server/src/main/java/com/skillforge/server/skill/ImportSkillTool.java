package com.skillforge.server.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.security.skill.SkillSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
                + "应先走需要用户确认的受控安装流程，得到本地目录后再调用本工具。";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourcePath", Map.of(
                "type", "string",
                "description", "已装好的 skill 目录绝对路径（包含 SKILL.md），"
                        + "例如 ~/.openclaw/workspace/skills/tool-call-retry"));
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
        Object sourcePathRaw = input.get("sourcePath");
        Object sourceRaw = input.get("source");
        if (!(sourcePathRaw instanceof String sourcePathStr) || sourcePathStr.isBlank()) {
            return SkillResult.validationError("sourcePath is required (non-blank string)");
        }
        if (!(sourceRaw instanceof String sourceStr) || sourceStr.isBlank()) {
            return SkillResult.validationError(
                    "source is required (clawhub | github | skillhub | filesystem)");
        }

        Long ownerId = context != null ? context.getUserId() : null;
        if (ownerId == null) {
            return SkillResult.error("ImportSkill requires an authenticated user; userId is missing");
        }

        SkillSource sourceEnum;
        try {
            sourceEnum = SkillSource.valueOf(sourceStr.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return SkillResult.validationError(
                    "source must be one of clawhub | github | skillhub | filesystem; got '" + sourceStr + "'");
        }

        Path sourcePath;
        try {
            sourcePath = Path.of(expandHome(sourcePathStr));
        } catch (Exception e) {
            return SkillResult.validationError("sourcePath is not a valid path: " + sourcePathStr);
        }

        boolean allowMediumRisk = Boolean.TRUE.equals(input.get("allowMediumRisk"));

        try {
            ImportResult result = importService.importSkill(sourcePath, sourceEnum, ownerId, allowMediumRisk);
            return SkillResult.success(objectMapper.writeValueAsString(result));
        } catch (SkillSecurityException e) {
            log.warn("ImportSkill blocked by security scan sourcePath={} source={}: {}",
                    sourcePathStr, sourceStr, e.getMessage());
            return SkillResult.error(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("ImportSkill rejected request sourcePath={} source={}: {}",
                    sourcePathStr, sourceStr, e.getMessage());
            return SkillResult.error(e.getMessage());
        } catch (JsonProcessingException e) {
            log.error("ImportSkill succeeded but JSON serialisation failed", e);
            return SkillResult.error("ImportSkill succeeded but result serialisation failed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("ImportSkill failed for sourcePath={} source={}", sourcePathStr, sourceStr, e);
            return SkillResult.error("ImportSkill failed: " + e.getMessage());
        }
    }

    /** Expand a leading {@code ~} to the JVM user home; everything else passes through. */
    private static String expandHome(String raw) {
        if (raw.equals("~")) return System.getProperty("user.home");
        if (raw.startsWith("~/")) return System.getProperty("user.home") + raw.substring(1);
        return raw;
    }
}
