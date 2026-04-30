package com.skillforge.server.skill;

import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-D — Tests for {@link SkillCatalogReconciler#resolveConflicts()} and the
 * per-row {@link SkillConflictResolver} REQUIRES_NEW isolation semantics.
 *
 * <p>Plan T5 / 准入条件覆盖:
 * <ul>
 *   <li>system 同名 → runtime 全部 shadowed via markShadowedBySystem</li>
 *   <li>runtime newest winner via created_at desc + id desc</li>
 *   <li>REQUIRES_NEW row isolation: one row's mutation throws, siblings still process</li>
 *   <li>4-tuple compat (is_system, owner_id, name, source) — different owners
 *       with same name do NOT match each other (verified via repository query
 *       call semantics — repository contract is JPA; this validates the
 *       reconciler passes the correct arguments)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillConflictResolutionTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillConflictResolver conflictResolver;
    @Mock private SkillPackageLoader packageLoader;
    @Mock private SkillForgeHomeResolver homeResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private SkillCatalogReconciler reconciler;

    @BeforeEach
    void setUp() {
        when(homeResolver.getRuntimeRoot()).thenReturn(java.nio.file.Path.of("/tmp/__sf-test"));
        reconciler = new SkillCatalogReconciler(
                homeResolver, skillRepository, packageLoader, conflictResolver);
        // EM is @PersistenceContext (field-injected). Use reflection in tests.
        ReflectionTestUtils.setField(reconciler, "entityManager", entityManager);
    }

    @Test
    @DisplayName("system + runtime same name → runtime row markShadowedBySystem")
    void systemNameWinsOverRuntime() {
        SkillEntity systemRow = makeRow(1L, "shared-name", true, true, null);
        SkillEntity runtimeRow = makeRow(2L, "shared-name", false, true, 99L);

        when(skillRepository.findAll()).thenReturn(List.of(systemRow, runtimeRow));

        RescanReport report = reconciler.resolveConflicts();

        verify(conflictResolver).markShadowedBySystem(eq(2L), eq("shared-name"));
        verify(conflictResolver, never()).markShadowedByRuntime(anyLong(), anyLong());
        assertThat(report.shadowed()).isEqualTo(1);
    }

    @Test
    @DisplayName("multiple runtime same-name → newest by created_at desc wins; others shadowed")
    void runtimeNewestWinner() {
        SkillEntity older = makeRow(10L, "dup", false, true, 1L);
        SkillEntity newer = makeRow(11L, "dup", false, true, 1L);

        when(skillRepository.findAll()).thenReturn(List.of(older, newer));
        // Native query returns newer (id=11) as the head.
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(new ArrayList<>(Arrays.asList(11L, 10L)));

        RescanReport report = reconciler.resolveConflicts();

        verify(conflictResolver).markShadowedByRuntime(eq(10L), eq(11L));
        // Older was enabled → should also count as a disabled duplicate
        assertThat(report.shadowed()).isEqualTo(1);
        assertThat(report.disabledDuplicates()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQUIRES_NEW isolation: one shadow mutation throws → siblings still processed")
    void perRowExceptionDoesNotAbortLoop() {
        // Two independent name groups, each with a 2-row conflict.
        SkillEntity older1 = makeRow(20L, "name-a", false, true, 1L);
        SkillEntity newer1 = makeRow(21L, "name-a", false, true, 1L);
        SkillEntity older2 = makeRow(30L, "name-b", false, true, 2L);
        SkillEntity newer2 = makeRow(31L, "name-b", false, true, 2L);

        when(skillRepository.findAll()).thenReturn(List.of(older1, newer1, older2, newer2));
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        // First call (name-a) returns 21 head; second (name-b) returns 31 head.
        when(query.getResultList())
                .thenReturn(new ArrayList<>(Arrays.asList(21L, 20L)))
                .thenReturn(new ArrayList<>(Arrays.asList(31L, 30L)));

        // markShadowedByRuntime(20,21) throws; markShadowedByRuntime(30,31) succeeds.
        // Per-row try/catch in resolveConflicts should keep processing name-b.
        org.mockito.Mockito.doThrow(new RuntimeException("simulated REQUIRES_NEW tx failure"))
                .when(conflictResolver).markShadowedByRuntime(20L, 21L);
        org.mockito.Mockito.doNothing().when(conflictResolver).markShadowedByRuntime(30L, 31L);

        // Should not propagate.
        assertThatCode(() -> reconciler.resolveConflicts()).doesNotThrowAnyException();

        // Both rows had their mutation attempted — REQUIRES_NEW isolation upheld.
        verify(conflictResolver, times(1)).markShadowedByRuntime(20L, 21L);
        verify(conflictResolver, times(1)).markShadowedByRuntime(30L, 31L);
    }

    @Test
    @DisplayName("single row that is currently shadowed but no longer conflicts → clearShadowOrError")
    void singleShadowedRow_clearedWhenNoLongerConflicting() {
        SkillEntity row = makeRow(40L, "lonely", false, false, 1L);
        row.setArtifactStatus("shadowed");

        when(skillRepository.findAll()).thenReturn(List.of(row));

        RescanReport report = reconciler.resolveConflicts();

        verify(conflictResolver).clearShadowOrError(40L);
        verify(conflictResolver, never()).markShadowedBySystem(anyLong(), anyString());
        verify(conflictResolver, never()).markShadowedByRuntime(anyLong(), anyLong());
        assertThat(report.shadowed()).isZero();
    }

    @Test
    @DisplayName("4-tuple compat: reconciler queries (ownerId, name, source) — different owners do not match each other")
    void compatTuple_includesOwnerId() throws Exception {
        // This test verifies the contract by checking that reconciler asks
        // the repository with the expected ownerId. Real repository behavior
        // (different owners → no match) is JPA's job.
        java.nio.file.Path runtimeRoot = java.nio.file.Files.createTempDirectory("sf-tuple");
        java.nio.file.Path skillDir = runtimeRoot.resolve("upload/777/uuid-x");
        java.nio.file.Files.createDirectories(skillDir);
        java.nio.file.Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: tuple-skill\ndescription: x\n---\n\nbody.\n");

        when(homeResolver.getRuntimeRoot()).thenReturn(runtimeRoot);
        when(skillRepository.findByIsSystemFalse()).thenReturn(java.util.Collections.emptyList());
        when(skillRepository.findFirstByIsSystemFalseAndOwnerIdAndNameAndSource(
                any(), any(), any())).thenReturn(Optional.empty());
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> {
            SkillEntity e = inv.getArgument(0);
            e.setId(500L);
            return e;
        });

        // Construct a reconciler bound to the actual temp runtimeRoot (so
        // packageLoader can read the artifact). Use real package loader.
        SkillCatalogReconciler real = new SkillCatalogReconciler(
                homeResolver, skillRepository, new SkillPackageLoader(), conflictResolver);
        ReflectionTestUtils.setField(real, "entityManager", entityManager);

        real.reconcileRuntime();

        // Compat fallback was queried with ownerId = 777 (parsed from path),
        // not null and not some other number — verifying the 4-tuple includes
        // ownerId.
        verify(skillRepository).findFirstByIsSystemFalseAndOwnerIdAndNameAndSource(
                eq(777L), eq("tuple-skill"), eq("upload"));
    }

    // ─── helpers ───

    private SkillEntity makeRow(Long id, String name, boolean isSystem,
                                boolean enabled, Long ownerId) {
        SkillEntity e = new SkillEntity();
        e.setId(id);
        e.setName(name);
        e.setSystem(isSystem);
        e.setEnabled(enabled);
        e.setOwnerId(ownerId);
        e.setSource(isSystem ? "system" : "upload");
        e.setArtifactStatus("active");
        return e;
    }
}
