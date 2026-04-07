package com.skillforge.core.engine;

import com.skillforge.core.model.ToolSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ask_user 是一个特殊 Tool:
 * <ul>
 *   <li>不注册到 SkillRegistry</li>
 *   <li>只在 session.executionMode == "ask" 时由 AgentLoopEngine 注入到 LLM 的 tools 列表</li>
 *   <li>LLM 调用它时,engine 不走普通 ToolExecutor,而是走阻塞等 UI 答复的特殊分支</li>
 * </ul>
 * Schema 为多选题格式,借鉴 Claude Code AskUserQuestionTool,强制 LLM 给出 2-4 个选项,
 * 降低用户回答成本、让 LLM 的问题更结构化。
 */
public final class AskUserTool {

    public static final String NAME = "ask_user";

    public static final String DESCRIPTION =
            "Ask the user for a decision when the current approach is not working or you need to switch paths. "
                    + "Use this tool ONLY when: "
                    + "(1) a skill keeps failing and you cannot determine the root cause, "
                    + "(2) you need to adopt an alternative approach the user did not mention, "
                    + "(3) the original request is ambiguous and you need clarification. "
                    + "Provide 2-4 concrete options as a multiple-choice question. "
                    + "Do not use this tool for rhetorical questions or progress updates.";

    private AskUserTool() {
    }

    public static ToolSchema toolSchema() {
        Map<String, Object> optionItem = new LinkedHashMap<>();
        optionItem.put("type", "object");
        Map<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put("label", Map.of(
                "type", "string",
                "description", "Short label (1-5 words) for this option"));
        optionProps.put("description", Map.of(
                "type", "string",
                "description", "Optional longer explanation of what picking this option means"));
        optionItem.put("properties", optionProps);
        optionItem.put("required", List.of("label"));

        Map<String, Object> optionsSchema = new LinkedHashMap<>();
        optionsSchema.put("type", "array");
        optionsSchema.put("minItems", 2);
        optionsSchema.put("maxItems", 4);
        optionsSchema.put("items", optionItem);
        optionsSchema.put("description", "2-4 distinct options for the user to pick from");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("question", Map.of(
                "type", "string",
                "description", "The complete question, clear and specific, ending with a question mark"));
        properties.put("context", Map.of(
                "type", "string",
                "description", "Optional: why you are asking — e.g., what just failed or what is ambiguous"));
        properties.put("options", optionsSchema);
        properties.put("allowOther", Map.of(
                "type", "boolean",
                "description", "Whether to allow the user to reply with free-form text instead of picking an option. Default true.",
                "default", true));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("question", "options"));

        return new ToolSchema(NAME, DESCRIPTION, inputSchema);
    }
}
