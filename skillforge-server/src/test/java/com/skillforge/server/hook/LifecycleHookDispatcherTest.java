package com.skillforge.server.hook;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.skillforge.core.engine.TraceCollector;
import com.skillforge.core.engine.TraceSpan;
import com.skillforge.core.engine.hook.FailurePolicy;
import com.skillforge.core.engine.hook.HandlerRunner;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LifecycleHookDispatcherImpl}. Uses real executor + stub runner
 * (no Mockito — we need deterministic control over runner behavior).
 */
class LifecycleHookDispatcherTest {

    private ExecutorService executor;
    private List<TraceSpan> collected;
    private TraceCollector traceCollector;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-hook");
            t.setDaemon(true);
            return t;
        });
        collected = new ArrayList<>();
        traceCollector = span -> {
            synchronized (collected) {
                collected.add(span);
            }
        };
    }

    private AgentDefinition agentWithHook(HookEvent event, HookEntry entry) {
        AgentDefinition def = new AgentDefinition();
        def.setId("42");
        def.setName("TestAgent");
        LifecycleHooksConfig cfg = new LifecycleHooksConfig();
        cfg.putEntries(event, List.of(entry));
        def.setLifecycleHooks(cfg);
        return def;
    }

    private HookEntry entry(HookHandler handler, int timeoutSec, FailurePolicy policy, boolean async) {
        HookEntry e = new HookEntry();
        e.setHandler(handler);
        e.setTimeoutSeconds(timeoutSec);
        e.setFailurePolicy(policy);
        e.setAsync(async);
        return e;
    }

    private LifecycleHookDispatcherImpl newDispatcher(HandlerRunner<?>... runners) {
        return new LifecycleHookDispatcherImpl(List.of(runners), executor, traceCollector);
    }

    private static class StubRunner implements HandlerRunner<HookHandler.SkillHandler> {
        final AtomicInteger calls = new AtomicInteger(0);
        final HookRunResult result;
        final long sleepMs;
        final RuntimeException throwException;

        StubRunner(HookRunResult result, long sleepMs, RuntimeException throwException) {
            this.result = result;
            this.sleepMs = sleepMs;
            this.throwException = throwException;
        }

        @Override
        public Class<HookHandler.SkillHandler> handlerType() {
            return HookHandler.SkillHandler.class;
        }

        @Override
        public HookRunResult run(HookHandler.SkillHandler handler, Map<String, Object> input, HookExecutionContext ctx) {
            calls.incrementAndGet();
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if (throwException != null) throw throwException;
            return result;
        }
    }

    @Test
    @DisplayName("dispatch returns true when agent has no hook config")
    void dispatch_noHookConfig_returnsTrue() {
        LifecycleHookDispatcherImpl dispatcher = newDispatcher();
        AgentDefinition def = new AgentDefinition();
        def.setId("1");
        boolean result = dispatcher.dispatch(HookEvent.SESSION_START, Map.of(), def, "s1", 99L);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("dispatch returns true when no entries for the fired event")
    void dispatch_eventNotConfigured_returnsTrue() {
        LifecycleHookDispatcherImpl dispatcher = newDispatcher();
        AgentDefinition def = agentWithHook(HookEvent.SESSION_END,
                entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.CONTINUE, false));
        boolean result = dispatcher.dispatch(HookEvent.SESSION_START, Map.of(), def, "s1", 99L);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("No runner for handler type with ABORT policy returns false")
    void noRunner_abortPolicy_returnsFalse() {
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(); // no runners registered
        AgentDefinition def = agentWithHook(HookEvent.USER_PROMPT_SUBMIT,
                entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.ABORT, false));
        boolean keepGoing = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isFalse();
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).getError()).isEqualTo("runner_not_implemented");
    }

    @Test
    @DisplayName("No runner with CONTINUE policy returns true")
    void noRunner_continuePolicy_returnsTrue() {
        LifecycleHookDispatcherImpl dispatcher = newDispatcher();
        AgentDefinition def = agentWithHook(HookEvent.POST_TOOL_USE,
                entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.CONTINUE, false));
        boolean keepGoing = dispatcher.dispatch(HookEvent.POST_TOOL_USE, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isTrue();
    }

    @Test
    @DisplayName("Runner success returns true and records ok trace")
    void runnerSuccess_returnsTrue_andRecordsOkTrace() {
        StubRunner runner = new StubRunner(HookRunResult.ok("done", 0), 0, null);
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(runner);
        AgentDefinition def = agentWithHook(HookEvent.USER_PROMPT_SUBMIT,
                entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.CONTINUE, false));
        boolean keepGoing = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isTrue();
        assertThat(runner.calls.get()).isEqualTo(1);
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).isSuccess()).isTrue();
        assertThat(collected.get(0).getSpanType()).isEqualTo("LIFECYCLE_HOOK");
    }

    @Test
    @DisplayName("Runner failure + ABORT policy returns false")
    void runnerFailure_abortPolicy_returnsFalse() {
        StubRunner runner = new StubRunner(HookRunResult.failure("bad_input", 0), 0, null);
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(runner);
        AgentDefinition def = agentWithHook(HookEvent.USER_PROMPT_SUBMIT,
                entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.ABORT, false));
        boolean keepGoing = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isFalse();
    }

    @Test
    @DisplayName("Runner failure + CONTINUE policy returns true")
    void runnerFailure_continuePolicy_returnsTrue() {
        StubRunner runner = new StubRunner(HookRunResult.failure("bad_input", 0), 0, null);
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(runner);
        AgentDefinition def = agentWithHook(HookEvent.USER_PROMPT_SUBMIT,
                entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.CONTINUE, false));
        boolean keepGoing = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isTrue();
    }

    @Test
    @DisplayName("Runner throws exception + CONTINUE policy returns true with trace")
    void runnerThrows_continuePolicy_returnsTrue() {
        StubRunner runner = new StubRunner(null, 0, new RuntimeException("boom"));
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(runner);
        AgentDefinition def = agentWithHook(HookEvent.POST_TOOL_USE,
                entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.CONTINUE, false));
        boolean keepGoing = dispatcher.dispatch(HookEvent.POST_TOOL_USE, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isTrue();
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).getError()).startsWith("exception:");
    }

    @Test
    @DisplayName("Runner timeout + ABORT policy returns false")
    void runnerTimeout_abortPolicy_returnsFalse() {
        StubRunner runner = new StubRunner(HookRunResult.ok("done", 0), 5000, null);
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(runner);
        AgentDefinition def = agentWithHook(HookEvent.USER_PROMPT_SUBMIT,
                entry(new HookHandler.SkillHandler("X"), 1, FailurePolicy.ABORT, false));
        long t0 = System.currentTimeMillis();
        boolean keepGoing = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(keepGoing).isFalse();
        // Timed out around ~1s, not ~5s.
        assertThat(elapsed).isLessThan(3000L);
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).getError()).isEqualTo("timeout");
    }

    @Test
    @DisplayName("async=true returns true immediately without waiting for runner")
    void asyncMode_returnsImmediately() throws Exception {
        CountDownLatch releaseRunner = new CountDownLatch(1);
        StubRunner slowRunner = new StubRunner(HookRunResult.ok("done", 0), 0, null) {
            @Override
            public HookRunResult run(HookHandler.SkillHandler handler, Map<String, Object> input, HookExecutionContext ctx) {
                try {
                    releaseRunner.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                return super.run(handler, input, ctx);
            }
        };
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(slowRunner);
        AgentDefinition def = agentWithHook(HookEvent.SESSION_END,
                entry(new HookHandler.SkillHandler("X"), 10, FailurePolicy.CONTINUE, true));
        long t0 = System.currentTimeMillis();
        boolean keepGoing = dispatcher.dispatch(HookEvent.SESSION_END, Map.of(), def, "s1", 99L);
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(keepGoing).isTrue();
        // Should return in well under the 3-second stub wait because async skips .get()
        assertThat(elapsed).isLessThan(500L);
        releaseRunner.countDown();
    }

    @Test
    @DisplayName("Hook depth guard skips nested dispatch (second call inside runner is a no-op)")
    void depthGuard_skipsNestedHook() {
        // First runner tries to dispatch again from inside; the inner call MUST short-circuit.
        StubRunner innerCountingRunner = new StubRunner(HookRunResult.ok("inner", 0), 0, null);
        AtomicInteger outerInvocations = new AtomicInteger(0);

        // Build a dispatcher that has both a real outer dispatcher reference (via a wrapper).
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(innerCountingRunner);
        AgentDefinition def = agentWithHook(HookEvent.USER_PROMPT_SUBMIT,
                entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.CONTINUE, false));
        // Overwrite the runner with one that recurses:
        HandlerRunner<HookHandler.SkillHandler> recursiveRunner = new HandlerRunner<>() {
            @Override
            public Class<HookHandler.SkillHandler> handlerType() { return HookHandler.SkillHandler.class; }
            @Override
            public HookRunResult run(HookHandler.SkillHandler handler, Map<String, Object> input, HookExecutionContext ctx) {
                outerInvocations.incrementAndGet();
                // recurse: must be skipped
                dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
                return HookRunResult.ok("outer", 0);
            }
        };
        LifecycleHookDispatcherImpl dispatcher2 = newDispatcher(recursiveRunner);
        // Point recursion at dispatcher2, not dispatcher:
        HandlerRunner<HookHandler.SkillHandler> recursiveRunner2 = new HandlerRunner<>() {
            @Override
            public Class<HookHandler.SkillHandler> handlerType() { return HookHandler.SkillHandler.class; }
            @Override
            public HookRunResult run(HookHandler.SkillHandler handler, Map<String, Object> input, HookExecutionContext ctx) {
                outerInvocations.incrementAndGet();
                dispatcher2.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
                return HookRunResult.ok("outer", 0);
            }
        };
        LifecycleHookDispatcherImpl d = new LifecycleHookDispatcherImpl(
                List.of(recursiveRunner2), executor, traceCollector);
        boolean keepGoing = d.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isTrue();
        // The outer dispatch runs the runner once; the inner recursive dispatch is
        // short-circuited by the depth guard before calling the runner again.
        assertThat(outerInvocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("SkillHandlerRunner returns skill_not_found when skill missing")
    void skillHandlerRunner_skillMissing_returnsFailure() {
        SkillRegistry registry = new SkillRegistry();
        SkillHandlerRunner runner = new SkillHandlerRunner(registry);
        HookHandler.SkillHandler handler = new HookHandler.SkillHandler("Ghost");
        HookExecutionContext ctx = new HookExecutionContext("s1", 99L, HookEvent.SESSION_START, Map.of());
        HookRunResult r = runner.run(handler, Map.of(), ctx);
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).startsWith("skill_not_found");
    }

    // ---------- P1 tests ----------

    /** Helper: build a dispatcher with the given forbidden-skill list. */
    private LifecycleHookDispatcherImpl newDispatcherWithForbidden(List<String> forbidden, HandlerRunner<?>... runners) {
        return new LifecycleHookDispatcherImpl(List.of(runners), executor, traceCollector, forbidden);
    }

    /** Helper: agent with multiple entries for a single event, in order. */
    private AgentDefinition agentWithEntries(HookEvent event, List<HookEntry> entries) {
        AgentDefinition def = new AgentDefinition();
        def.setId("42");
        def.setName("TestAgent");
        LifecycleHooksConfig cfg = new LifecycleHooksConfig();
        cfg.putEntries(event, entries);
        def.setLifecycleHooks(cfg);
        return def;
    }

    @Test
    @DisplayName("Multi-entry CONTINUE runs every entry in order (both synchronous)")
    void multiEntry_continue_runsAll() {
        // Two entries routed to a single counting runner (dispatcher indexes runners by handlerType()).
        AtomicInteger counter = new AtomicInteger();
        HandlerRunner<HookHandler.SkillHandler> counting = new HandlerRunner<>() {
            @Override public Class<HookHandler.SkillHandler> handlerType() { return HookHandler.SkillHandler.class; }
            @Override public HookRunResult run(HookHandler.SkillHandler h, Map<String, Object> in, HookExecutionContext c) {
                counter.incrementAndGet();
                return HookRunResult.ok("call-" + counter.get(), 0);
            }
        };
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(counting);
        HookEntry e1 = entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.CONTINUE, false);
        HookEntry e2 = entry(new HookHandler.SkillHandler("Y"), 5, FailurePolicy.CONTINUE, false);
        AgentDefinition def = agentWithEntries(HookEvent.POST_TOOL_USE, List.of(e1, e2));

        boolean keepGoing = dispatcher.dispatch(HookEvent.POST_TOOL_USE, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isTrue();
        assertThat(counter.get()).isEqualTo(2);
        assertThat(collected).hasSize(2);
    }

    @Test
    @DisplayName("ABORT at entry index 1 prevents entry index 2 from running (dispatcher returns false)")
    void multiEntry_abortMidway_stopsSubsequent() {
        AtomicInteger callIndex = new AtomicInteger(-1);
        HandlerRunner<HookHandler.SkillHandler> runner = new HandlerRunner<>() {
            @Override public Class<HookHandler.SkillHandler> handlerType() { return HookHandler.SkillHandler.class; }
            @Override public HookRunResult run(HookHandler.SkillHandler h, Map<String, Object> in, HookExecutionContext c) {
                int idx = callIndex.incrementAndGet();
                // index 1 fails; index 0 succeeds
                if (idx == 1) return HookRunResult.failure("boom", 0);
                return HookRunResult.ok("ok-" + idx, 0);
            }
        };
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(runner);
        HookEntry e0 = entry(new HookHandler.SkillHandler("A"), 5, FailurePolicy.CONTINUE, false);
        HookEntry e1 = entry(new HookHandler.SkillHandler("B"), 5, FailurePolicy.ABORT, false);
        HookEntry e2 = entry(new HookHandler.SkillHandler("C"), 5, FailurePolicy.CONTINUE, false);
        AgentDefinition def = agentWithEntries(HookEvent.USER_PROMPT_SUBMIT, List.of(e0, e1, e2));

        boolean keepGoing = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isFalse();
        // The third entry (index 2) must NOT have run.
        assertThat(callIndex.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("SKIP_CHAIN stops the chain but returns true (main flow continues)")
    void multiEntry_skipChain_stopsButKeepsGoing() {
        AtomicInteger invocations = new AtomicInteger();
        HandlerRunner<HookHandler.SkillHandler> runner = new HandlerRunner<>() {
            @Override public Class<HookHandler.SkillHandler> handlerType() { return HookHandler.SkillHandler.class; }
            @Override public HookRunResult run(HookHandler.SkillHandler h, Map<String, Object> in, HookExecutionContext c) {
                int n = invocations.incrementAndGet();
                return n == 1 ? HookRunResult.failure("skip-me", 0) : HookRunResult.ok("should-not-run", 0);
            }
        };
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(runner);
        HookEntry e0 = entry(new HookHandler.SkillHandler("A"), 5, FailurePolicy.SKIP_CHAIN, false);
        HookEntry e1 = entry(new HookHandler.SkillHandler("B"), 5, FailurePolicy.CONTINUE, false);
        AgentDefinition def = agentWithEntries(HookEvent.POST_TOOL_USE, List.of(e0, e1));

        boolean keepGoing = dispatcher.dispatch(HookEvent.POST_TOOL_USE, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isTrue();
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Forbidden skill short-circuits with forbidden_skill error — runner is never called")
    void forbiddenSkill_neverInvokesRunner() {
        AtomicInteger calls = new AtomicInteger();
        HandlerRunner<HookHandler.SkillHandler> runner = new HandlerRunner<>() {
            @Override public Class<HookHandler.SkillHandler> handlerType() { return HookHandler.SkillHandler.class; }
            @Override public HookRunResult run(HookHandler.SkillHandler h, Map<String, Object> in, HookExecutionContext c) {
                calls.incrementAndGet();
                return HookRunResult.ok("should-not-run", 0);
            }
        };
        LifecycleHookDispatcherImpl dispatcher = newDispatcherWithForbidden(List.of("TeamCreate"), runner);
        HookEntry e = entry(new HookHandler.SkillHandler("TeamCreate"), 5, FailurePolicy.ABORT, false);
        AgentDefinition def = agentWithEntries(HookEvent.USER_PROMPT_SUBMIT, List.of(e));

        boolean keepGoing = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isFalse(); // ABORT policy → false
        assertThat(calls.get()).isEqualTo(0);
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).getError()).startsWith("forbidden_skill");
    }

    @Test
    @DisplayName("async entry with SKIP_CHAIN policy (legacy data) runtime-downgrades to CONTINUE and logs policy_overridden_async")
    void asyncWithSkipChain_downgradesAtRuntime() throws Exception {
        CountDownLatch releaseRunner = new CountDownLatch(1);
        StubRunner slow = new StubRunner(HookRunResult.ok("done", 0), 0, null) {
            @Override
            public HookRunResult run(HookHandler.SkillHandler handler, Map<String, Object> input, HookExecutionContext ctx) {
                try { releaseRunner.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                return super.run(handler, input, ctx);
            }
        };
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(slow);
        HookEntry e = entry(new HookHandler.SkillHandler("X"), 5, FailurePolicy.SKIP_CHAIN, true);
        AgentDefinition def = agentWithEntries(HookEvent.POST_TOOL_USE, List.of(e));

        // Capture logs to assert the policy_overridden_async warning is emitted.
        Logger dispatcherLogger = (Logger) LoggerFactory.getLogger(LifecycleHookDispatcherImpl.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        dispatcherLogger.addAppender(logCapture);
        try {
            long t0 = System.currentTimeMillis();
            boolean keepGoing = dispatcher.dispatch(HookEvent.POST_TOOL_USE, Map.of(), def, "s1", 99L);
            long elapsed = System.currentTimeMillis() - t0;
            // chainDecision is CONTINUE (downgraded from SKIP_CHAIN), so dispatch returns true.
            assertThat(keepGoing).isTrue();
            // async path returns before the runner finishes — should be well under 500ms.
            assertThat(elapsed).isLessThan(500L);
            // Warn log must record the downgrade event.
            assertThat(logCapture.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("policy_overridden_async"));
        } finally {
            dispatcherLogger.detachAppender(logCapture);
            releaseRunner.countDown();
        }
    }

    @Test
    @DisplayName("Two async entries run concurrently — hookDepth is isolated per executor thread")
    void twoAsyncEntries_runConcurrently() throws Exception {
        CountDownLatch insideRunner = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        StubRunner concurrent = new StubRunner(HookRunResult.ok("ok", 0), 0, null) {
            @Override
            public HookRunResult run(HookHandler.SkillHandler handler, Map<String, Object> input, HookExecutionContext ctx) {
                insideRunner.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                return super.run(handler, input, ctx);
            }
        };
        LifecycleHookDispatcherImpl dispatcher = newDispatcher(concurrent);
        HookEntry e0 = entry(new HookHandler.SkillHandler("A"), 5, FailurePolicy.CONTINUE, true);
        HookEntry e1 = entry(new HookHandler.SkillHandler("B"), 5, FailurePolicy.CONTINUE, true);
        AgentDefinition def = agentWithEntries(HookEvent.SESSION_END, List.of(e0, e1));

        boolean keepGoing = dispatcher.dispatch(HookEvent.SESSION_END, Map.of(), def, "s1", 99L);
        assertThat(keepGoing).isTrue();
        // Both async runners must enter the runner body despite hookDepth ThreadLocal.
        boolean bothEntered = insideRunner.await(5, TimeUnit.SECONDS);
        assertThat(bothEntered).as("two async entries should run concurrently — hookDepth must not block the second").isTrue();
        release.countDown();
    }
}
