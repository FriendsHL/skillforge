package com.skillforge.server.skill;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieve the full content of a specific memory by its ID.
 * Designed for progressive disclosure: memory_search returns snippets,
 * this tool fetches full text on demand.
 */
public class MemoryDetailSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(MemoryDetailSkill.class);

    private final MemoryService memoryService;

    public MemoryDetailSkill(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "memory_detail";
    }

    @Override
    public String getDescription() {
        return "Retrieve the full content of a specific memory by its ID. "
                + "First call memory_search to get memoryId, then call this tool for full text.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("memoryId", Map.of(
                "type", "integer",
                "description", "The memory ID to retrieve"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("memoryId"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            Long memoryId = SkillInputUtils.toLong(input.get("memoryId"));
            if (memoryId == null) {
                return SkillResult.error("memoryId is required");
            }

            return memoryService.findById(memoryId)
                    .map(m -> SkillResult.success(
                            "[" + m.getType() + "] " + m.getTitle() + "\n\n" + m.getContent()))
                    .orElse(SkillResult.error("Memory not found: id=" + memoryId));
        } catch (Exception e) {
            log.warn("Memory detail retrieval failed: {}", e.getMessage(), e);
            return SkillResult.error("Failed to retrieve memory");
        }
    }
}
