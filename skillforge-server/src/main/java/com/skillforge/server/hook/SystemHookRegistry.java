package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.model.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SystemHookRegistry {

    private final List<SystemHookProvider> providers;

    public SystemHookRegistry(List<SystemHookProvider> providers) {
        this.providers = providers != null ? List.copyOf(providers) : List.of();
    }

    public List<EffectiveHook> hooksFor(HookEvent event, AgentDefinition agentDef) {
        if (event == null || providers.isEmpty()) {
            return List.of();
        }
        List<EffectiveHook> result = new ArrayList<>();
        for (SystemHookProvider provider : providers) {
            List<SystemHookDescriptor> descriptors = provider.hooksFor(event, agentDef);
            if (descriptors == null) {
                continue;
            }
            for (SystemHookDescriptor d : descriptors) {
                if (d == null || d.entry() == null || d.entry().getHandler() == null) {
                    continue;
                }
                String sourceId = d.sourceId() != null && !d.sourceId().isBlank()
                        ? d.sourceId()
                        : "system:" + event.wireName() + ":" + result.size();
                result.add(EffectiveHook.system(event, d.entry(), sourceId));
            }
        }
        return result;
    }
}
