package com.skillforge.server.controller;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.improve.SkillEvolutionService;
import com.skillforge.server.service.SkillService;
import com.skillforge.server.skill.SkillCatalogReconciler;
import com.skillforge.server.skill.SkillBatchImporter;
import com.skillforge.server.skill.UserSkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * SKILL-DASHBOARD-POLISH B — verify {@code GET /api/skills/{id}/skill-md} maps each
 * {@link SkillService.SkillMdReadResult} variant to the right HTTP response. The
 * underlying repository access + file I/O now live in
 * {@code SkillService.readSkillMd} (covered by {@code SkillServiceSkillMdTest});
 * this test covers the controller's protocol mapping only.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillController.getSkillMd")
class SkillControllerSkillMdTest {

    @Mock private SkillService skillService;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillAbEvalService skillAbEvalService;
    @Mock private SkillEvolutionService skillEvolutionService;
    @Mock private SkillCatalogReconciler reconciler;
    @Mock private UserSkillLoader userSkillLoader;
    @Mock private SkillBatchImporter skillBatchImporter;

    private SkillController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillController(skillService, skillRegistry,
                skillAbEvalService, skillEvolutionService, reconciler, userSkillLoader,
                skillBatchImporter);
    }

    @Test
    @DisplayName("Loaded → 200 with content + path + updatedAt")
    void loaded_returns200WithContent() {
        when(skillService.readSkillMd(1L, 7L)).thenReturn(
                new SkillService.SkillMdReadResult.Loaded("# MySkill\n", "/abs/skill-1/SKILL.md",
                        "2026-05-14T10:00:00Z"));

        ResponseEntity<?> resp = controller.getSkillMd(1L, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("content")).asString().contains("# MySkill");
        assertThat(body.get("path")).asString().endsWith("SKILL.md");
        assertThat(body.get("updatedAt")).isEqualTo("2026-05-14T10:00:00Z");
    }

    @Test
    @DisplayName("Loaded with null mtime → 200, updatedAt key omitted")
    void loaded_nullMtime_omitsUpdatedAt() {
        when(skillService.readSkillMd(1L, 7L)).thenReturn(
                new SkillService.SkillMdReadResult.Loaded("body", "/abs/SKILL.md", null));

        ResponseEntity<?> resp = controller.getSkillMd(1L, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).doesNotContainKey("updatedAt");
    }

    @Test
    @DisplayName("NoPath → 200 with empty content + null path")
    void noPath_returnsEmpty() {
        when(skillService.readSkillMd(2L, 7L)).thenReturn(new SkillService.SkillMdReadResult.NoPath());

        ResponseEntity<?> resp = controller.getSkillMd(2L, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("content")).isEqualTo("");
        assertThat(body.get("path")).isNull();
    }

    @Test
    @DisplayName("NotOnDisk → 200 with empty content + error message")
    void notOnDisk_returnsErrorBody() {
        when(skillService.readSkillMd(3L, 7L)).thenReturn(
                new SkillService.SkillMdReadResult.NotOnDisk("/abs/skill-3/SKILL.md"));

        ResponseEntity<?> resp = controller.getSkillMd(3L, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("content")).isEqualTo("");
        assertThat(body.get("error")).asString().contains("not found");
    }

    @Test
    @DisplayName("Forbidden → 403 forbidden")
    void forbidden_returns403() {
        when(skillService.readSkillMd(4L, 99L)).thenReturn(new SkillService.SkillMdReadResult.Forbidden());

        ResponseEntity<?> resp = controller.getSkillMd(4L, 99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("forbidden");
    }

    @Test
    @DisplayName("ReadFailed → 500 with error message")
    void readFailed_returns500() {
        when(skillService.readSkillMd(5L, 7L)).thenReturn(
                new SkillService.SkillMdReadResult.ReadFailed("Failed to read SKILL.md: disk error"));

        ResponseEntity<?> resp = controller.getSkillMd(5L, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).asString().contains("Failed to read SKILL.md");
    }

    @Test
    @DisplayName("missing skill id → service RuntimeException propagates")
    void missingSkill_propagatesException() {
        when(skillService.readSkillMd(9L, 7L)).thenThrow(new RuntimeException("Skill not found: 9"));

        assertThatThrownBy(() -> controller.getSkillMd(9L, 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("9");
    }
}
