package com.skillforge.core.engine;

import com.skillforge.core.engine.confirm.ChannelUnavailableException;
import com.skillforge.core.engine.confirm.ConfirmationPrompter;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.RootSessionLookup;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers §9.1 P0 test rows for install-confirmation: APPROVED happy, DENIED, ask_user
 * pending mutex, cache-hit short-circuit, and the "> 120s main-thread block is NOT
 * truncated by the supplyAsync timeout" invariant.
 */
class AgentLoopEngineInstallConfirmationTest {

    /** Trivial Bash stub that records invocations and returns a canned SkillResult. */
    private static class RecordingBashTool implements Tool {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Map<String, Object>> lastInput = new AtomicReference<>();

        @Override public String getName() { return "Bash"; }
        @Override public String getDescription() { return "test-only bash"; }
        @Override public ToolSchema getToolSchema() {
            ToolSchema s = new ToolSchema();
            s.setName("Bash");
            s.setDescription("test");
            s.setInputSchema(Map.of("type", "object", "properties",
                    Map.of("command", Map.of("type", "string"))));
            return s;
        }
        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            calls.incrementAndGet();
            lastInput.set(input);
            return SkillResult.success("ran: " + input.get("command"));
        }
    }

    private AgentLoopEngine newEngine(RecordingBashTool tool,
                                      ConfirmationPrompter prompter,
                                      SessionConfirmCache cache) {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = new AgentLoopEngine(
                new LlmProviderFactory(), "unused", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        engine.setConfirmationPrompter(prompter);
        engine.setSessionConfirmCache(cache != null ? cache : new SessionConfirmCache());
        engine.setRootSessionLookup(sid -> sid);
        engine.setPendingAskRegistry(new PendingAskRegistry());
        return engine;
    }

    private LoopContext ctx(String sid) {
        LoopContext c = new LoopContext();
        c.setSessionId(sid);
        c.setUserId(1L);
        c.setMessages(new ArrayList<>());
        return c;
    }

    private ToolUseBlock installBlock(String cmd) {
        return new ToolUseBlock(UUID.randomUUID().toString(), "Bash",
                Map.of("command", cmd));
    }

    @Test
    @DisplayName("APPROVED: executeToolCall runs, cache is populated, tool_result success")
    void approvedRunsBash() {
        SessionConfirmCache cache = new SessionConfirmCache();
        ConfirmationPrompter prompter = req -> Decision.APPROVED;
        RecordingBashTool skill = new RecordingBashTool();
        RecordingBashTool tool = skill;
        AgentLoopEngine engine = newEngine(tool, prompter, cache);

        ToolUseBlock blk = installBlock("clawhub install obsidian");
        Message r = engine.handleInstallConfirmation(blk, ctx("s1"), new CopyOnWriteArrayList<>());

        assertThat(skill.calls.get()).isEqualTo(1);
        assertThat(isError(r)).isFalse();
        assertThat(cache.isApproved("s1", "clawhub", "obsidian")).isTrue();
    }

    @Test
    @DisplayName("DENIED: no Bash execution, tool_result is error")
    void deniedDoesNotRunBash() {
        ConfirmationPrompter prompter = req -> Decision.DENIED;
        RecordingBashTool skill = new RecordingBashTool();
        AgentLoopEngine engine = newEngine(skill, prompter, null);

        Message r = engine.handleInstallConfirmation(installBlock("clawhub install obsidian"),
                ctx("s1"), new CopyOnWriteArrayList<>());

        assertThat(skill.calls.get()).isZero();
        assertThat(isError(r)).isTrue();
        assertThat(textOf(r)).contains("User denied");
    }

    @Test
    @DisplayName("TIMEOUT: no Bash execution, tool_result is error")
    void timeoutDoesNotRunBash() {
        ConfirmationPrompter prompter = req -> Decision.TIMEOUT;
        RecordingBashTool skill = new RecordingBashTool();
        AgentLoopEngine engine = newEngine(skill, prompter, null);

        Message r = engine.handleInstallConfirmation(installBlock("clawhub install obsidian"),
                ctx("s1"), new CopyOnWriteArrayList<>());

        assertThat(skill.calls.get()).isZero();
        assertThat(isError(r)).isTrue();
        assertThat(textOf(r)).contains("timed out");
    }

    @Test
    @DisplayName("ask_user pending: install returns error WITHOUT calling prompter")
    void askPendingShortCircuits() {
        AtomicInteger prompterCalls = new AtomicInteger();
        ConfirmationPrompter prompter = req -> {
            prompterCalls.incrementAndGet();
            return Decision.APPROVED;
        };
        RecordingBashTool skill = new RecordingBashTool();
        AgentLoopEngine engine = newEngine(skill, prompter, null);

        // Seed a pending ask on sid
        PendingAskRegistry ask = new PendingAskRegistry();
        ask.register("ask-1", "s1");
        engine.setPendingAskRegistry(ask);

        Message r = engine.handleInstallConfirmation(installBlock("clawhub install obsidian"),
                ctx("s1"), new CopyOnWriteArrayList<>());

        assertThat(prompterCalls.get()).isZero();
        assertThat(skill.calls.get()).isZero();
        assertThat(isError(r)).isTrue();
        assertThat(textOf(r)).contains("ask_user is pending");
    }

    @Test
    @DisplayName("cache hit → executeToolCall directly, no prompter call")
    void cacheHitSkipsPrompter() {
        AtomicInteger prompterCalls = new AtomicInteger();
        ConfirmationPrompter prompter = req -> {
            prompterCalls.incrementAndGet();
            return Decision.APPROVED;
        };
        SessionConfirmCache cache = new SessionConfirmCache();
        cache.approve("s1", "clawhub", "obsidian");
        RecordingBashTool skill = new RecordingBashTool();
        AgentLoopEngine engine = newEngine(skill, prompter, cache);

        Message r = engine.handleInstallConfirmation(installBlock("clawhub install obsidian"),
                ctx("s1"), new CopyOnWriteArrayList<>());

        assertThat(prompterCalls.get()).isZero();
        assertThat(skill.calls.get()).isEqualTo(1);
        assertThat(isError(r)).isFalse();
    }

    @Test
    @DisplayName("ChannelUnavailableException from prompter → error tool_result with its message")
    void channelUnavailable() {
        ConfirmationPrompter prompter = req -> {
            throw new ChannelUnavailableException("Confirmation channel unavailable: test");
        };
        RecordingBashTool skill = new RecordingBashTool();
        AgentLoopEngine engine = newEngine(skill, prompter, null);

        Message r = engine.handleInstallConfirmation(installBlock("clawhub install obsidian"),
                ctx("s1"), new CopyOnWriteArrayList<>());

        assertThat(skill.calls.get()).isZero();
        assertThat(isError(r)).isTrue();
        assertThat(textOf(r)).contains("unavailable");
    }

    @Test
    @DisplayName("Main-thread blocks beyond 120s without being truncated (no allOf timeout applies)")
    void mainThreadBlockIsUnbounded() throws Exception {
        // Simulate a prompter that takes >150ms (we can't wait 120s in test; the key
        // invariant is that handleInstallConfirmation is NOT wrapped by allOf(futures)
        // and will return whatever the prompter returns, regardless of duration).
        ConfirmationPrompter prompter = req -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            return Decision.APPROVED;
        };
        RecordingBashTool skill = new RecordingBashTool();
        AgentLoopEngine engine = newEngine(skill, prompter, null);

        long t0 = System.currentTimeMillis();
        Message r = engine.handleInstallConfirmation(installBlock("clawhub install obsidian"),
                ctx("s1"), new CopyOnWriteArrayList<>());
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(elapsed).isGreaterThanOrEqualTo(100L);
        assertThat(skill.calls.get()).isEqualTo(1);
        assertThat(isError(r)).isFalse();
    }

    @Test
    @DisplayName("Different target → cache miss, prompter IS called")
    void differentTargetPrompts() {
        AtomicInteger prompterCalls = new AtomicInteger();
        ConfirmationPrompter prompter = req -> {
            prompterCalls.incrementAndGet();
            return Decision.APPROVED;
        };
        SessionConfirmCache cache = new SessionConfirmCache();
        cache.approve("s1", "clawhub", "obsidian");
        RecordingBashTool skill = new RecordingBashTool();
        AgentLoopEngine engine = newEngine(skill, prompter, cache);

        Message r = engine.handleInstallConfirmation(installBlock("clawhub install another"),
                ctx("s1"), new CopyOnWriteArrayList<>());

        assertThat(prompterCalls.get()).isEqualTo(1);
        assertThat(skill.calls.get()).isEqualTo(1); // approved this time
        assertThat(isError(r)).isFalse();
    }

    @Test
    @DisplayName("Sub-session with same target shares root cache (inheritance via rootSessionLookup)")
    void subSessionInheritsRootCache() {
        AtomicInteger prompterCalls = new AtomicInteger();
        ConfirmationPrompter prompter = req -> {
            prompterCalls.incrementAndGet();
            return Decision.APPROVED;
        };
        SessionConfirmCache cache = new SessionConfirmCache();
        cache.approve("root", "clawhub", "obsidian");

        RecordingBashTool skill = new RecordingBashTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(skill);
        AgentLoopEngine engine = new AgentLoopEngine(
                new LlmProviderFactory(), "unused", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        engine.setConfirmationPrompter(prompter);
        engine.setSessionConfirmCache(cache);
        engine.setRootSessionLookup(sid -> "child".equals(sid) ? "root" : sid);
        engine.setPendingAskRegistry(new PendingAskRegistry());

        Message r = engine.handleInstallConfirmation(installBlock("clawhub install obsidian"),
                ctx("child"), new CopyOnWriteArrayList<>());

        assertThat(prompterCalls.get()).isZero();
        assertThat(skill.calls.get()).isEqualTo(1);
        assertThat(isError(r)).isFalse();
    }

    @Test
    @DisplayName("isInstallRequiringConfirmation true for clawhub install Bash, false for non-install Bash")
    void isInstallPredicate() {
        AgentLoopEngine engine = new AgentLoopEngine(
                new LlmProviderFactory(), "unused", new SkillRegistry(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertThat(engine.isInstallRequiringConfirmation(
                new ToolUseBlock("id1", "Bash", Map.of("command", "clawhub install x")))).isTrue();
        assertThat(engine.isInstallRequiringConfirmation(
                new ToolUseBlock("id2", "Bash", Map.of("command", "echo hi")))).isFalse();
        assertThat(engine.isInstallRequiringConfirmation(
                new ToolUseBlock("id3", "FileRead", Map.of("command", "clawhub install x")))).isFalse();
    }

    // ---- helpers ----

    private static boolean isError(Message m) {
        if (!(m.getContent() instanceof List<?> blocks)) return false;
        for (Object o : blocks) {
            if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())) {
                return Boolean.TRUE.equals(cb.getIsError());
            }
        }
        return false;
    }

    private static String textOf(Message m) {
        if (!(m.getContent() instanceof List<?> blocks)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object o : blocks) {
            if (o instanceof ContentBlock cb && cb.getContent() != null) sb.append(cb.getContent());
        }
        return sb.toString();
    }
}
