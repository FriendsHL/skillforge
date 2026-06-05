package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.workflow.engine.WorkflowEvolveToolRegistryFactory;
import org.springframework.stereotype.Component;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — builds per-run {@link DefaultWorkflowToolInvoker}
 * instances for the {@code tool()} host binding. The shared service dependencies
 * are singletons (injected here); the run id / user id vary per workflow run and
 * are passed to {@link #create}. Mirrors {@link WorkflowAgentInvokerFactory}.
 */
@Component
public class WorkflowToolInvokerFactory {

    private final FlywheelRunService flywheelRunService;
    private final WorkflowEvolveToolRegistryFactory registryFactory;
    private final ObjectMapper objectMapper;

    public WorkflowToolInvokerFactory(FlywheelRunService flywheelRunService,
                                      WorkflowEvolveToolRegistryFactory registryFactory,
                                      ObjectMapper objectMapper) {
        this.flywheelRunService = flywheelRunService;
        this.registryFactory = registryFactory;
        this.objectMapper = objectMapper;
    }

    public WorkflowToolInvoker create(String runId, Long userId) {
        return new DefaultWorkflowToolInvoker(
                flywheelRunService, registryFactory, objectMapper, runId, userId);
    }
}
