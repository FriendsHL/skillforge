package com.skillforge.server.hook;

import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserPromptSubmit prompt-enrichment tests. Verifies that when a synchronous UPS handler
 * returns {@code {"injected_context":"..."}}, the adapter appends a fresh user message
 * to {@link LoopContext#getMessages()}.
 */
class LifecycleHookLoopAdapterTest {

    /** Dispatcher stub that returns a canned DispatchOutcome for UPS. */
    private static LifecycleHookDispatcher stub(LifecycleHookDispatcher.DispatchOutcome outcome) {
        return new LifecycleHookDispatcher() {
            @Override
            public boolean dispatch(HookEvent event, Map<String, Object> input,
                                    AgentDefinition agentDef, String sessionId, Long userId) { return true; }
            @Override
            public LifecycleHookDispatcher.DispatchOutcome dispatchCollecting(HookEvent event,
                                                                              Map<String, Object> input,
                                                                              AgentDefinition agentDef,
                                                                              String sessionId, Long userId) {
                return outcome;
            }
            @Override
            public boolean fireSessionStart(AgentDefinition agentDef, String sessionId, Long userId) { return true; }
            @Override
            public boolean fireUserPromptSubmit(AgentDefinition agentDef, String sessionId, Long userId,
                                                String userMessage, int messageCount) { return outcome.keepGoing(); }
            @Override
            public LifecycleHookDispatcher.DispatchOutcome fireUserPromptSubmitCollecting(
                    AgentDefinition agentDef, String sessionId, Long userId,
                    String userMessage, int messageCount) { return outcome; }
            @Override
            public void firePostToolUse(AgentDefinition agentDef, String sessionId, Long userId,
                                        String skillName, Map<String, Object> skillInput,
                                        SkillResult result, long durationMs) {}
            @Override
            public void fireStop(AgentDefinition agentDef, String sessionId, Long userId,
                                 int loopCount, long inputTokens, long outputTokens, String finalResponse) {}
            @Override
            public void fireSessionEnd(AgentDefinition agentDef, String sessionId, Long userId,
                                       int messageCount, String reason) {}
        };
    }

    private static LoopContext ctxWithLastUserMessage(String text) {
        LoopContext ctx = new LoopContext();
        AgentDefinition def = new AgentDefinition();
        def.setLifecycleHooks(new LifecycleHooksConfig());
        ctx.setAgentDefinition(def);
        ctx.setSessionId("sess-1");
        ctx.setUserId(7L);
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user(text));
        ctx.setMessages(msgs);
        return ctx;
    }

    @Test
    @DisplayName("injected_context from UPS handler output is appended as a fresh user message")
    void injectedContext_appendsUserMessage() {
        HookRunResult r = HookRunResult.ok("{\"injected_context\":\"recent memory fact X\"}", 0);
        LifecycleHookDispatcher dispatcher = stub(
                new LifecycleHookDispatcher.DispatchOutcome(true, List.of(r)));

        LifecycleHookLoopAdapter adapter = new LifecycleHookLoopAdapter(dispatcher);
        LoopContext ctx = ctxWithLastUserMessage("hi");
        LoopContext after = adapter.beforeLoop(ctx);

        assertThat(after).isSameAs(ctx);
        assertThat(ctx.getMessages()).hasSize(2);
        Message appended = ctx.getMessages().get(1);
        assertThat(appended.getRole()).isEqualTo(Message.Role.USER);
        assertThat(appended.getTextContent()).contains("[Context]").contains("recent memory fact X");
    }

    @Test
    @DisplayName("Empty or null injected_context does not change messages")
    void emptyInjectedContext_isNoOp() {
        HookRunResult rEmpty = HookRunResult.ok("{\"injected_context\":\"\"}", 0);
        HookRunResult rNull = HookRunResult.ok("{\"injected_context\":null}", 0);
        HookRunResult rMissing = HookRunResult.ok("{\"other\":\"x\"}", 0);
        HookRunResult rNonJson = HookRunResult.ok("this is not JSON", 0);
        LifecycleHookDispatcher dispatcher = stub(new LifecycleHookDispatcher.DispatchOutcome(
                true, List.of(rEmpty, rNull, rMissing, rNonJson)));

        LifecycleHookLoopAdapter adapter = new LifecycleHookLoopAdapter(dispatcher);
        LoopContext ctx = ctxWithLastUserMessage("hi");
        adapter.beforeLoop(ctx);

        assertThat(ctx.getMessages()).as("no injection cases should leave messages untouched").hasSize(1);
    }
}
