package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * V4 MULTI-SURFACE-FLYWHEEL Phase 1.3 — unit tests for the generic 4-arg
 * {@link CanaryAllocator#allocate(String, Long, String, String)} entry point
 * and the surface_type-aware annotation-value discrimination path.
 *
 * <p>The V2 3-arg entry point {@link CanaryAllocator#allocate(String, Long, String)}
 * coverage stays in {@link CanaryAllocatorTest} (zero-behavior-drift contract —
 * those tests must remain green untouched).
 *
 * <p>Coverage matrix here is focused on the NEW behaviors only:
 * <ol>
 *   <li>4-arg with surface={@code behavior_rule} hits the new
 *       {@code findActiveCanaryByAgentSurfaceBaseline} JPQL path.</li>
 *   <li>4-arg with surface={@code skill} matches V2 algorithm but persists
 *       the same {@code annotation_value} prefix as V2 ({@code "skill:<id>"}).</li>
 *   <li>Cross-surface isolation: a session's existing {@code "skill:..."}
 *       annotation does NOT pin its behavior_rule allocation (the surface
 *       prefix discriminator works).</li>
 *   <li>Defensive: null surface short-circuits to baseline.</li>
 *   <li>Defensive: null baselineIdentity returns null.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanaryAllocator — generic 4-arg surface-aware allocate (V4 Phase 1.3)")
class CanaryAllocatorGenericTest {

    @Mock private CanaryRolloutRepository canaryRepository;
    @Mock private SessionAnnotationRepository sessionAnnotationRepository;

    private CanaryAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new CanaryAllocator(canaryRepository, sessionAnnotationRepository);
    }

    private CanaryRolloutEntity activeCanaryFor(String surfaceType, int pct,
                                                String baseline, String candidate) {
        CanaryRolloutEntity c = new CanaryRolloutEntity();
        c.setId(11L);
        c.setSurfaceType(surfaceType);
        c.setAgentId(42L);
        c.setBaselineSkillName(baseline);
        c.setCandidateSkillName(candidate);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_CANARY);
        c.setRolloutPercentage(pct);
        Instant now = Instant.now();
        c.setStartedAt(now);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    /** Same bucket-search helper as V2 CanaryAllocatorTest. */
    private String sessionIdInBucket(int pct, boolean inSlice) {
        for (int i = 0; i < 10_000; i++) {
            String s = "sess-gen-" + i;
            int bucket = (s.hashCode() & 0x7FFFFFFF) % 100;
            if (inSlice && bucket < pct) return s;
            if (!inSlice && bucket >= pct) return s;
        }
        throw new IllegalStateException("no sessionId for pct=" + pct + " inSlice=" + inSlice);
    }

    @Test
    @DisplayName("4-arg behavior_rule: no active canary → baseline identity returned, no annotation write")
    void allocate_behaviorRule_noActiveCanary_returnsBaseline() {
        when(canaryRepository.findActiveCanaryByAgentSurfaceBaseline(
                42L, "behavior_rule", "br-baseline-v1"))
                .thenReturn(Optional.empty());

        String result = allocator.allocate("sess-001", 42L, "behavior_rule", "br-baseline-v1");

        assertThat(result).isEqualTo("br-baseline-v1");
        verify(canaryRepository).findActiveCanaryByAgentSurfaceBaseline(
                42L, "behavior_rule", "br-baseline-v1");
        // Never hit the legacy skill-only finder.
        verify(canaryRepository, never()).findActiveCanaryForSkill(any(), anyString());
        verifyNoInteractions(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("4-arg behavior_rule: pct=100 → candidate identity returned (defensive short-circuit)")
    void allocate_behaviorRule_pct100_returnsCandidate() {
        when(canaryRepository.findActiveCanaryByAgentSurfaceBaseline(
                42L, "behavior_rule", "br-baseline-v1"))
                .thenReturn(Optional.of(activeCanaryFor(
                        "behavior_rule", 100, "br-baseline-v1", "br-candidate-v2")));

        String result = allocator.allocate("sess-001", 42L, "behavior_rule", "br-baseline-v1");

        assertThat(result).isEqualTo("br-candidate-v2");
        // pct=100 short-circuit means no session-pin lookup nor persist.
        verifyNoInteractions(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("4-arg behavior_rule: fresh hash into candidate slice → candidate + annotation persisted "
            + "with surface-prefixed value 'behavior_rule:<id>'")
    void allocate_behaviorRule_freshCandidate_writesSurfacePrefixedAnnotation() {
        when(canaryRepository.findActiveCanaryByAgentSurfaceBaseline(
                42L, "behavior_rule", "br-baseline-v1"))
                .thenReturn(Optional.of(activeCanaryFor(
                        "behavior_rule", 50, "br-baseline-v1", "br-candidate-v2")));
        String sessionId = sessionIdInBucket(50, /*inSlice*/ true);
        when(sessionAnnotationRepository.findCanaryGroup(sessionId, "behavior_rule"))
                .thenReturn(Optional.empty());

        String result = allocator.allocate(sessionId, 42L, "behavior_rule", "br-baseline-v1");

        assertThat(result).isEqualTo("br-candidate-v2");
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(sessionAnnotationRepository).upsertSkipDuplicate(
                eq(sessionId),
                eq("canary_group"),
                valueCap.capture(),
                eq("system"),
                eq(BigDecimal.ONE),
                eq(null));
        assertThat(valueCap.getValue()).isEqualTo("behavior_rule:br-candidate-v2");
    }

    @Test
    @DisplayName("4-arg behavior_rule: previously pinned to 'behavior_rule:<id>' → honours prior pin, no re-hash")
    void allocate_behaviorRule_existingPin_honoursPrior() {
        when(canaryRepository.findActiveCanaryByAgentSurfaceBaseline(
                42L, "behavior_rule", "br-baseline-v1"))
                .thenReturn(Optional.of(activeCanaryFor(
                        "behavior_rule", 50, "br-baseline-v1", "br-candidate-v2")));
        when(sessionAnnotationRepository.findCanaryGroup("sess-pinned-br", "behavior_rule"))
                .thenReturn(Optional.of("behavior_rule:br-pinned-version"));

        String result = allocator.allocate("sess-pinned-br", 42L, "behavior_rule", "br-baseline-v1");

        assertThat(result).isEqualTo("br-pinned-version");
        verify(sessionAnnotationRepository, never()).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("4-arg skill: same algorithm as V2 3-arg → uses new finder + writes 'skill:<id>' annotation")
    void allocate_skillVia4Arg_matchesV2_annotation() {
        when(canaryRepository.findActiveCanaryByAgentSurfaceBaseline(
                42L, "skill", "my-skill"))
                .thenReturn(Optional.of(activeCanaryFor("skill", 50, "my-skill", "my-skill-v2")));
        String sessionId = sessionIdInBucket(50, /*inSlice*/ true);
        when(sessionAnnotationRepository.findCanaryGroup(sessionId, "skill"))
                .thenReturn(Optional.empty());

        String result = allocator.allocate(sessionId, 42L, "skill", "my-skill");

        assertThat(result).isEqualTo("my-skill-v2");
        verify(sessionAnnotationRepository).upsertSkipDuplicate(
                eq(sessionId), eq("canary_group"), eq("skill:my-skill-v2"),
                eq("system"), eq(BigDecimal.ONE), eq(null));
    }

    @Test
    @DisplayName("Cross-surface isolation: existing skill pin does NOT influence behavior_rule allocation "
            + "(separate findCanaryGroup queries scoped by surface prefix)")
    void allocate_crossSurfaceIsolation_skillPinIgnoredForBehaviorRule() {
        when(canaryRepository.findActiveCanaryByAgentSurfaceBaseline(
                42L, "behavior_rule", "br-baseline"))
                .thenReturn(Optional.of(activeCanaryFor(
                        "behavior_rule", 50, "br-baseline", "br-cand")));
        // Same session has a skill pin, but the behavior_rule path filters by
        // surfaceType="behavior_rule" → empty match → fresh hash.
        when(sessionAnnotationRepository.findCanaryGroup("sess-multi", "behavior_rule"))
                .thenReturn(Optional.empty());

        String result = allocator.allocate("sess-multi", 42L, "behavior_rule", "br-baseline");

        // Result depends on hash; the assertion of interest is the surface-scoped query.
        verify(sessionAnnotationRepository).findCanaryGroup("sess-multi", "behavior_rule");
        // And NOT a skill-scoped lookup.
        verify(sessionAnnotationRepository, never()).findCanaryGroup("sess-multi", "skill");
        assertThat(result).isIn("br-baseline", "br-cand");  // either is valid depending on bucket
    }

    @Test
    @DisplayName("4-arg: null surfaceType degrades to baseline (defensive)")
    void allocate_nullSurface_returnsBaseline() {
        String result = allocator.allocate("sess-001", 42L, /*surfaceType*/ null, "ident-1");
        assertThat(result).isEqualTo("ident-1");
        verifyNoInteractions(canaryRepository);
        verifyNoInteractions(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("4-arg: blank surfaceType degrades to baseline (defensive)")
    void allocate_blankSurface_returnsBaseline() {
        String result = allocator.allocate("sess-001", 42L, "  ", "ident-1");
        assertThat(result).isEqualTo("ident-1");
        verifyNoInteractions(canaryRepository);
    }

    @Test
    @DisplayName("4-arg: null baselineIdentity returns null (matches V2 null-baseline contract)")
    void allocate_nullBaseline_returnsNull() {
        String result = allocator.allocate("sess-001", 42L, "behavior_rule", /*baseline*/ null);
        assertThat(result).isNull();
        verifyNoInteractions(canaryRepository);
    }

    @Test
    @DisplayName("4-arg: null sessionId / null agentId short-circuits to baseline (matches V2 contract)")
    void allocate_nullSessionOrAgent_returnsBaseline() {
        assertThat(allocator.allocate(/*sessionId*/ null, 42L, "behavior_rule", "br-v1"))
                .isEqualTo("br-v1");
        assertThat(allocator.allocate("sess-001", /*agentId*/ null, "behavior_rule", "br-v1"))
                .isEqualTo("br-v1");
        verifyNoInteractions(canaryRepository);
    }

    @Test
    @DisplayName("3-arg deprecated wrapper still uses V2 skill-only finder (existing test mocks stay green)")
    void allocate_3argLegacy_usesSkillOnlyFinder() {
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.empty());

        String result = allocator.allocate("sess-001", 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill");
        verify(canaryRepository).findActiveCanaryForSkill(42L, "my-skill");
        // The new generic finder must NOT be touched by the 3-arg path.
        verify(canaryRepository, never()).findActiveCanaryByAgentSurfaceBaseline(
                any(), anyString(), anyString());
    }
}
