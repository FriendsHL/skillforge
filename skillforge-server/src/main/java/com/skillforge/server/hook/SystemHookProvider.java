package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.model.AgentDefinition;

import java.util.List;

/**
 * Provider for platform-owned lifecycle hooks. Providers are code-owned; their entries are
 * never stored in {@code t_agent.lifecycle_hooks}.
 */
public interface SystemHookProvider {

    List<SystemHookDescriptor> hooksFor(HookEvent event, AgentDefinition agentDef);
}
