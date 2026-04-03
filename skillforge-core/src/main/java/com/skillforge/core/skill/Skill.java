package com.skillforge.core.skill;

import com.skillforge.core.model.ToolSchema;

import java.util.Map;

/**
 * 内置工具型 Skill 的核心接口。
 * 每个 Skill 对应 LLM 可调用的一个工具。
 */
public interface Skill {

    /**
     * Skill 名称，需全局唯一。
     */
    String getName();

    /**
     * Skill 描述，用于 LLM 理解该工具的用途。
     */
    String getDescription();

    /**
     * 返回工具的 JSON Schema 定义，传给 LLM API。
     */
    ToolSchema getToolSchema();

    /**
     * 执行 Skill 逻辑。
     *
     * @param input   LLM 传入的参数
     * @param context 执行上下文
     * @return 执行结果
     */
    SkillResult execute(Map<String, Object> input, SkillContext context);

    /**
     * 是否为只读操作，默认 false。
     */
    default boolean isReadOnly() {
        return false;
    }
}
