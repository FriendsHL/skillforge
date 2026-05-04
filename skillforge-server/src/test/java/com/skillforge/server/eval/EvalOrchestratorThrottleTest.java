package com.skillforge.server.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-V2 M1 (W6 fix): pure-logic unit test locking the throttle math used
 * by {@link EvalOrchestrator}. The orchestrator itself wires LLM/JPA/WS so a
 * full integration test would be overkill — these tests just pin the static
 * stride/should-push helpers so future refactors don't silently change the
 * cadence (or skip the final-case push).
 */
class EvalOrchestratorThrottleTest {

    @Nested
    @DisplayName("computeProgressStride")
    class StrideMath {

        @ParameterizedTest(name = "totalCount={0} → stride={1}")
        @CsvSource({
                // Boundary at 50: ≤50 pushes per-case
                "1,    1",
                "10,   1",
                "50,   1",
                // Just above the boundary
                "51,   2",   // 51 / 20 = 2 (int div)
                "60,   3",
                "100,  5",
                "200, 10",
                "1000, 50",
                // Edge case: 0 cases — stride formula returns 1 (guard via shouldPush)
                "0,    1",
        })
        void stride_acrossBoundary(int totalCount, int expectedStride) {
            assertThat(EvalOrchestrator.computeProgressStride(totalCount)).isEqualTo(expectedStride);
        }

        @Test
        @DisplayName("stride is never 0 for any positive totalCount (avoid mod-by-zero)")
        void stride_isAlwaysAtLeastOne() {
            for (int n = 1; n <= 5000; n++) {
                assertThat(EvalOrchestrator.computeProgressStride(n))
                        .as("stride for totalCount=%d", n)
                        .isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("shouldPushProgressEvent")
    class PushDecision {

        @Test
        @DisplayName("totalCount=1 (single-case edge): only index 0, must push")
        void singleCase_pushes() {
            int total = 1;
            int stride = EvalOrchestrator.computeProgressStride(total);
            assertThat(EvalOrchestrator.shouldPushProgressEvent(0, total, stride)).isTrue();
        }

        @Test
        @DisplayName("totalCount=50 (boundary lower): every index pushes")
        void total50_pushesEveryCase() {
            int total = 50;
            int stride = EvalOrchestrator.computeProgressStride(total);
            assertThat(stride).isEqualTo(1);
            for (int i = 0; i < total; i++) {
                assertThat(EvalOrchestrator.shouldPushProgressEvent(i, total, stride))
                        .as("index %d", i).isTrue();
            }
        }

        @Test
        @DisplayName("totalCount=51 (just past throttle): stride=2, first/even/last push, odd skip")
        void total51_oddIndicesSkip_finalAlwaysPushes() {
            int total = 51;
            int stride = EvalOrchestrator.computeProgressStride(total);
            assertThat(stride).isEqualTo(2);

            List<Integer> pushedIndices = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                if (EvalOrchestrator.shouldPushProgressEvent(i, total, stride)) {
                    pushedIndices.add(i);
                }
            }
            // Even indices 0,2,…,48 plus the final case at 50 (which is even
            // so already in the list; the "always push final" branch is a
            // no-op here but matters for total=51 with odd final).
            assertThat(pushedIndices).contains(0, 2, 48, 50);
            // Last index always pushed (regression for the always-push-final invariant)
            assertThat(EvalOrchestrator.shouldPushProgressEvent(total - 1, total, stride)).isTrue();
            // An odd intermediate index is skipped
            assertThat(EvalOrchestrator.shouldPushProgressEvent(1, total, stride)).isFalse();
            assertThat(EvalOrchestrator.shouldPushProgressEvent(49, total, stride)).isFalse();
        }

        @Test
        @DisplayName("totalCount=100: stride=5, indices {0,5,10,…,95,99} push (≈21 events vs 100)")
        void total100_strideFive() {
            int total = 100;
            int stride = EvalOrchestrator.computeProgressStride(total);
            assertThat(stride).isEqualTo(5);

            int pushedCount = 0;
            for (int i = 0; i < total; i++) {
                if (EvalOrchestrator.shouldPushProgressEvent(i, total, stride)) {
                    pushedCount++;
                }
            }
            // Expect 20 stride-aligned (0,5,…,95) + final (99) which is not
            // stride-aligned → 21 events.
            assertThat(pushedCount).isEqualTo(21);
            assertThat(EvalOrchestrator.shouldPushProgressEvent(99, total, stride)).isTrue();
            assertThat(EvalOrchestrator.shouldPushProgressEvent(98, total, stride)).isFalse();
        }

        @Test
        @DisplayName("totalCount=1000: stride=50, ≈21 events instead of 1000")
        void total1000_strideFifty() {
            int total = 1000;
            int stride = EvalOrchestrator.computeProgressStride(total);
            assertThat(stride).isEqualTo(50);

            int pushedCount = 0;
            for (int i = 0; i < total; i++) {
                if (EvalOrchestrator.shouldPushProgressEvent(i, total, stride)) {
                    pushedCount++;
                }
            }
            // 0,50,…,950 = 20 stride-aligned + final (999) which is not
            // stride-aligned → 21 events. Confirms WS spam reduction at scale.
            assertThat(pushedCount).isEqualTo(21);
            assertThat(EvalOrchestrator.shouldPushProgressEvent(0, total, stride)).isTrue();
            assertThat(EvalOrchestrator.shouldPushProgressEvent(999, total, stride)).isTrue();
        }

        @Test
        @DisplayName("zero total / zero stride degenerate cases never push")
        void degenerate_neverPushes() {
            assertThat(EvalOrchestrator.shouldPushProgressEvent(0, 0, 1)).isFalse();
            assertThat(EvalOrchestrator.shouldPushProgressEvent(0, 10, 0)).isFalse();
        }
    }
}
