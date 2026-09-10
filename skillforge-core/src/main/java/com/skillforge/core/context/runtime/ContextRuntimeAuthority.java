package com.skillforge.core.context.runtime;

import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.skill.view.SessionSkillView;

/** Current, non-persisted authorization surface used to validate runtime snapshot refs. */
public record ContextRuntimeAuthority(
        ToolCatalog toolCatalog,
        SessionSkillView skillView) {
}
