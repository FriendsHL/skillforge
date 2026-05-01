package com.skillforge.server.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
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
        return "把第三方 marketplace（ClawHub / GitHub / SkillHub）已经装好的 skill 注册到 SkillForge。"
                + "适用场景：你刚刚用 `npx clawhub install <slug>` / `gh repo clone <repo>` / "
                + "`npx @skill-hub/cli install <slug>` 把 skill 装到外部目录，"
                + "调本工具把它复制到 SkillForge 的 runtime root + 写入 t_skill 表 + 注册到 SkillRegistry，"
                + "之后 dashboard 能看到、后续 agent turn 能调用。"
                + "不调用本工具，第三方 CLI 装的 skill SkillForge 不可见。";
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

        try {
            ImportResult result = importService.importSkill(sourcePath, sourceEnum, ownerId);
            return SkillResult.success(objectMapper.writeValueAsString(result));
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
