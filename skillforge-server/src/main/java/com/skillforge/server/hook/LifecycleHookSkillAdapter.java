package com.skillforge.server.hook;

import com.skillforge.core.engine.SkillHook;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link SkillHook} adapter that fires {@code PostToolUse} lifecycle hooks after each Skill
 * execution.
 *
 * <p>Limitations of the {@link SkillHook} contract: {@code afterSkillExecute} does not have
 * access to the owning {@link com.skillforge.core.engine.LoopContext} or {@link
 * com.skillforge.core.model.AgentDefinition}. We extract the session id from the provided
 * {@link SkillContext} and look up the active agent definition via a resolver callback
 * installed by the server layer (see {@code SkillForgeConfig}).
 *
 * <p>PostToolUse is fire-and-log; it cannot ABORT a running loop.
 */
public class LifecycleHookSkillAdapter implements SkillHook {

    private static final Logger log = LoggerFactory.getLogger(LifecycleHookSkillAdapter.class);

    /**
     * Resolver that maps a session id → active {@link com.skillforge.core.model.AgentDefinition}.
     * Installed by server layer because core has no session registry visibility.
     */
    public interface AgentDefinitionResolver {
        com.skillforge.core.model.AgentDefinition resolveForSession(String sessionId);
    }

    private final LifecycleHookDispatcher dispatcher;
    private final AgentDefinitionResolver agentDefResolver;

    public LifecycleHookSkillAdapter(LifecycleHookDispatcher dispatcher,
                                     AgentDefinitionResolver agentDefResolver) {
        this.dispatcher = dispatcher;
        this.agentDefResolver = agentDefResolver;
    }

    @Override
    public Map<String, Object> beforeSkillExecute(String skillName,
                                                  Map<String, Object> input,
                                                  SkillContext context) {
        return input;
    }

    @Override
    public void afterSkillExecute(String skillName,
                                  Map<String, Object> input,
                                  SkillResult result,
                                  SkillContext context) {
        if (context == null || context.getSessionId() == null) return;
        try {
            var agentDef = agentDefResolver.resolveForSession(context.getSessionId());
            if (agentDef == null) return;
            // Defensive copy: prevent the hook handler from mutating the live skill input map
            // and accidentally affecting downstream Skill execution. Map.copyOf would NPE on
            // null values, which Skill inputs may legitimately contain — use HashMap + unmodifiableMap.
            Map<String, Object> safeInput = input == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new HashMap<>(input));
            // Duration isn't surfaced by the SkillHook contract today; 0 is the least-bad default
            // until we extend the contract (tracked in the SkillHook TODO).
            dispatcher.firePostToolUse(
                    agentDef,
                    context.getSessionId(),
                    context.getUserId(),
                    skillName,
                    safeInput,
                    result,
                    0L);
        } catch (Exception e) {
            log.warn("PostToolUse dispatch failed (skill={}): {}", skillName, e.toString());
        }
    }
}
