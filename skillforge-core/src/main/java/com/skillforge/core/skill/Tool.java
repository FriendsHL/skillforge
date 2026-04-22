package com.skillforge.core.skill;

import com.skillforge.core.model.ToolSchema;

import java.util.Map;

/**
 * Java function-calling tool abstraction.
 * <p>
 * A Tool must provide JSON schema metadata and an execute method so the loop
 * engine can expose and invoke it consistently.
 */
public interface Tool {

    /**
     * Tool name, globally unique.
     */
    String getName();

    /**
     * Tool description used by the model.
     */
    String getDescription();

    /**
     * JSON schema metadata exposed to model providers.
     */
    ToolSchema getToolSchema();

    /**
     * Executes the tool logic.
     */
    SkillResult execute(Map<String, Object> input, SkillContext context);

    /**
     * Read-only tools never mutate state.
     */
    default boolean isReadOnly() {
        return false;
    }
}

