package com.skillforge.core.context;

/**
 * Logical origin of a system-prompt fragment. This is observation metadata only and
 * does not participate in prompt rendering.
 */
public enum PromptSourceType {
    GLOBAL,
    AGENT,
    SOUL,
    TOOL_GUIDANCE,
    BEHAVIOR_RULES,
    RUNTIME_CONTEXT
}
