package com.skillforge.server.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopHook;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link LoopHook} adapter that wires {@code UserPromptSubmit} and {@code Stop} events into
 * the {@link LifecycleHookDispatcher}.
 *
 * <p>{@code SessionStart} is NOT fired here — it is fired from {@code ChatService.chatAsync}
 * so that it only triggers on the first message of a session, not every loop.
 *
 * <p>{@code beforeLoop} returns {@code null} to signal an ABORT to the engine, after setting
 * {@link LoopContext#markAbortedByHook} so the engine can produce a structured "aborted_by_hook"
 * result.
 *
 * <p><b>Prompt enrichment.</b> When a {@code UserPromptSubmit} handler returns JSON with a
 * non-empty {@code injected_context} string, we append a fresh {@code user} message
 * ({@code "[Context]\n${injected_context}"}) to {@link LoopContext#getMessages()} so the
 * enrichment is visible to the LLM call. A separate message (not concatenation onto the
 * existing user message) is used because the user message's {@code content} may be an
 * array of {@code ContentBlock}s (tool_result / image) — string concatenation would corrupt
 * it.
 */
public class LifecycleHookLoopAdapter implements LoopHook {

    private static final Logger log = LoggerFactory.getLogger(LifecycleHookLoopAdapter.class);

    /** Local JSON parser — injected_context payloads are small and untrusted user output. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private final LifecycleHookDispatcher dispatcher;

    public LifecycleHookLoopAdapter(LifecycleHookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public LoopContext beforeLoop(LoopContext context) {
        if (context == null) return null;
        AgentDefinition agentDef = context.getAgentDefinition();
        if (agentDef == null) {
            return context;
        }
        try {
            String userMessage = extractLastUserMessageText(context);
            int messageCount = context.getMessages() != null ? context.getMessages().size() : 0;
            LifecycleHookDispatcher.DispatchOutcome outcome = dispatcher.fireUserPromptSubmitCollecting(
                    agentDef, context.getSessionId(), context.getUserId(), userMessage, messageCount);
            if (!outcome.keepGoing()) {
                context.markAbortedByHook("UserPromptSubmit hook returned ABORT");
                log.info("UserPromptSubmit ABORT for session={}", context.getSessionId());
                return null;
            }
            applyInjectedContext(context, outcome.syncResults());
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
        if (agentDef == null) return;
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

    /**
     * For every sync UserPromptSubmit result, if output parses as JSON with a non-empty
     * {@code injected_context} string field, append a new user message to the loop context.
     */
    private static void applyInjectedContext(LoopContext context, List<HookRunResult> syncResults) {
        if (syncResults == null || syncResults.isEmpty()) return;
        List<Message> messages = context.getMessages();
        if (messages == null) {
            messages = new ArrayList<>();
            context.setMessages(messages);
        }
        for (HookRunResult r : syncResults) {
            if (r == null || !r.success() || r.output() == null || r.output().isBlank()) continue;
            String injected = extractInjectedContext(r.output());
            if (injected == null || injected.isBlank()) continue;
            String combined = "[Context]\n" + injected;
            messages.add(Message.user(combined));
        }
    }

    private static String extractInjectedContext(String jsonOrText) {
        String trimmed = jsonOrText.strip();
        if (trimmed.isEmpty()) return null;
        // Only attempt JSON parse when the payload looks like a JSON object.
        if (trimmed.charAt(0) != '{') return null;
        try {
            JsonNode root = JSON.readTree(trimmed);
            JsonNode n = root.get("injected_context");
            if (n == null || n.isNull() || !n.isTextual()) return null;
            String v = n.asText();
            return v == null || v.isBlank() ? null : v;
        } catch (Exception e) {
            // Non-JSON handler output is allowed — treat as no injection, not an error.
            return null;
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
