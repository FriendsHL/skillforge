package com.skillforge.server.improve.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — unit test for {@link SurfaceRegistry}.
 *
 * <p>Uses lightweight stub surfaces (not Mockito mocks) — the registry's
 * contract is purely "dispatch by string", which is faster to verify with
 * no-arg POJO stubs than mock proxies.
 */
@DisplayName("SurfaceRegistry")
class OptimizableSurfaceRegistryTest {

    @Test
    @DisplayName("registers all three known surfaces by surfaceType()")
    void registry_registersThreeSurfaces() {
        SurfaceRegistry registry = new SurfaceRegistry(List.of(
                new StubSurface<>("skill"),
                new StubSurface<>("prompt"),
                new StubSurface<>("behavior_rule")));

        assertThat(registry.knownSurfaceTypes())
                .containsExactlyInAnyOrder("skill", "prompt", "behavior_rule");

        // Lookup hits all three.
        OptimizableSurface<Object> skill = registry.get("skill");
        OptimizableSurface<Object> prompt = registry.get("prompt");
        OptimizableSurface<Object> behaviorRule = registry.get("behavior_rule");
        assertThat(skill.surfaceType()).isEqualTo("skill");
        assertThat(prompt.surfaceType()).isEqualTo("prompt");
        assertThat(behaviorRule.surfaceType()).isEqualTo("behavior_rule");
        // Distinct instances.
        assertThat(skill).isNotSameAs(prompt);
        assertThat(prompt).isNotSameAs(behaviorRule);
    }

    @Test
    @DisplayName("get(unknown) throws IllegalArgumentException with known-surface diagnostic")
    void registry_unknownSurfaceThrows() {
        SurfaceRegistry registry = new SurfaceRegistry(List.of(
                new StubSurface<>("skill"),
                new StubSurface<>("behavior_rule")));

        assertThatThrownBy(() -> registry.get("lifecycle_hook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown surface type: 'lifecycle_hook'")
                .hasMessageContaining("skill")
                .hasMessageContaining("behavior_rule");
    }

    @Test
    @DisplayName("duplicate surfaceType() registration is detected at construction time")
    void registry_duplicateSurfaceTypeRejected() {
        // Defensive against future copy/paste bugs (e.g. two surfaces both
        // returning "skill"). Throw at startup, not at first dispatch.
        assertThatThrownBy(() -> new SurfaceRegistry(List.of(
                new StubSurface<>("skill"),
                new StubSurface<>("skill"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate OptimizableSurface registration")
                .hasMessageContaining("surfaceType=skill");
    }

    /** Minimal no-behavior surface — every hook throws if invoked. Only surfaceType() matters. */
    private static class StubSurface<V> implements OptimizableSurface<V> {
        private final String type;
        StubSurface(String type) { this.type = type; }
        @Override public String surfaceType() { return type; }
        @Override public V loadActive(Long agentId) { throw new UnsupportedOperationException(); }
        @Override public V loadVersion(String versionId) { throw new UnsupportedOperationException(); }
        @Override public V createCandidate(V baseline, String improvementContext) { throw new UnsupportedOperationException(); }
        @Override public void injectForSandbox(SandboxContext ctx, V version) { throw new UnsupportedOperationException(); }
        @Override public void promote(V candidate) { throw new UnsupportedOperationException(); }
        @Override public void rollback(V candidate) { throw new UnsupportedOperationException(); }
    }
}
