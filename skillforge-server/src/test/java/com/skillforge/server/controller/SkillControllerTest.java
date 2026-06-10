package com.skillforge.server.controller;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.improve.SkillEvolutionService;
import com.skillforge.server.service.SkillService;
import com.skillforge.server.skill.RescanReport;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-D — Contract test for {@link SkillController}'s P1-D additions:
 * <ul>
 *   <li>{@code POST /api/skills/rescan} returns 200 with the
 *       {@link RescanReport} JSON shape FE expects.</li>
 *   <li>{@code DELETE /api/skills/{id}} on a row whose {@code is_system=true}
 *       returns 403 — defense in depth, even if FE forgets to disable the
 *       button.</li>
 * </ul>
 * <p>Pattern follows {@code SkillDraftControllerTest}: direct controller
 * instantiation + invocation (no MockMvc, no Spring context).
 */
@ExtendWith(MockitoExtension.class)
class SkillControllerTest {

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
    @DisplayName("POST /api/skills/rescan returns 200 + RescanReport with all 6 counts")
    void rescan_returnsRescanReportJson() {
        RescanReport report = new RescanReport(3, 1, 2, 0, 4, 1);
        when(reconciler.fullRescan()).thenReturn(report);

        ResponseEntity<Map<String, Object>> resp = controller.rescan();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("created", 3);
        assertThat(body).containsEntry("updated", 1);
        assertThat(body).containsEntry("missing", 2);
        assertThat(body).containsEntry("invalid", 0);
        assertThat(body).containsEntry("shadowed", 4);
        assertThat(body).containsEntry("disabledDuplicates", 1);

        verify(reconciler).fullRescan();
        verify(userSkillLoader).loadAll();
    }

    @Test
    @DisplayName("POST /api/skills/rescan: userSkillLoader.loadAll exception is swallowed (logged only)")
    void rescan_loaderException_isSwallowed() {
        when(reconciler.fullRescan()).thenReturn(RescanReport.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("registry boom"))
                .when(userSkillLoader).loadAll();

        ResponseEntity<Map<String, Object>> resp = controller.rescan();

        // Loader exception must not turn a successful reconcile into a 5xx.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("created", 0);
    }

    @Test
    @DisplayName("DELETE /api/skills/{id}: numeric id pointing at is_system=true row → 403")
    void delete_systemRowByNumericId_returns403() {
        SkillEntity systemRow = new SkillEntity();
        systemRow.setId(42L);
        systemRow.setName("system-skill");
        systemRow.setSystem(true);
        when(skillService.findById(42L)).thenReturn(Optional.of(systemRow));

        ResponseEntity<?> resp = controller.deleteSkill("42");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Service must NOT be invoked — defense in depth.
        verify(skillService, never()).deleteSkill(anyLong());
    }

    @Test
    @DisplayName("DELETE /api/skills/{id}: legacy 'system-<name>' id → 403")
    void delete_systemPrefixedId_returns403() {
        ResponseEntity<?> resp = controller.deleteSkill("system-grill-me");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(skillService, never()).findById(anyLong());
        verify(skillService, never()).deleteSkill(anyLong());
    }

    @Test
    @DisplayName("DELETE /api/skills/{id}: regular user skill is deleted normally")
    void delete_userSkill_succeeds() {
        SkillEntity userRow = new SkillEntity();
        userRow.setId(99L);
        userRow.setName("MyUserSkill");
        userRow.setSystem(false);
        when(skillService.findById(99L)).thenReturn(Optional.of(userRow));

        ResponseEntity<?> resp = controller.deleteSkill("99");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(skillService).deleteSkill(99L);
    }

    @Test
    @DisplayName("DELETE /api/skills/{id}: non-numeric, non-system-prefixed id → 400")
    void delete_invalidId_returns400() {
        ResponseEntity<?> resp = controller.deleteSkill("abc-xyz");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(skillService, never()).deleteSkill(anyLong());
    }

