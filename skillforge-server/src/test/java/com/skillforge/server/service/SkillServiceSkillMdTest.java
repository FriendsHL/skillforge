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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Covers {@link SkillService#readSkillMd} — the repository access + visibility check
 * + file I/O that moved out of {@code SkillController.getSkillMd} during the layering
 * refactor. Controller-side HTTP mapping is covered by {@code SkillControllerSkillMdTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService.readSkillMd")
class SkillServiceSkillMdTest {

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

    @Test
    @DisplayName("SKILL.md present → Loaded with content + path")
    void loaded(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("skill-1");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# MySkill\n\nDoes a thing.\n");

        SkillEntity entity = new SkillEntity();
        entity.setId(1L);
        entity.setOwnerId(7L);
        entity.setSkillPath(skillDir.toString());
        when(skillRepository.findById(1L)).thenReturn(Optional.of(entity));

        SkillService.SkillMdReadResult result = service.readSkillMd(1L, 7L);

        assertThat(result).isInstanceOf(SkillService.SkillMdReadResult.Loaded.class);
        SkillService.SkillMdReadResult.Loaded loaded = (SkillService.SkillMdReadResult.Loaded) result;
        assertThat(loaded.content()).contains("# MySkill");
        assertThat(loaded.path()).endsWith("SKILL.md");
    }

    @Test
    @DisplayName("skill_path null → NoPath")
    void nullSkillPath() {
        SkillEntity entity = new SkillEntity();
        entity.setId(2L);
        entity.setOwnerId(7L);
        entity.setSkillPath(null);
        when(skillRepository.findById(2L)).thenReturn(Optional.of(entity));

        SkillService.SkillMdReadResult result = service.readSkillMd(2L, 7L);

        assertThat(result).isInstanceOf(SkillService.SkillMdReadResult.NoPath.class);
    }

    @Test
    @DisplayName("SKILL.md missing on disk → NotOnDisk with path")
    void skillMdMissing(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("skill-3");
        Files.createDirectories(skillDir);
        // Directory exists but SKILL.md does not.

        SkillEntity entity = new SkillEntity();
        entity.setId(3L);
        entity.setOwnerId(7L);
        entity.setSkillPath(skillDir.toString());
        when(skillRepository.findById(3L)).thenReturn(Optional.of(entity));

        SkillService.SkillMdReadResult result = service.readSkillMd(3L, 7L);

        assertThat(result).isInstanceOf(SkillService.SkillMdReadResult.NotOnDisk.class);
        SkillService.SkillMdReadResult.NotOnDisk notOnDisk =
                (SkillService.SkillMdReadResult.NotOnDisk) result;
        assertThat(notOnDisk.path()).endsWith("SKILL.md");
    }

    @Test
    @DisplayName("non-owner of non-public skill → Forbidden")
    void nonOwnerNonPublic() {
        SkillEntity entity = new SkillEntity();
        entity.setId(4L);
        entity.setOwnerId(7L);
        entity.setPublic(false);
        when(skillRepository.findById(4L)).thenReturn(Optional.of(entity));

        // userId=99 != ownerId=7, skill is not public → forbidden.
        SkillService.SkillMdReadResult result = service.readSkillMd(4L, 99L);

        assertThat(result).isInstanceOf(SkillService.SkillMdReadResult.Forbidden.class);
    }

    @Test
    @DisplayName("missing skill id → RuntimeException")
    void missingSkill() {
        when(skillRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readSkillMd(9L, 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("9");
    }
}
