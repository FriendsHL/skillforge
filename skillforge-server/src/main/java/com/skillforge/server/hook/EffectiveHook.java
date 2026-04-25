package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;

/**
 * Runtime wrapper around a user/system/agent hook entry. Source metadata is intentionally
 * kept outside {@link HookEntry}, so the persisted user JSON schema stays user-only.
 */
public record EffectiveHook(
        HookEvent event,
        HookEntry entry,
        HookSource source,
        String sourceId,
        Long agentAuthoredHookId,
        Long authorAgentId,
        String reviewState
) {

    public static EffectiveHook user(HookEvent event, HookEntry entry, int index) {
        return new EffectiveHook(event, entry, HookSource.USER,
                "user:" + event.wireName() + ":" + index,
                null, null, null);
    }

    public static EffectiveHook system(HookEvent event, HookEntry entry, String sourceId) {
        return new EffectiveHook(event, entry, HookSource.SYSTEM, sourceId, null, null, null);
    }

    public static EffectiveHook agent(HookEvent event,
                                      HookEntry entry,
                                      Long hookId,
                                      Long authorAgentId,
                                      String reviewState) {
        return new EffectiveHook(event, entry, HookSource.AGENT,
                hookId != null ? "agent:" + hookId : "agent:unknown",
                hookId, authorAgentId, reviewState);
    }
}