    // ----------------------------------------------------------------------
    // SKILL-EVOLVE-LOOP Phase 2: /evaluate + /eval-history
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/skills/{id}/evaluate: delegates to runBaselineOnly + returns latest history map")
    void evaluate_delegatesToService() {
        com.skillforge.server.entity.SkillEvalHistoryEntity history =
                new com.skillforge.server.entity.SkillEvalHistoryEntity();
        history.setId(1L);
        history.setSkillId(11L);
        history.setCompositeScore(75.5);
        history.setTriggeredBy("manual");

        when(skillAbEvalService.runBaselineOnly(11L, "99", 7L, null, "manual"))
                .thenReturn(history);
        when(skillAbEvalService.getEvalHistoryForSkill(11L, 1))
                .thenReturn(java.util.List.of(java.util.Map.of(
                        "id", 1L,
                        "skillId", 11L,
                        "compositeScore", 75.5,
                        "triggeredBy", "manual")));

        ResponseEntity<?> resp = controller.evaluateSkill(11L, 7L, "99", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object body = resp.getBody();
        assertThat(body).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyMap = (Map<String, Object>) body;
        assertThat(bodyMap).containsEntry("compositeScore", 75.5);
        assertThat(bodyMap).containsEntry("triggeredBy", "manual");
        verify(skillAbEvalService).runBaselineOnly(11L, "99", 7L, null, "manual");
        verify(skillAbEvalService).getEvalHistoryForSkill(11L, 1);
    }

    @Test
    @DisplayName("POST /api/skills/{id}/evaluate: blank agentId → 400")
    void evaluate_blankAgent_returns400() {
        ResponseEntity<?> resp = controller.evaluateSkill(11L, 7L, "  ", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(skillAbEvalService, never()).runBaselineOnly(
                anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET /api/skills/{id}/eval-history: passes through service rows")
    void evalHistory_returnsRows() {
        when(skillAbEvalService.getEvalHistoryForSkill(11L, 20))
                .thenReturn(java.util.List.of(
                        java.util.Map.of("compositeScore", 80.0, "triggeredBy", "manual"),
                        java.util.Map.of("compositeScore", 60.0, "triggeredBy", "scheduled")));

        ResponseEntity<java.util.List<Map<String, Object>>> resp =
                controller.getEvalHistory(11L, 7L, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody().get(0)).containsEntry("compositeScore", 80.0);
        assertThat(resp.getBody().get(1)).containsEntry("triggeredBy", "scheduled");
        verify(skillAbEvalService).getEvalHistoryForSkill(11L, 20);
    }

    @Test
    @DisplayName("GET /api/skills/{id}/eval-history: forwards raw limit to service (service clamps)")
    void evalHistory_forwardsLimit() {
        when(skillAbEvalService.getEvalHistoryForSkill(org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.List.of());

        ResponseEntity<?> over = controller.getEvalHistory(11L, 7L, 9999);
        ResponseEntity<?> under = controller.getEvalHistory(11L, 7L, -5);

        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(under.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Service receives raw values; clamping is its responsibility (verified via direct service tests).
        verify(skillAbEvalService).getEvalHistoryForSkill(11L, 9999);
        verify(skillAbEvalService).getEvalHistoryForSkill(11L, -5);
    }

    // ----------------------------------------------------------------------
    // FLYWHEEL-VISUAL-STATUS Phase 2 (2026-05-20) — GET /api/skills/abtest
    // global paginated A/B run listing.
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("FLYWHEEL-VISUAL-STATUS Phase 2: GET /api/skills/abtest forwards filters + "
            + "page/size to service; returns items/page/pageSize/total/totalPages envelope")
    @SuppressWarnings("unchecked")
    void listAbTestsGlobal_happy_returnsPagedEnvelope() {
        com.skillforge.server.entity.SkillAbRunEntity r1 =
                new com.skillforge.server.entity.SkillAbRunEntity();
        r1.setId("ab-1");
        r1.setParentSkillId(11L);
        r1.setCandidateSkillId(12L);
        r1.setAgentId("ag-1");
        r1.setStatus("RUNNING");
        com.skillforge.server.entity.SkillAbRunEntity r2 =
                new com.skillforge.server.entity.SkillAbRunEntity();
        r2.setId("ab-2");
        r2.setParentSkillId(11L);
        r2.setCandidateSkillId(13L);
        r2.setAgentId("ag-1");
        r2.setStatus("RUNNING");
        org.springframework.data.domain.Page<com.skillforge.server.entity.SkillAbRunEntity> page =
                new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(r1, r2),
                        org.springframework.data.domain.PageRequest.of(0, 20),
                        2L);
        when(skillAbEvalService.getAbRunsByFilters(
                org.mockito.ArgumentMatchers.eq("ag-1"),
                org.mockito.ArgumentMatchers.eq("RUNNING"),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        ResponseEntity<?> resp = controller.listAbTestsGlobal("ag-1", "RUNNING", null, 1, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("items", "page", "pageSize", "total", "totalPages");
        assertThat(body.get("page")).isEqualTo(1);
        assertThat(body.get("pageSize")).isEqualTo(20);
        assertThat(body.get("total")).isEqualTo(2L);
        java.util.List<Map<String, Object>> items =
                (java.util.List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id")).isEqualTo("ab-1");
        assertThat(items.get(0).get("status")).isEqualTo("RUNNING");
        // toAbRunMap exposes manuallyPromoted (derived field) — verify wiring.
        assertThat(items.get(0)).containsKey("manuallyPromoted");
    }

    @Test
    @DisplayName("FLYWHEEL-VISUAL-STATUS Phase 2: GET /api/skills/abtest accepts surfaceType=skill "
            + "+ rejects other surfaces with 400 (t_skill_ab_run has no surface_type column)")
    void listAbTestsGlobal_surfaceTypeGuard() {
        when(skillAbEvalService.getAbRunsByFilters(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        // null surfaceType → accepted
        ResponseEntity<?> respNull = controller.listAbTestsGlobal(null, null, null, 1, 20);
        assertThat(respNull.getStatusCode()).isEqualTo(HttpStatus.OK);

        // "skill" surface → accepted
        ResponseEntity<?> respSkill = controller.listAbTestsGlobal(null, null, "skill", 1, 20);
        assertThat(respSkill.getStatusCode()).isEqualTo(HttpStatus.OK);

        // "prompt" surface → 400 (no surface_type column → only skill is honestly representable)
        ResponseEntity<?> respPrompt = controller.listAbTestsGlobal(null, null, "prompt", 1, 20);
        assertThat(respPrompt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Service must NOT be called when the surface guard rejects.
        verify(skillAbEvalService, org.mockito.Mockito.times(2)).getAbRunsByFilters(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    @DisplayName("FLYWHEEL-VISUAL-STATUS Phase 2: pageSize clamped 1-100, page floor=1")
    @SuppressWarnings("unchecked")
    void listAbTestsGlobal_pageSizeClamped() {
        org.springframework.data.domain.Page<com.skillforge.server.entity.SkillAbRunEntity> emptyPage =
                org.springframework.data.domain.Page.empty();
        when(skillAbEvalService.getAbRunsByFilters(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(emptyPage);

        // pageSize 9999 → clamped to 100; page 0 / negative → floor 1
        ResponseEntity<?> resp = controller.listAbTestsGlobal(null, null, null, 0, 9999);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("page")).isEqualTo(1);
        assertThat(body.get("pageSize")).isEqualTo(100);

        // pageSize -5 → clamped to 1 (floor; same as min)
        ResponseEntity<?> respMin = controller.listAbTestsGlobal(null, null, null, -3, -5);
        Map<String, Object> bodyMin = (Map<String, Object>) respMin.getBody();
        assertThat(bodyMin.get("page")).isEqualTo(1);
        assertThat(bodyMin.get("pageSize")).isEqualTo(1);
    }
}
