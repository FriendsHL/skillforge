package com.skillforge.core.engine.hook;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillResult;

import java.util.List;
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

    /**
     * Variant of {@link #dispatch} that also exposes the per-entry results so callers can
     * harvest handler outputs (e.g. {@code UserPromptSubmit} injected_context). Sync entries
     * are fully materialized; async entries produce no result record and the caller must not
     * wait on them.
     */
    default DispatchOutcome dispatchCollecting(HookEvent event,
                                               Map<String, Object> input,
                                               AgentDefinition agentDef,
                                               String sessionId,
                                               Long userId) {
        boolean keepGoing = dispatch(event, input, agentDef, sessionId, userId);
        return new DispatchOutcome(keepGoing, List.of());
    }

    boolean fireSessionStart(AgentDefinition agentDef, String sessionId, Long userId);

    boolean fireUserPromptSubmit(AgentDefinition agentDef,
                                 String sessionId,
                                 Long userId,
                                 String userMessage,
                                 int messageCount);

    /**
     * {@link #fireUserPromptSubmit} sibling that returns per-entry runner results. Used by
     * the loop adapter to harvest {@code injected_context} from handler output for prompt
     * enrichment. Default implementation proxies to {@link #fireUserPromptSubmit}.
     */
    default DispatchOutcome fireUserPromptSubmitCollecting(AgentDefinition agentDef,
                                                           String sessionId,
                                                           Long userId,
                                                           String userMessage,
                                                           int messageCount) {
        boolean keepGoing = fireUserPromptSubmit(agentDef, sessionId, userId, userMessage, messageCount);
        return new DispatchOutcome(keepGoing, List.of());
    }

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

    /**
     * Aggregated outcome returned by {@link #dispatchCollecting}. Contains the final
     * {@code keepGoing} flag plus a snapshot of synchronous-entry results in execution order.
     * Async-entry results are intentionally absent — callers must not block on them.
     */
    record DispatchOutcome(boolean keepGoing, List<HookRunResult> syncResults) {
        public DispatchOutcome {
            syncResults = syncResults != null ? List.copyOf(syncResults) : List.of();
        }
    }
}
