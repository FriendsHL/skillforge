package com.skillforge.server.service;

import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.SkillCreatorService;
import com.skillforge.server.skill.SkillStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers {@link SkillService#writeSkillMd} — the repository access + gate chain +
 * file I/O that moved out of {@code SkillController.updateSkillMd}. Asserts every
 * {@code SkillMdWriteResult} variant plus two gate-ordering cases that the 3-arg
 * {@code content == null} check (vs the dropped {@code contentProvided} flag) must
 * keep correct. Controller-side HTTP mapping is covered by {@code SkillControllerSkillMdTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SkillService.writeSkillMd")
class SkillServiceSkillMdWriteTest {

    private static final Long OWNER = 7L;

    @Mock private SkillRepository skillRepository;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillPackageLoader skillPackageLoader;
    @Mock private SkillStorageService skillStorageService;
    @Mock private SkillEvalHistoryRepository skillEvalHistoryRepository;
    @Mock private SkillCreatorService skillCreatorService;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private EvalScenarioDraftRepository evalScenarioRepository;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(skillRepository, skillRegistry, skillPackageLoader,
                skillStorageService, skillEvalHistoryRepository, skillCreatorService,
                skillDraftRepository, evalScenarioRepository);
    }

    /** A write-eligible row: disabled candidate (parentSkillId != null && !enabled), owned by OWNER. */
    private SkillEntity disabledCandidate(Long id, String skillPath) {
        SkillEntity e = new SkillEntity();
        e.setId(id);
        e.setOwnerId(OWNER);
        e.setParentSkillId(100L);
        e.setEnabled(false);
        e.setSkillPath(skillPath);
        return e;
    }

    @Test
    @DisplayName("missing id → NotFound")
    void notFound() {
        when(skillRepository.findById(1L)).thenReturn(Optional.empty());

        assertThat(service.writeSkillMd(1L, "body", OWNER))
                .isInstanceOf(SkillService.SkillMdWriteResult.NotFound.class);
    }

    @Test
    @DisplayName("system skill → SystemForbidden")
    void systemForbidden() {
        SkillEntity e = new SkillEntity();
        e.setId(2L);
        e.setSystem(true);
        when(skillRepository.findById(2L)).thenReturn(Optional.of(e));

        assertThat(service.writeSkillMd(2L, "body", OWNER))
                .isInstanceOf(SkillService.SkillMdWriteResult.SystemForbidden.class);
    }

    @Test
    @DisplayName("active row (parentSkillId null) → NotEditableCandidate")
    void notEditableCandidate_noParent() {
        SkillEntity e = new SkillEntity();
        e.setId(3L);
        e.setOwnerId(OWNER);
        e.setParentSkillId(null);
        e.setEnabled(false);
        when(skillRepository.findById(3L)).thenReturn(Optional.of(e));

        assertThat(service.writeSkillMd(3L, "body", OWNER))
                .isInstanceOf(SkillService.SkillMdWriteResult.NotEditableCandidate.class);
    }

    @Test
    @DisplayName("enabled candidate → NotEditableCandidate")
    void notEditableCandidate_enabled() {
        SkillEntity e = new SkillEntity();
        e.setId(4L);
        e.setOwnerId(OWNER);
        e.setParentSkillId(100L);
        e.setEnabled(true);
        when(skillRepository.findById(4L)).thenReturn(Optional.of(e));

        assertThat(service.writeSkillMd(4L, "body", OWNER))
                .isInstanceOf(SkillService.SkillMdWriteResult.NotEditableCandidate.class);
    }

    @Test
    @DisplayName("non-owner → OwnerForbidden")
    void ownerForbidden() {
        when(skillRepository.findById(5L)).thenReturn(Optional.of(disabledCandidate(5L, "/x")));

        assertThat(service.writeSkillMd(5L, "body", 99L))
                .isInstanceOf(SkillService.SkillMdWriteResult.OwnerForbidden.class);
    }

    @Test
    @DisplayName("content null → ContentRequired")
    void contentRequired() {
        when(skillRepository.findById(6L)).thenReturn(Optional.of(disabledCandidate(6L, "/x")));

        assertThat(service.writeSkillMd(6L, null, OWNER))
                .isInstanceOf(SkillService.SkillMdWriteResult.ContentRequired.class);
    }

    @Test
    @DisplayName("empty string content is valid (NOT ContentRequired) → Written")
    void emptyContentIsValid(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("skill-empty");
        when(skillRepository.findById(60L)).thenReturn(Optional.of(disabledCandidate(60L, dir.toString())));

        SkillService.SkillMdWriteResult result = service.writeSkillMd(60L, "", OWNER);

        assertThat(result).isInstanceOf(SkillService.SkillMdWriteResult.Written.class);
        SkillService.SkillMdWriteResult.Written written = (SkillService.SkillMdWriteResult.Written) result;
        assertThat(written.bytes()).isZero();
        assertThat(Files.readString(dir.resolve("SKILL.md"))).isEmpty();
    }

    @Test
    @DisplayName("candidate without skillPath → NoPath")
    void noPath() {
        when(skillRepository.findById(7L)).thenReturn(Optional.of(disabledCandidate(7L, null)));

        assertThat(service.writeSkillMd(7L, "body", OWNER))
                .isInstanceOf(SkillService.SkillMdWriteResult.NoPath.class);
    }

    @Test
    @DisplayName("happy path → Written with id + path + byte count, file on disk")
    void written(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("skill-8");
        when(skillRepository.findById(8L)).thenReturn(Optional.of(disabledCandidate(8L, dir.toString())));

        SkillService.SkillMdWriteResult result = service.writeSkillMd(8L, "# Edited\n", OWNER);

        assertThat(result).isInstanceOf(SkillService.SkillMdWriteResult.Written.class);
        SkillService.SkillMdWriteResult.Written written = (SkillService.SkillMdWriteResult.Written) result;
        assertThat(written.id()).isEqualTo(8L);
        assertThat(written.path()).endsWith("SKILL.md");
        assertThat(written.bytes()).isEqualTo("# Edited\n".length());
        assertThat(Files.readString(dir.resolve("SKILL.md"))).isEqualTo("# Edited\n");
    }

    @Test
    @DisplayName("write I/O failure → WriteFailed")
    void writeFailed(@TempDir Path tmp) throws IOException {
        // skillPath points at an existing *file*, so Files.createDirectories throws.
        Path notADir = tmp.resolve("not-a-dir");
        Files.writeString(notADir, "i am a file");
        when(skillRepository.findById(9L)).thenReturn(Optional.of(disabledCandidate(9L, notADir.toString())));

        SkillService.SkillMdWriteResult result = service.writeSkillMd(9L, "body", OWNER);

        assertThat(result).isInstanceOf(SkillService.SkillMdWriteResult.WriteFailed.class);
        SkillService.SkillMdWriteResult.WriteFailed failed =
                (SkillService.SkillMdWriteResult.WriteFailed) result;
        assertThat(failed.message()).contains("Failed to write SKILL.md");
    }

    // ---- Gate-ordering cases the content==null check must keep correct ----

    @Test
    @DisplayName("non-owner + content null → OwnerForbidden (owner gate precedes content gate)")
    void ownerForbiddenBeatsContentRequired() {
        when(skillRepository.findById(10L)).thenReturn(Optional.of(disabledCandidate(10L, "/x")));

        assertThat(service.writeSkillMd(10L, null, 99L))
                .isInstanceOf(SkillService.SkillMdWriteResult.OwnerForbidden.class);
    }

    @Test
    @DisplayName("content null + skillPath null → ContentRequired (content gate precedes skillPath gate)")
    void contentRequiredBeatsNoPath() {
        when(skillRepository.findById(11L)).thenReturn(Optional.of(disabledCandidate(11L, null)));

        assertThat(service.writeSkillMd(11L, null, OWNER))
                .isInstanceOf(SkillService.SkillMdWriteResult.ContentRequired.class);
    }
}
