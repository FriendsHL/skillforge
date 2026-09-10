package com.skillforge.core.skill;

import java.util.Set;

/**
 * A platform-owned Tool whose model visibility is resolved from trusted runtime context instead
 * of an Agent's configurable Tool allowlist.
 */
public interface SystemResidentTool extends Tool {

    /** Closed visibility state; UNAVAILABLE is master-on but not safely dispatchable. */
    enum Status {
        DISABLED,
        UNAVAILABLE,
        AVAILABLE
    }

    Status getSystemToolStatus(SkillContext context);

    /** Tools in one group are surfaced atomically; mixed runtime states collapse to unavailable. */
    default String getSystemToolGroup() {
        return getName();
    }

    /** Compatibility tools hidden whenever this system capability is master-on. */
    default Set<String> getSupersededToolNames() {
        return Set.of();
    }
}
