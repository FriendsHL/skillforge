package com.skillforge.server.skill;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.service.MemoryService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Built-in Skill that allows agents to save, search, and delete user memories.
 */
public class MemorySkill implements Skill {

    private final MemoryService memoryService;

    public MemorySkill(MemoryService memoryService) {
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
            + "Actions: save, search, delete";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "The action to perform: save, search, or delete",
                "enum", List.of("save", "search", "delete")
        ));
        properties.put("userId", Map.of(
                "type", "integer",
                "description", "The user ID"
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
        properties.put("keyword", Map.of(
                "type", "string",
                "description", "Search keyword (required for search)"
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
                case "save" -> handleSave(input);
                case "search" -> handleSearch(input);
                case "delete" -> handleDelete(input);
                default -> SkillResult.error("Unknown action: " + action + ". Supported: save, search, delete");
            };
        } catch (Exception e) {
            return SkillResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private SkillResult handleSave(Map<String, Object> input) {
        Long userId = toLong(input.get("userId"));
        String type = (String) input.get("type");
        String title = (String) input.get("title");
        String content = (String) input.get("content");
        String tags = (String) input.get("tags");

        if (userId == null) {
            return SkillResult.error("userId is required for save");
        }
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

    private SkillResult handleSearch(Map<String, Object> input) {
        Long userId = toLong(input.get("userId"));
        String keyword = (String) input.get("keyword");

        if (userId == null) {
            return SkillResult.error("userId is required for search");
        }
        if (keyword == null || keyword.isBlank()) {
            return SkillResult.error("keyword is required for search");
        }

        List<MemoryEntity> results = memoryService.searchWithRanking(userId, keyword);
        if (results.isEmpty()) {
            return SkillResult.success("No memories found matching \"" + keyword + "\"");
        }

        String output = results.stream()
                .map(m -> "- [" + m.getType() + "] " + m.getTitle() + " (id=" + m.getId() + "): " + m.getContent())
                .collect(Collectors.joining("\n"));
        return SkillResult.success("Found " + results.size() + " memories:\n" + output);
    }

    private SkillResult handleDelete(Map<String, Object> input) {
        Long memoryId = toLong(input.get("memoryId"));
        if (memoryId == null) {
            return SkillResult.error("memoryId is required for delete");
        }

        memoryService.deleteMemory(memoryId);
        return SkillResult.success("Memory with id=" + memoryId + " deleted");
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
