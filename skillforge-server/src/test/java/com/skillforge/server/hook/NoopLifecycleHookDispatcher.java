package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillResult;

import java.util.Map;

/**
 * No-op {@link LifecycleHookDispatcher} for tests that don't care about hooks. All synchronous
 * methods return true (continue); void methods are empty. Lives under
 * {@code src/test/java/com/skillforge/server/hook} and is not a Spring bean.
 */
public class NoopLifecycleHookDispatcher implements LifecycleHookDispatcher {

    @Override
    public boolean dispatch(HookEvent event, Map<String, Object> input,
                            AgentDefinition agentDef, String sessionId, Long userId) {
        return true;
    }

    @Override
    public boolean fireSessionStart(AgentDefinition agentDef, String sessionId, Long userId) {
        return true;
    }

    @Override
    public boolean fireUserPromptSubmit(AgentDefinition agentDef, String sessionId, Long userId,
                                        String userMessage, int messageCount) {
        return true;
    }

    @Override
    public void firePostToolUse(AgentDefinition agentDef, String sessionId, Long userId,
                                String skillName, Map<String, Object> skillInput, SkillResult result,
                                long durationMs) {
    }

    @Override
    public void fireStop(AgentDefinition agentDef, String sessionId, Long userId, int loopCount,
                         long inputTokens, long outputTokens, String finalResponse) {
    }

    @Override
    public void fireSessionEnd(AgentDefinition agentDef, String sessionId, Long userId,
                               int messageCount, String reason) {
    }
}
