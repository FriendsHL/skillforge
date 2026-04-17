package com.skillforge.server.hook;

import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopHook;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.AgentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LoopHook} adapter that wires {@code UserPromptSubmit} and {@code Stop} events into
 * the {@link LifecycleHookDispatcher}.
 *
 * <p>{@code SessionStart} is NOT fired here — it is fired from {@code ChatService.chatAsync}
 * so that it only triggers on the first message of a session, not every loop.
 *
 * <p>{@code beforeLoop} returns {@code null} to signal an ABORT to the engine, after setting
 * {@link LoopContext#setAbortedByHook} so the engine can produce a structured "aborted_by_hook"
 * result.
 */
public class LifecycleHookLoopAdapter implements LoopHook {

    private static final Logger log = LoggerFactory.getLogger(LifecycleHookLoopAdapter.class);

    private final LifecycleHookDispatcher dispatcher;

    public LifecycleHookLoopAdapter(LifecycleHookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public LoopContext beforeLoop(LoopContext context) {
        if (context == null) return null;
        AgentDefinition agentDef = context.getAgentDefinition();
        if (agentDef == null || agentDef.getLifecycleHooks() == null) {
            return context;
        }
        try {
            String userMessage = extractLastUserMessageText(context);
            int messageCount = context.getMessages() != null ? context.getMessages().size() : 0;
            boolean keepGoing = dispatcher.fireUserPromptSubmit(
                    agentDef, context.getSessionId(), context.getUserId(), userMessage, messageCount);
            if (!keepGoing) {
                context.markAbortedByHook("UserPromptSubmit hook returned ABORT");
                log.info("UserPromptSubmit ABORT for session={}", context.getSessionId());
                return null;
            }
        } catch (Exception e) {
            // Defensive: the dispatcher should already swallow all errors; if somehow it escapes,
            // don't block the main flow.
            log.warn("UserPromptSubmit dispatch threw (session={}): {}", context.getSessionId(), e.toString());
        }
        return context;
    }

    @Override
    public void afterLoop(LoopContext context, LlmResponse finalResponse) {
        if (context == null) return;
        AgentDefinition agentDef = context.getAgentDefinition();
        if (agentDef == null || agentDef.getLifecycleHooks() == null) return;
        try {
            String text = finalResponse != null ? finalResponse.getContent() : null;
            dispatcher.fireStop(
                    agentDef,
                    context.getSessionId(),
                    context.getUserId(),
                    context.getLoopCount(),
                    context.getTotalInputTokens(),
                    context.getTotalOutputTokens(),
                    text);
        } catch (Exception e) {
            log.warn("Stop dispatch threw (session={}): {}", context.getSessionId(), e.toString());
        }
    }

    private static String extractLastUserMessageText(LoopContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) return null;
        // The user message is always appended last by AgentLoopEngine.run before beforeLoop fires.
        var lastMsg = context.getMessages().get(context.getMessages().size() - 1);
        if (lastMsg == null) return null;
        try {
            return lastMsg.getTextContent();
        } catch (Exception e) {
            return null;
        }
    }
}
