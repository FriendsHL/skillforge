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
}
