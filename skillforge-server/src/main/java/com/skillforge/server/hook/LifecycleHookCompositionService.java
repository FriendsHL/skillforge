package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.service.AgentAuthoredHookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class LifecycleHookCompositionService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleHookCompositionService.class);

    private final SystemHookRegistry systemHookRegistry;
    private final AgentAuthoredHookService agentAuthoredHookService;

    public LifecycleHookCompositionService(SystemHookRegistry systemHookRegistry,
                                           AgentAuthoredHookService agentAuthoredHookService) {
        this.systemHookRegistry = systemHookRegistry;
        this.agentAuthoredHookService = agentAuthoredHookService;
    }

    /**
     * Return hooks in dispatch order: system -> user -> approved agent-authored.
     */
    public List<EffectiveHook> dispatchableHooks(AgentDefinition agentDef, HookEvent event) {
        if (agentDef == null || event == null) {
            return List.of();
        }
        List<EffectiveHook> result = new ArrayList<>();
        result.addAll(systemHookRegistry.hooksFor(event, agentDef));
        result.addAll(userHooks(agentDef.getLifecycleHooks(), event));
        Long agentId = parseAgentId(agentDef);
        if (agentId != null) {
            result.addAll(agentHooks(agentId, event));
        }
        return result;
    }

    public void recordExecution(EffectiveHook hook, boolean success, String errorMessage) {
        if (hook == null || hook.agentAuthoredHookId() == null) {
            return;
        }
        agentAuthoredHookService.recordExecution(hook.agentAuthoredHookId(), success, errorMessage);
    }

    private List<EffectiveHook> userHooks(LifecycleHooksConfig config, HookEvent event) {
        if (config == null) {
            return List.of();
        }
        List<HookEntry> entries = config.entriesFor(event);
        if (entries.isEmpty()) {
            return List.of();
        }
        List<EffectiveHook> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            HookEntry entry = entries.get(i);
            if (entry == null || entry.getHandler() == null) {
                continue;
            }
            result.add(EffectiveHook.user(event, entry, i));
        }
        return result;
    }

    private List<EffectiveHook> agentHooks(Long agentId, HookEvent event) {
        List<AgentAuthoredHookEntity> rows = agentAuthoredHookService.findDispatchable(agentId, event);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<EffectiveHook> result = new ArrayList<>();
        for (AgentAuthoredHookEntity row : rows) {
            try {
                HookEntry entry = agentAuthoredHookService.toHookEntry(row);
                if (entry != null && entry.getHandler() != null) {
                    result.add(EffectiveHook.agent(event, entry, row.getId(),
                            row.getAuthorAgentId(), row.getReviewState()));
                }
            } catch (RuntimeException e) {
                log.warn("Skipping invalid agent-authored hook id={} targetAgent={} event={}: {}",
                        row.getId(), agentId, event, e.getMessage());
            }
        }
        return result;
    }

    private static Long parseAgentId(AgentDefinition agentDef) {
        if (agentDef.getId() == null || agentDef.getId().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(agentDef.getId());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
