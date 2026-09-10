package com.skillforge.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.context.runtime.ContextRuntimeAuthority;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.mcp.service.McpServerService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CollabRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rebuilds the current Session capability authority through the live Engine's own resolver. */
@Service
public class SessionContextRuntimeAuthorityResolver {

    private static final Logger log = LoggerFactory.getLogger(
            SessionContextRuntimeAuthorityResolver.class);

    private final ObjectProvider<AgentLoopEngine> engineProvider;
    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final CollabRunRepository collabRunRepository;
    private final ObjectMapper objectMapper;

    public SessionContextRuntimeAuthorityResolver(
            ObjectProvider<AgentLoopEngine> engineProvider,
            AgentRepository agentRepository,
            AgentService agentService,
            CollabRunRepository collabRunRepository,
            ObjectMapper objectMapper) {
        this.engineProvider = engineProvider;
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.collabRunRepository = collabRunRepository;
        this.objectMapper = objectMapper;
    }

    public ContextRuntimeAuthority resolve(SessionEntity session) {
        if (session == null || session.getId() == null || session.getAgentId() == null) {
            return empty();
        }
        try {
            AgentLoopEngine engine = engineProvider.getIfAvailable();
            AgentEntity agent = agentRepository.findById(session.getAgentId()).orElse(null);
            if (engine == null || agent == null) {
                return empty();
            }
            AgentDefinition definition = agentService.toAgentDefinition(agent);
            applySkillOverrides(session, definition);

            LoopContext context = new LoopContext();
            context.setSessionId(session.getId());
            context.setExecutionMode(effectiveExecutionMode(session, agent));
            configureDepthGate(session, context);
            configureToolAllowlist(session, definition, context);
            context.setAllowedMcpServerNames(new HashSet<>(
                    McpServerService.parseServerIds(agent.getMcpServerIds())));
            return engine.resolveContextRuntimeAuthority(definition, context);
        } catch (RuntimeException invalidOrUnavailableAuthority) {
            log.warn("Context runtime authority unavailable; clearing restored refs: sessionId={}",
                    session.getId());
            return empty();
        }
    }

    private void applySkillOverrides(SessionEntity session, AgentDefinition definition) {
        String json = session.getSkillOverridesJson();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            List<String> names = objectMapper.readValue(json, new TypeReference<>() {});
            definition.setSkillIds(names);
        } catch (JsonProcessingException malformed) {
            // Match the live ChatService behavior: malformed override falls back to Agent skills.
            log.warn("Malformed Session skill override ignored while resolving runtime authority: sessionId={}",
                    session.getId());
        }
    }

    private static String effectiveExecutionMode(SessionEntity session, AgentEntity agent) {
        if (session.getExecutionMode() != null && !session.getExecutionMode().isBlank()) {
            return session.getExecutionMode();
        }
        return agent.getExecutionMode() != null ? agent.getExecutionMode() : "ask";
    }

    private void configureDepthGate(SessionEntity session, LoopContext context) {
        if (session.getCollabRunId() == null) {
            return;
        }
        CollabRunEntity run = collabRunRepository.findById(session.getCollabRunId()).orElse(null);
        if (run != null && session.getDepth() >= run.getMaxDepth()) {
            context.setExcludedSkillNames(Set.of("TeamCreate", "SubAgent"));
        }
    }

    private static void configureToolAllowlist(
            SessionEntity session, AgentDefinition definition, LoopContext context) {
        Object configured = definition.getConfig().get("tool_ids");
        if (!(configured instanceof List<?> raw)) {
            return;
        }
        // Do not silently discard malformed entries: turning a configured-but-invalid list into
        // the empty-list sentinel would make ChatService treat it as "all tools". Let resolve()
        // fail closed to an empty authority instead.
        if (raw.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("tool_ids must contain only strings");
        }
        List<String> ids = raw.stream().map(String.class::cast).toList();
        Set<String> allowed = ChatService.resolveAllowedToolNames(ids, session.getCollabRunId());
        if (allowed != null) {
            context.setAllowedToolNames(allowed);
        }
    }

    private ContextRuntimeAuthority empty() {
        return new ContextRuntimeAuthority(
                ToolCatalog.fromAuthorizedSchemas(List.of(), objectMapper), SessionSkillView.EMPTY);
    }
}
