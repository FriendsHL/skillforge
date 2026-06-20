package com.skillforge.server.skill.curate;

import com.skillforge.server.config.SkillConsolidatorProperties;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillConsolidator (SKILL-CURATOR V1)")
class SkillConsolidatorTest {

    @Mock
    private SkillRepository skillRepository;

    private SkillConsolidatorProperties props;
    private SkillConsolidator consolidator;

    @BeforeEach
    void setUp() {
        props = new SkillConsolidatorProperties();
        consolidator = new SkillConsolidator(skillRepository, props);
    }

    private SkillEntity skill(long id, String name) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName(name);
        s.setUsageCount(0);
        s.setEnabled(true);
        s.setCreatedAt(LocalDateTime.now().minusDays(60));
        return s;
    }

    @Test
    @DisplayName("dry-run (default) finds candidates but archives 0 and mutates nothing")
    void dryRun_findsCandidates_archivesNothing() {
        // props.dryRun defaults to true (v1 safety default)
        SkillEntity a = skill(1L, "alpha");
        SkillEntity b = skill(2L, "beta");
        when(skillRepository.findArchivalCandidates(anyLong(), any(LocalDateTime.class), any(Instant.class)))
                .thenReturn(List.of(a, b));

        SkillConsolidator.ConsolidationResult result = consolidator.consolidate();

        assertThat(result.dryRun()).isTrue();
        assertThat(result.candidatesFound()).isEqualTo(2);
        assertThat(result.archived()).isEqualTo(0);
        // No mutation: not saved, not disabled, not archived.
        verify(skillRepository, never()).save(any());
        assertThat(a.isEnabled()).isTrue();
        assertThat(a.getArchivedAt()).isNull();
        assertThat(a.getArchiveReason()).isNull();
    }

    @Test
    @DisplayName("real mode disables + stamps archivedAt/reason and saves each candidate")
    void realMode_archivesCandidates() {
        props.setDryRun(false);
        SkillEntity a = skill(1L, "alpha");
        SkillEntity b = skill(2L, "beta");
        when(skillRepository.findArchivalCandidates(anyLong(), any(LocalDateTime.class), any(Instant.class)))
                .thenReturn(List.of(a, b));

        SkillConsolidator.ConsolidationResult result = consolidator.consolidate();

        assertThat(result.dryRun()).isFalse();
        assertThat(result.candidatesFound()).isEqualTo(2);
        assertThat(result.archived()).isEqualTo(2);
        verify(skillRepository, times(2)).save(any(SkillEntity.class));
        assertThat(a.isEnabled()).isFalse();
        assertThat(a.getArchivedAt()).isNotNull();
        assertThat(a.getArchiveReason()).isEqualTo("low_usage_curator");
        assertThat(b.isEnabled()).isFalse();
        assertThat(b.getArchivedAt()).isNotNull();
        assertThat(b.getArchiveReason()).isEqualTo("low_usage_curator");
    }

    @Test
    @DisplayName("enabled=false property short-circuits to a no-op (no query, no save)")
    void disabled_isNoOp() {
        props.setEnabled(false);

        SkillConsolidator.ConsolidationResult result = consolidator.consolidate();

        assertThat(result.candidatesFound()).isEqualTo(0);
        assertThat(result.archived()).isEqualTo(0);
        verify(skillRepository, never()).findArchivalCandidates(anyLong(), any(), any());
        verify(skillRepository, never()).save(any());
    }

    @Test
    @DisplayName("one skill throwing during save does not abort the batch (INV-2)")
    void oneFailure_doesNotAbortBatch() {
        props.setDryRun(false);
        SkillEntity bad = skill(1L, "bad");
        SkillEntity good = skill(2L, "good");
        when(skillRepository.findArchivalCandidates(anyLong(), any(LocalDateTime.class), any(Instant.class)))
                .thenReturn(List.of(bad, good));
        when(skillRepository.save(bad)).thenThrow(new RuntimeException("boom"));
        when(skillRepository.save(good)).thenReturn(good);

        SkillConsolidator.ConsolidationResult result = consolidator.consolidate();

        // bad failed, good still processed.
        assertThat(result.candidatesFound()).isEqualTo(2);
        assertThat(result.archived()).isEqualTo(1);
        verify(skillRepository, times(2)).save(any(SkillEntity.class));
        assertThat(good.isEnabled()).isFalse();
        assertThat(good.getArchiveReason()).isEqualTo("low_usage_curator");
    }
}
