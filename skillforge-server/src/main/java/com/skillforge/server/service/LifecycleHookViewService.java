package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.hook.EffectiveHook;
import com.skillforge.server.hook.HookSource;
import com.skillforge.server.hook.LifecycleHookCompositionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LifecycleHookViewService {

    private final AgentService agentService;
    private final LifecycleHookCompositionService compositionService;
    private final AgentAuthoredHookService agentAuthoredHookService;
    private final ObjectMapper objectMapper;

    public LifecycleHookViewService(AgentService agentService,
                                    LifecycleHookCompositionService compositionService,
                                    AgentAuthoredHookService agentAuthoredHookService,
                                    ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.compositionService = compositionService;
        this.agentAuthoredHookService = agentAuthoredHookService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAgentHooks(Long agentId) {
        AgentEntity entity = agentService.getAgent(agentId);
        AgentDefinition def = agentService.toAgentDefinition(entity);

        List<Map<String, Object>> system = new ArrayList<>();
        List<Map<String, Object>> user = new ArrayList<>();
        Map<String, List<Map<String, Object>>> effectiveByEvent = new LinkedHashMap<>();

        for (HookEvent event : HookEvent.values()) {
            List<Map<String, Object>> effective = new ArrayList<>();
            for (EffectiveHook hook : compositionService.dispatchableHooks(def, event)) {
                Map<String, Object> view = effectiveHookToMap(hook);
                effective.add(view);
                if (hook.source() == HookSource.SYSTEM) {
                    system.add(view);
                } else if (hook.source() == HookSource.USER) {
                    user.add(view);
                }
            }
            effectiveByEvent.put(event.wireName(), effective);
        }

        List<Map<String, Object>> agentAuthored = agentAuthoredHookService.listForTarget(agentId).stream()
                .map(this::agentAuthoredHookToMap)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", "1.0");
        response.put("agentId", agentId);
        response.put("mergeOrder", List.of("system", "user", "agent"));
        response.put("counts", counts(system, user, agentAuthored, effectiveByEvent));
        response.put("system", Map.of("entries", system));
        response.put("user", Map.of(
                "rawJson", entity.getLifecycleHooks(),
                "entries", user));
        response.put("agentAuthored", Map.of("entries", agentAuthored));
        response.put("effectiveByEvent", effectiveByEvent);
        return response;
    }

    private Map<String, Object> effectiveHookToMap(EffectiveHook hook) {
        Map<String, Object> map = baseHookMap(hook.entry());
        map.put("event", hook.event().wireName());
        map.put("source", hook.source().name().toLowerCase());
        map.put("sourceId", hook.sourceId());
        map.put("agentAuthoredHookId", hook.agentAuthoredHookId());
        map.put("authorAgentId", hook.authorAgentId());
        map.put("reviewState", hook.reviewState());
        map.put("readOnly", hook.source() != HookSource.USER);
        map.put("dispatchEnabled", true);
        return map;
    }

    private Map<String, Object> agentAuthoredHookToMap(AgentAuthoredHookEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("source", "agent");
        map.put("sourceId", "agent:" + e.getId());
        map.put("targetAgentId", e.getTargetAgentId());
        map.put("authorAgentId", e.getAuthorAgentId());
        map.put("authorSessionId", e.getAuthorSessionId());
        map.put("event", e.getEvent());
        map.put("methodKind", e.getMethodKind());
        map.put("methodId", e.getMethodId());
        map.put("methodRef", e.getMethodRef());
        map.put("displayName", e.getDisplayName());
        map.put("description", e.getDescription());
        map.put("timeoutSeconds", e.getTimeoutSeconds());
        map.put("failurePolicy", e.getFailurePolicy());
        map.put("async", e.isAsync());
        map.put("reviewState", e.getReviewState());
        map.put("reviewNote", e.getReviewNote());
        map.put("reviewedByUserId", e.getReviewedByUserId());
        map.put("reviewedAt", e.getReviewedAt());
        map.put("parentHookId", e.getParentHookId());
        map.put("enabled", e.isEnabled());
        map.put("dispatchEnabled", AgentAuthoredHookEntity.STATE_APPROVED.equals(e.getReviewState()) && e.isEnabled());
        map.put("readOnly", true);
        map.put("stats", Map.of(
                "usageCount", e.getUsageCount(),
                "successCount", e.getSuccessCount(),
                "failureCount", e.getFailureCount()));
        map.put("createdAt", e.getCreatedAt());
        map.put("updatedAt", e.getUpdatedAt());
        return map;
    }

    private Map<String, Object> baseHookMap(HookEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("entry", objectMapper.convertValue(entry, Map.class));
        map.put("displayName", entry.getDisplayName());
        map.put("timeoutSeconds", entry.getTimeoutSeconds());
        map.put("failurePolicy", entry.getFailurePolicy() != null ? entry.getFailurePolicy().name() : "CONTINUE");
        map.put("async", entry.isAsync());
        map.put("handlerSummary", handlerSummary(entry.getHandler()));
        return map;
    }

    private static Map<String, Object> handlerSummary(HookHandler handler) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (handler instanceof HookHandler.SkillHandler h) {
            map.put("type", "skill");
            map.put("name", h.getSkillName());
        } else if (handler instanceof HookHandler.MethodHandler h) {
            map.put("type", "method");
            map.put("name", h.getMethodRef());
        } else if (handler instanceof HookHandler.ScriptHandler h) {
            map.put("type", "script");
            map.put("name", h.getScriptLang());
            map.put("scriptLength", h.getScriptBody() != null ? h.getScriptBody().length() : 0);
        } else {
            map.put("type", "unknown");
        }
        return map;
    }

    private static Map<String, Object> counts(List<Map<String, Object>> system,
                                              List<Map<String, Object>> user,
                                              List<Map<String, Object>> agentAuthored,
                                              Map<String, List<Map<String, Object>>> effectiveByEvent) {
        Map<String, Integer> agentCounts = new LinkedHashMap<>();
        for (String state : List.of("PENDING", "APPROVED", "REJECTED", "RETIRED")) {
            agentCounts.put(state, 0);
        }
        for (Map<String, Object> entry : agentAuthored) {
            Object state = entry.get("reviewState");
            if (state != null) {
                agentCounts.compute(state.toString(), (k, v) -> v == null ? 1 : v + 1);
            }
        }
        int dispatchable = effectiveByEvent.values().stream().mapToInt(List::size).sum();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("system", system.size());
        counts.put("user", user.size());
        counts.put("agentAuthored", agentCounts);
        counts.put("dispatchable", dispatchable);
        return counts;
    }
}
