package com.skillforge.server.improve;

import com.skillforge.server.improve.surface.OptimizableSurface;
import com.skillforge.server.improve.surface.SandboxContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.2 — unit test for the
 * {@link AbstractAbEvalRunner} template-method contract.
 *
 * <p>Verifies the 5-step hook ordering ratified in tech-design §3.1
 * (#3 — locked) using a string-tracing fake subclass + stub surface. The
 * fake records hook entries in an ordered list so the test asserts the
 * ratified hook order is preserved.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Happy-path hook ordering: inject(baseline) → runEvalSet(baseline) →
 *       inject(candidate) → runEvalSet(candidate) → judgeAndCompare →
 *       shouldPromote → promoteIfNeeded (when gate passes)</li>
 *   <li>shouldPromote=false short-circuits: promoteIfNeeded NOT called</li>
 *   <li>Null-guard semantics: abRunId / baseline / candidate / ctx</li>
 *   <li>AbRunResult shape echoes the template's internal records</li>
 * </ul>
 */
@DisplayName("AbstractAbEvalRunner template method")
class AbstractAbEvalRunnerTest {

    @Test
    @DisplayName("happy path: hooks called in spec ratify §3 order (4 hooks + injected EvalService), promote=true")
    void run_happyPath_hookOrderMatchesSpec() {
        List<String> trace = new ArrayList<>();
        TracingSurface surface = new TracingSurface(trace);
        TracingEvalService evalService = new TracingEvalService(trace);
        TracingRunner runner = new TracingRunner(surface, evalService, trace, /* promoteGate */ true);

        SandboxContext ctx = new SandboxContext(42L, "session-1", null);
        AbstractAbEvalRunner.AbRunResult result = runner.run("ab-1", "BASELINE", "CANDIDATE", ctx);

        // Ratified hook order — ANY deviation here is a spec violation.
        // Note: runEvalSet is delegated to injected EvalService (not abstract,
        // see Phase 1.2 reviewer-r1 fix preserving ratify #3 4-hook count).
        assertThat(trace).containsExactly(
                "inject:BASELINE",
                "evalService.run:BASELINE",
                "inject:CANDIDATE",
                "evalService.run:CANDIDATE",
                "judgeAndCompare:50.0,75.0",
                "shouldPromote:25.0",
                "promoteIfNeeded:CANDIDATE,25.0");
        assertThat(result.abRunId()).isEqualTo("ab-1");
        assertThat(result.promoted()).isTrue();
        assertThat(result.comparison().delta()).isEqualTo(25.0);
        assertThat(result.baselineRun().passRate()).isEqualTo(50.0);
        assertThat(result.candidateRun().passRate()).isEqualTo(75.0);
    }

    @Test
    @DisplayName("shouldPromote=false short-circuits: promoteIfNeeded NOT called, AbRunResult.promoted=false")
    void run_shouldPromoteFalse_shortCircuitsPromote() {
        List<String> trace = new ArrayList<>();
        TracingSurface surface = new TracingSurface(trace);
        TracingEvalService evalService = new TracingEvalService(trace);
        TracingRunner runner = new TracingRunner(surface, evalService, trace, /* promoteGate */ false);

        SandboxContext ctx = new SandboxContext(42L, "session-1", null);
        AbstractAbEvalRunner.AbRunResult result = runner.run("ab-2", "BASELINE", "CANDIDATE", ctx);

        // promoteIfNeeded MUST be absent — gate said no.
        assertThat(trace).containsExactly(
                "inject:BASELINE",
                "evalService.run:BASELINE",
                "inject:CANDIDATE",
                "evalService.run:CANDIDATE",
                "judgeAndCompare:50.0,75.0",
                "shouldPromote:25.0");
        assertThat(trace).doesNotContain("promoteIfNeeded:CANDIDATE,25.0");
        assertThat(result.promoted()).isFalse();
        assertThat(result.comparison().delta()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("null-guard: abRunId / baseline / candidate / ctx all rejected pre-hook")
    void run_nullArgs_rejectedBeforeAnyHook() {
        List<String> trace = new ArrayList<>();
        TracingSurface surface = new TracingSurface(trace);
        TracingEvalService evalService = new TracingEvalService(trace);
        TracingRunner runner = new TracingRunner(surface, evalService, trace, true);
        SandboxContext ctx = new SandboxContext(42L, "session-1", null);

        assertThatThrownBy(() -> runner.run(null, "B", "C", ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abRunId");
        assertThatThrownBy(() -> runner.run("", "B", "C", ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abRunId");
        assertThatThrownBy(() -> runner.run("ab", null, "C", ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseline and candidate");
        assertThatThrownBy(() -> runner.run("ab", "B", null, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseline and candidate");
        assertThatThrownBy(() -> runner.run("ab", "B", "C", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SandboxContext");

        // No hooks should have fired on any of the rejection paths.
        assertThat(trace).isEmpty();
    }

    @Test
    @DisplayName("constructor rejects null surface and null evalService (defense against accidental null injection)")
    void constructor_nullArgs_throws() {
        List<String> trace = new ArrayList<>();
        TracingSurface surface = new TracingSurface(trace);
        TracingEvalService evalService = new TracingEvalService(trace);
        // null surface
        assertThatThrownBy(() -> new TracingRunner(null, evalService, trace, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surface must not be null");
        // null evalService (Phase 1.2 reviewer-r1 fix added this validation)
        assertThatThrownBy(() -> new TracingRunner(surface, null, trace, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evalService must not be null");
    }

    // ─────── Test doubles ───────

    /** Stub surface that records every {@code injectForSandbox} into the shared trace list. */
    private static class TracingSurface implements OptimizableSurface<String> {
        private final List<String> trace;
        TracingSurface(List<String> trace) { this.trace = trace; }
        @Override public String surfaceType() { return "test"; }
        @Override public String loadActive(Long agentId) { throw new UnsupportedOperationException(); }
        @Override public String loadVersion(String versionId) { throw new UnsupportedOperationException(); }
        @Override public String createCandidate(String baseline, String improvementContext) {
            throw new UnsupportedOperationException();
        }
        @Override
        public void injectForSandbox(SandboxContext ctx, String version) {
            trace.add("inject:" + version);
        }
        @Override public void promote(String candidate) { throw new UnsupportedOperationException(); }
        @Override public void rollback(String candidate) { throw new UnsupportedOperationException(); }
    }

    /**
     * Stub {@link EvalService} that returns deterministic per-side rates
     * (baseline=50.0, candidate=75.0) so judgeAndCompare produces a stable
     * Comparison(50, 75, 25). Records every invocation into the shared trace.
     */
    private static class TracingEvalService implements EvalService<String> {
        private final List<String> trace;
        TracingEvalService(List<String> trace) { this.trace = trace; }
        @Override
        public AbstractAbEvalRunner.EvalRun run(SandboxContext ctx, String version) {
            trace.add("evalService.run:" + version);
            double rate = "BASELINE".equals(version) ? 50.0 : 75.0;
            return new AbstractAbEvalRunner.EvalRun("er-" + version, rate, 0);
        }
    }

    /**
     * Tracing subclass — records the 3 abstract hooks here (judgeAndCompare
     * / shouldPromote / promoteIfNeeded). Phase 1.2 reviewer-r1 fix: no
     * {@code runEvalSet} override (eval delegated to injected EvalService).
     */
    private static class TracingRunner extends AbstractAbEvalRunner<String> {
        private final List<String> trace;
        private final boolean promoteGate;

        TracingRunner(OptimizableSurface<String> surface, EvalService<String> evalService,
                      List<String> trace, boolean promoteGate) {
            super(surface, evalService);
            this.trace = trace;
            this.promoteGate = promoteGate;
        }

        @Override
        protected Comparison judgeAndCompare(EvalRun baseline, EvalRun candidate) {
            trace.add("judgeAndCompare:" + baseline.passRate() + "," + candidate.passRate());
            return new Comparison(baseline.passRate(), candidate.passRate(),
                    candidate.passRate() - baseline.passRate());
        }

        @Override
        protected boolean shouldPromote(Comparison comparison) {
            trace.add("shouldPromote:" + comparison.delta());
            return promoteGate;
        }

        @Override
        protected void promoteIfNeeded(String candidate, Comparison comparison) {
            trace.add("promoteIfNeeded:" + candidate + "," + comparison.delta());
        }
    }
}
