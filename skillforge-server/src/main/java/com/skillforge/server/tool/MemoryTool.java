package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in Tool that allows agents to save and delete user memories.
 * Use memory_search to find memories.
 */
public class MemoryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);

    private final MemoryService memoryService;

    public MemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "Memory";
    }

    @Override
    public String getDescription() {
        return "Manage user memories for long-term learning and context persistence.\n\n"
            + "Memory types:\n"
            + "- preference: User preferences and working style (e.g., 'prefers concise output')\n"
            + "- knowledge: Stable technical facts that won't change (e.g., 'project uses Spring Boot 3.2')\n"
            + "- feedback: Corrections and confirmed approaches (e.g., 'don't mock DB in tests')\n"
            + "- project: Time-sensitive project context that may expire (e.g., 'sprint ends Friday', 'currently refactoring auth module')\n"
            + "- reference: External system pointers (e.g., 'bugs tracked in Linear INGEST')\n\n"
            + "Do NOT save: code paths, git history, one-time debug steps, or info derivable from code.\n"
            + "Actions: save, delete. Use memory_search to find memories. "
            + "User context (userId) is provided automatically by the system — no need to pass it.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "The action to perform: save or delete",
                "enum", List.of("save", "delete")
        ));
        properties.put("type", Map.of(
                "type", "string",
                "description", "Memory type (required for save)",
                "enum", List.of("preference", "knowledge", "feedback", "project", "reference")
        ));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Memory title (required for save)"
        ));
        properties.put("content", Map.of(
                "type", "string",
                "description", "Memory content (required for save)"
        ));
        properties.put("tags", Map.of(
                "type", "string",
                "description", "Comma-separated tags (optional for save)"
        ));
        properties.put("memoryId", Map.of(
                "type", "integer",
                "description", "Memory ID (required for delete)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String action = (String) input.get("action");
            if (action == null || action.isBlank()) {
                return SkillResult.error("action is required");
            }

            return switch (action) {
                case "save" -> handleSave(input, context);
                case "delete" -> handleDelete(input, context);
                default -> SkillResult.error("Unknown action: " + action + ". Supported: save, delete. Use memory_search to find memories.");
            };
        } catch (Exception e) {
            log.warn("Memory tool unexpected error: {}", e.getMessage(), e);
            return SkillResult.error("Memory tool encountered an unexpected error");
        }
    }

    private SkillResult handleSave(Map<String, Object> input, SkillContext context) {
        Long userId = context != null ? context.getUserId() : null;
        if (userId == null) {
            return SkillResult.error("User context is missing — Memory tool requires session userId");
        }

        String type = (String) input.get("type");
        String title = (String) input.get("title");
        String content = (String) input.get("content");
        String tags = (String) input.get("tags");

        if (type == null || type.isBlank()) {
            return SkillResult.error("type is required for save");
        }
        if (title == null || title.isBlank()) {
            return SkillResult.error("title is required for save");
        }
        if (content == null || content.isBlank()) {
            return SkillResult.error("content is required for save");
        }

        MemoryEntity memory = new MemoryEntity();
        memory.setUserId(userId);
        memory.setType(type);
        memory.setTitle(title);
        memory.setContent(content);
        memory.setTags(tags);

        MemoryEntity saved = memoryService.createMemory(memory);
        return SkillResult.success("Memory saved with id=" + saved.getId() + ", title=\"" + saved.getTitle() + "\"");
    }

    private SkillResult handleDelete(Map<String, Object> input, SkillContext context) {
        Long userId = context != null ? context.getUserId() : null;
        if (userId == null) {
            return SkillResult.error("User context is missing — Memory tool requires session userId");
        }

        Long memoryId = SkillInputUtils.toLong(input.get("memoryId"));
        if (memoryId == null) {
            return SkillResult.error("memoryId is required for delete");
        }

        // F2: collapse not-found + cross-user into one branch to defeat IDOR enumeration —
        // LLM can't probe whether a memoryId belongs to another user vs. is genuinely absent.
        MemoryEntity memory = memoryService.findById(memoryId).orElse(null);
        if (memory == null || !Objects.equals(memory.getUserId(), userId)) {
            return SkillResult.error("Memory not found: id=" + memoryId);
        }

        memoryService.deleteMemory(memoryId);
        return SkillResult.success("Memory with id=" + memoryId + " deleted");
    }

}
