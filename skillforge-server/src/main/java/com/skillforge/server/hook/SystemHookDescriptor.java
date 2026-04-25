package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;

public record SystemHookDescriptor(
        String sourceId,
        HookEvent event,
        HookEntry entry,
        String description
) {}
