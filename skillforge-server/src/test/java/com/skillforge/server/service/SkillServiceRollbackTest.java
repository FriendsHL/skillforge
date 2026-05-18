package com.skillforge.server.service;

import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.SkillStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-DASHBOARD-POLISH D — verify {@link SkillService#rollbackToParent}.
 * The crucial INV is the V64 partial-unique ordering: candidate must be
 * disabled (and flushed) BEFORE the parent is enabled, otherwise both rows
 * would briefly satisfy {@code uq_t_skill_owner_name_enabled} and the
 * partial unique index would fire mid-transaction. This mirrors the (also
 * V64-safe) ordering in {@code SkillAbEvalService.promoteCandidate} which
 * disables the parent first.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService.rollbackToParent")
class SkillServiceRollbackTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillPackageLoader skillPackageLoader;
    @Mock private SkillStorageService skillStorageService;
    @Mock private SkillEvalHistoryRepository skillEvalHistoryRepository;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(skillRepository, skillRegistry, skillPackageLoader,
                skillStorageService, skillEvalHistoryRepository,
                null, null, null); // SKILL-CREATOR-WITH-EVAL Phase 1.2: eval-gate deps not exercised here
    }

    private SkillEntity newSkill(Long id, Long parentSkillId, boolean enabled, String semver) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName("MySkill");
        s.setParentSkillId(parentSkillId);
        s.setEnabled(enabled);
        s.setSemver(semver);
        // null skillPath in this test case so registry re-register is a no-op skip.
        return s;
    }

    @Test
    @DisplayName("happy path: disable candidate FIRST + flush, then enable parent + save")
    void happyPath_v64Ordering() {
        // Current (after promote) state: parent disabled, candidate enabled.
        SkillEntity parent = newSkill(10L, null, false, "v1");
        SkillEntity candidate = newSkill(20L, 10L, true, "v2");

        when(skillRepository.findById(20L)).thenReturn(Optional.of(candidate));
        when(skillRepository.findById(10L)).thenReturn(Optional.of(parent));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillEntity result = service.rollbackToParent(20L, 7L);

        // V64-safe order: saveAndFlush(candidate w/ enabled=false) BEFORE save(parent w/ enabled=true).
        InOrder inOrder = inOrder(skillRepository);
        inOrder.verify(skillRepository).saveAndFlush(candidate);
        inOrder.verify(skillRepository).save(parent);

        // Final state.
        assertThat(candidate.isEnabled()).isFalse();
        assertThat(parent.isEnabled()).isTrue();
        assertThat(result).isSameAs(parent);

        // candidate unregistered from registry (best-effort).
        verify(skillRegistry).unregisterSkillDefinition("MySkill");
    }

    @Test
    @DisplayName("candidate already disabled → throws (already rolled back?)")
    void candidateNotEnabled_throws() {
        SkillEntity candidate = newSkill(20L, 10L, false, "v2");
        when(skillRepository.findById(20L)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.rollbackToParent(20L, 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not currently enabled");

        verify(skillRepository, never()).saveAndFlush(any());
        verify(skillRepository, never()).save(any());
    }

    @Test
    @DisplayName("candidate has no parent → throws")
    void candidateNoParent_throws() {
        SkillEntity orphan = newSkill(20L, null, true, "v1");
        when(skillRepository.findById(20L)).thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> service.rollbackToParent(20L, 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no parent");

        verify(skillRepository, never()).saveAndFlush(any());
        verify(skillRepository, never()).save(any());
    }

    @Test
    @DisplayName("candidate id not found → throws")
    void candidateMissing_throws() {
        when(skillRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollbackToParent(99L, 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }
}
