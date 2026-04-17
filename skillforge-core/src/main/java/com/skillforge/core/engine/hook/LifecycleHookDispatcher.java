package com.skillforge.core.engine.hook;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillResult;

import java.util.Map;

/**
 * Dispatches lifecycle hook events for a given agent+session.
 *
 * <p>The interface lives in {@code skillforge-core} so core engine components
 * (e.g. {@code LifecycleHookLoopAdapter}) can depend on it without reaching into
 * the server module. The concrete implementation (thread pool, tracing, runner registry)
 * is wired in {@code skillforge-server}.
 *
 * <p>Boolean-returning variants ({@link #dispatch}, {@link #fireSessionStart},
 * {@link #fireUserPromptSubmit}) return {@code true} when the main flow should continue,
 * {@code false} when an ABORT policy fired. Void variants log/trace only.
 */
public interface LifecycleHookDispatcher {

    /**
     * Generic dispatch entry point used by the five named wrappers.
     * {@code input} is the per-event schema (see {@code docs/design-n3-lifecycle-hooks.md §3.4}).
     */
    boolean dispatch(HookEvent event,
                     Map<String, Object> input,
                     AgentDefinition agentDef,
                     String sessionId,
                     Long userId);

    boolean fireSessionStart(AgentDefinition agentDef, String sessionId, Long userId);

    boolean fireUserPromptSubmit(AgentDefinition agentDef,
                                 String sessionId,
                                 Long userId,
                                 String userMessage,
                                 int messageCount);

    void firePostToolUse(AgentDefinition agentDef,
                         String sessionId,
                         Long userId,
                         String skillName,
                         Map<String, Object> skillInput,
                         SkillResult result,
                         long durationMs);

    void fireStop(AgentDefinition agentDef,
                  String sessionId,
                  Long userId,
                  int loopCount,
                  long inputTokens,
                  long outputTokens,
                  String finalResponse);

    void fireSessionEnd(AgentDefinition agentDef,
                        String sessionId,
                        Long userId,
                        int messageCount,
                        String reason);
}
