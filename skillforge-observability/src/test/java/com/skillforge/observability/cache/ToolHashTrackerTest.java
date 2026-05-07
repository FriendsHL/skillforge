package com.skillforge.observability.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROMPT-CACHE-MVP Phase 1 — INV-3 unit tests for {@link ToolHashTracker}.
 *
 * <p>Verifies drift-detection counter rather than the warn log itself (LogCaptor would
 * pull in another dep just for one assertion). The `track` return value (drift count)
 * is exposed for this purpose.
 */
@DisplayName("ToolHashTracker — per-session drift detection (Phase 1 / INV-3)")
class ToolHashTrackerTest {

    private final ObjectMapper m = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("first call primes the tracker — returns 0 drift")
    void firstCallNoDrift() {
        ToolHashTracker tracker = new ToolHashTracker(m);
        ToolSchema bash = new ToolSchema("Bash", "Run shell", Map.of());
        assertThat(tracker.track("s1", List.of(bash))).isZero();
    }

    @Test
    @DisplayName("identical second call returns 0 drift")
    void identicalReturnsZero() {
        ToolHashTracker tracker = new ToolHashTracker(m);
        ToolSchema bash = new ToolSchema("Bash", "Run shell", Map.of());
        tracker.track("s1", List.of(bash));
        assertThat(tracker.track("s1", List.of(bash))).isZero();
    }

    @Test
    @DisplayName("description edit triggers drift = 1")
    void descriptionEditTriggersDrift() {
        ToolHashTracker tracker = new ToolHashTracker(m);
        ToolSchema bashV1 = new ToolSchema("Bash", "Run shell commands", Map.of());
        ToolSchema bashV2 = new ToolSchema("Bash", "Run shell commands SAFELY", Map.of());

        tracker.track("s1", List.of(bashV1));
        assertThat(tracker.track("s1", List.of(bashV2))).isEqualTo(1);
    }

    @Test
    @DisplayName("schema edit triggers drift")
    void schemaEditTriggersDrift() {
        ToolHashTracker tracker = new ToolHashTracker(m);
        ToolSchema readV1 = new ToolSchema("Read", "Read file",
                Map.of("type", "object"));
        ToolSchema readV2 = new ToolSchema("Read", "Read file",
                Map.of("type", "object", "required", List.of("path")));

        tracker.track("s1", List.of(readV1));
        assertThat(tracker.track("s1", List.of(readV2))).isEqualTo(1);
    }

    @Test
    @DisplayName("two sessions are tracked independently")
    void perSessionIndependent() {
        ToolHashTracker tracker = new ToolHashTracker(m);
        ToolSchema bashV1 = new ToolSchema("Bash", "v1", Map.of());
        ToolSchema bashV2 = new ToolSchema("Bash", "v2", Map.of());

        tracker.track("s1", List.of(bashV1));
        // s2's first call is fresh — no drift even though s1 has v1 cached
        assertThat(tracker.track("s2", List.of(bashV2))).isZero();
        // s1 sees the drift on its OWN second call
        assertThat(tracker.track("s1", List.of(bashV2))).isEqualTo(1);
    }

    @Test
    @DisplayName("null/blank inputs are no-ops (no NPE, no false drift)")
    void nullSafeNoOps() {
        ToolHashTracker tracker = new ToolHashTracker(m);
        assertThat(tracker.track(null, List.of())).isZero();
        assertThat(tracker.track("", List.of())).isZero();
        assertThat(tracker.track("s1", null)).isZero();
        assertThat(tracker.track("s1", List.of())).isZero();
    }
}
