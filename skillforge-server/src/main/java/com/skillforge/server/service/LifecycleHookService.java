package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.hook.HandlerRunner;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.server.dto.HookHistoryDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.TraceSpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LifecycleHookService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleHookService.class);

    private static final int HOOK_HISTORY_MAX_LIMIT = 200;

    private final AgentRepository agentRepository;
    private final TraceSpanRepository traceSpanRepository;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends HookHandler>, HandlerRunner<?>> runners;

    public LifecycleHookService(AgentRepository agentRepository,
                                TraceSpanRepository traceSpanRepository,
                                ObjectMapper objectMapper,
                                List<HandlerRunner<?>> runnerBeans) {
        this.agentRepository = agentRepository;
        this.traceSpanRepository = traceSpanRepository;
        this.objectMapper = objectMapper;

        Map<Class<? extends HookHandler>, HandlerRunner<?>> map = new HashMap<>();
        for (HandlerRunner<?> r : runnerBeans) {
            map.put(r.handlerType(), r);
        }
        this.runners = Map.copyOf(map);
    }

    public record DryRunInput(Long agentId, String event, Integer entryIndex) {}

    public HookRunResult dryRun(DryRunInput input) {
        AgentEntity agent = agentRepository.findById(input.agentId())
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + input.agentId()));

        // TODO: ownership check — when per-user auth is implemented, verify
        // agent.getOwnerId() matches the caller's userId

        String hooksJson = agent.getLifecycleHooks();
        if (hooksJson == null || hooksJson.isBlank()) {
            throw new IllegalArgumentException("agent has no lifecycle hooks config");
        }

        LifecycleHooksConfig cfg;
        try {
            cfg = objectMapper.readValue(hooksJson, LifecycleHooksConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse lifecycle hooks");
        }

        if (input.event() == null) {
            throw new IllegalArgumentException("event is required");
        }
        HookEvent event = HookEvent.fromWire(input.event());
        if (event == null) {
            throw new IllegalArgumentException("unknown event: " + input.event());
        }

        List<HookEntry> entries = cfg.entriesFor(event);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("no entries for event: " + event.wireName());
        }

        int entryIndex = input.entryIndex() != null ? input.entryIndex() : 0;
        if (entryIndex < 0 || entryIndex >= entries.size()) {
            throw new IllegalArgumentException(
                    "entryIndex " + entryIndex + " out of range [0, " + (entries.size() - 1) + "]");
        }

        HookEntry entry = entries.get(entryIndex);
        HookHandler handler = entry.getHandler();
        if (handler == null) {
            throw new IllegalArgumentException("entry has null handler");
        }

        @SuppressWarnings("unchecked")
        HandlerRunner<HookHandler> runner = (HandlerRunner<HookHandler>) runners.get(handler.getClass());
        if (runner == null) {
            throw new IllegalArgumentException(
                    "no runner for handler type: " + handler.getClass().getSimpleName());
        }

        String syntheticSessionId = "dry-run-" + UUID.randomUUID();
        Map<String, Object> metadata = Map.of(
                "_dry_run", true,
                "_hook_origin", "lifecycle:" + event.wireName(),
                "_hook_timeout_sec", Math.max(1, Math.min(entry.getTimeoutSeconds(), 30))
        );
        HookExecutionContext ctx = new HookExecutionContext(syntheticSessionId, null, event, metadata);

        return runner.run(handler, Map.of(), ctx);
    }

    @Transactional(readOnly = true)
    public List<HookHistoryDto> getHookHistory(Long agentId, int limit) {
        if (!agentRepository.existsById(agentId)) {
            throw new IllegalArgumentException("agent not found: " + agentId);
        }

        // TODO: ownership check — when per-user auth is implemented, verify
        // agent.getOwnerId() matches the caller's userId

        int clampedLimit = Math.max(1, Math.min(limit, HOOK_HISTORY_MAX_LIMIT));
        return traceSpanRepository.findHookHistoryByAgentId(agentId, PageRequest.of(0, clampedLimit))
                .stream()
                .map(HookHistoryDto::from)
                .toList();
    }
}
