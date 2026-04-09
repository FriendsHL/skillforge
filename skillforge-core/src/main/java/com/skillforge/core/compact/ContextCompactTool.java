package com.skillforge.core.compact;

import com.skillforge.core.model.ToolSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * compact_context 工具 schema 常量。
 *
 * <p>这是一个 engine-intercepted tool: 不注册到 SkillRegistry, engine 在调用 LLM 时主动注入,
 * LLM 调用时 engine 自己处理(调 {@link ContextCompactorCallback})。
 */
public final class ContextCompactTool {

    public static final String NAME = "compact_context";

    public static final String DESCRIPTION =
            "Compress the conversation context to free up tokens. " +
            "Use level='light' for a cheap rule-based cleanup (dedup, truncate large tool outputs, " +
            "fold failed retries). Use level='full' for a full LLM-based summarization of older history. " +
            "Call this when you notice the conversation is getting long or repetitive, " +
            "or when tool outputs are taking up too much space. " +
            "Always provide a short reason explaining why you are compacting.";

    private ContextCompactTool() {
    }

    public static ToolSchema toolSchema() {
        Map<String, Object> levelProp = new LinkedHashMap<>();
        levelProp.put("type", "string");
        levelProp.put("enum", List.of("light", "full"));
        levelProp.put("description",
                "'light' = cheap rule-based; 'full' = LLM summarization of older messages");

        Map<String, Object> reasonProp = new LinkedHashMap<>();
        reasonProp.put("type", "string");
        reasonProp.put("description", "Short human-readable reason for compacting now");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("level", levelProp);
        properties.put("reason", reasonProp);

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("level", "reason"));

        return new ToolSchema(NAME, DESCRIPTION, inputSchema);
    }
}
