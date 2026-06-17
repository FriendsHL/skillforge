package com.skillforge.server.controller;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.improve.SkillEvolutionService;
import com.skillforge.server.service.SkillService;
import com.skillforge.server.skill.SkillBatchImporter;
import com.skillforge.server.skill.SkillCatalogReconciler;
import com.skillforge.server.skill.UserSkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SKILL-DASHBOARD-POLISH-V2 §I — verify the version-tree endpoint dispatches
 * to {@link SkillService#getVersionTree} and maps service exceptions to the
 * right HTTP status (404 / 403 / 400).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillController.getVersionTree (/api/skills/{id}/version-tree)")
class SkillControllerVersionTreeTest {

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

    private Map<String, Object> mockTree() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ancestors", List.of(Map.of("id", 1L, "name", "MySkill", "semver", "v1")));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("id", 2L);
        current.put("name", "MySkill");
        current.put("semver", "v2");
        current.put("latestScore", 75.0);
        result.put("current", current);
        result.put("descendants", List.of());
        return result;
    }

    @Test
    @DisplayName("happy path returns 200 + tree JSON")
    @SuppressWarnings("unchecked")
    void happyPath_returns200() {
        when(skillService.getVersionTree(eq(2L), eq(7L))).thenReturn(mockTree());

        ResponseEntity<?> resp = controller.getVersionTree(2L, 7L, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKeys("ancestors", "current", "descendants");
        assertThat(((List<?>) body.get("ancestors"))).hasSize(1);
    }

    @Test
    @DisplayName("skill not found → 404")
    void skillNotFound_returns404() {
        when(skillService.getVersionTree(eq(2L), eq(7L)))
                .thenThrow(new RuntimeException("Skill not found: 2"));

        ResponseEntity<?> resp = controller.getVersionTree(2L, 7L, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("ownership mismatch → 403")
    @SuppressWarnings("unchecked")
    void ownershipMismatch_returns403() {
        when(skillService.getVersionTree(eq(2L), eq(99L)))
                .thenThrow(new RuntimeException("Caller userId=99 does not own skill id=2"));

        ResponseEntity<?> resp = controller.getVersionTree(2L, 99L, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("error");
    }

    @Test
    @DisplayName("system skill exposure → 403")
    void systemSkill_returns403() {
        when(skillService.getVersionTree(eq(2L), eq(7L)))
                .thenThrow(new RuntimeException("Cannot expose version tree for system skill: 2"));

        ResponseEntity<?> resp = controller.getVersionTree(2L, 7L, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
