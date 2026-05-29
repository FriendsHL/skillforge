package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
import com.skillforge.workflow.engine.WorkflowSubAgentEngineFactory;
import com.skillforge.workflow.schema.SchemaValidator;
import org.springframework.stereotype.Component;

/**
 * Builds per-run {@link DefaultWorkflowAgentInvoker} instances. The shared
 * service dependencies are singletons (injected here); the run id / anchor
 * session / user id vary per workflow run and are passed to {@link #create}.
 */
@Component
public class WorkflowAgentInvokerFactory {

    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final SessionService sessionService;
    private final FlywheelRunService flywheelRunService;
    private final WorkflowSubAgentEngineFactory engineFactory;
    private final ObjectMapper objectMapper;
    private final SchemaValidator schemaValidator;

    public WorkflowAgentInvokerFactory(AgentRepository agentRepository,
                                       AgentService agentService,
                                       SessionService sessionService,
                                       FlywheelRunService flywheelRunService,
                                       WorkflowSubAgentEngineFactory engineFactory,
                                       ObjectMapper objectMapper,
                                       SchemaValidator schemaValidator) {
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.flywheelRunService = flywheelRunService;
        this.engineFactory = engineFactory;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
    }

    public WorkflowAgentInvoker create(String runId, SessionEntity anchorSession, Long userId) {
        return new DefaultWorkflowAgentInvoker(
                agentRepository, agentService, sessionService, flywheelRunService,
                engineFactory, objectMapper, schemaValidator, runId, anchorSession, userId);
    }
}
