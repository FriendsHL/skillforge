package com.skillforge.server.controller;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.improve.SkillEvolutionService;
import com.skillforge.server.service.SkillService;
import com.skillforge.server.skill.BatchImportResult;
import com.skillforge.server.skill.SkillCatalogReconciler;
import com.skillforge.server.skill.SkillBatchImporter;
import com.skillforge.server.skill.SkillSource;
import com.skillforge.server.skill.UserSkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-IMPORT-BATCH — contract test for
 * {@link SkillController#rescanMarketplace}. Covers AC-6 (invalid source → 400)
 * and the happy path (valid source → 200 + {@link BatchImportResult}). AC-7
 * (unauthenticated → 401) is enforced by the {@code userId} {@code required=true}
 * binding which Spring rejects before reaching this method, so it is not
 * re-tested here.
 *
 * <p>Pattern follows {@link SkillControllerTest}: direct controller
 * instantiation + invocation (no MockMvc, no Spring context).
 */
@ExtendWith(MockitoExtension.class)
class SkillControllerRescanMarketplaceTest {

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
    @DisplayName("rescanMarketplace_validSource_returns200WithBatchImportResult")
    void rescanMarketplace_validSource_returns200WithBatchImportResult() {
        BatchImportResult expected = new BatchImportResult(
                List.of(new BatchImportResult.ImportedItem("alpha", "/runtime/clawhub/alpha/1.0.0")),
                List.of(),
                List.of(),
                List.of());
        when(skillBatchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 42L))
                .thenReturn(expected);

        ResponseEntity<?> resp = controller.rescanMarketplace("clawhub", 42L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isSameAs(expected);

        ArgumentCaptor<SkillSource> srcCap = ArgumentCaptor.forClass(SkillSource.class);
        ArgumentCaptor<Long> ownerCap = ArgumentCaptor.forClass(Long.class);
        verify(skillBatchImporter).batchImportFromMarketplace(srcCap.capture(), ownerCap.capture());
        assertThat(srcCap.getValue()).isEqualTo(SkillSource.CLAWHUB);
        assertThat(ownerCap.getValue()).isEqualTo(42L);
    }

    @Test
    @DisplayName("rescanMarketplace_uppercaseSourceWire_isLowercaseInsensitiveAndAllowed")
    void rescanMarketplace_uppercaseSourceWire_isLowercaseInsensitiveAndAllowed() {
        // The 4 marketplace SkillSource enum names (CLAWHUB / GITHUB / SKILLHUB
        // / FILESYSTEM) are single-word — there is no actual marketplace wire
        // form that exercises the dash→underscore branch. Validate the
        // remaining mapping concern: case-insensitive valueOf + marketplace
        // whitelist for a real marketplace source.
        when(skillBatchImporter.batchImportFromMarketplace(any(), anyLong()))
                .thenReturn(new BatchImportResult(List.of(), List.of(), List.of(), List.of()));

        ResponseEntity<?> resp = controller.rescanMarketplace("SkillHub", 9L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<SkillSource> srcCap = ArgumentCaptor.forClass(SkillSource.class);
        verify(skillBatchImporter).batchImportFromMarketplace(srcCap.capture(), anyLong());
        assertThat(srcCap.getValue()).isEqualTo(SkillSource.SKILLHUB);
    }

    @Test
    @DisplayName("rescanMarketplace_internalSource_returns400 (blocker fix: source whitelist)")
    void rescanMarketplace_internalSource_returns400() {
        // PRD F1 only allows {clawhub, github, skillhub, filesystem}. Passing
        // server-internal sources must be rejected with 400 BEFORE reaching
        // the import service — otherwise t_skill.source could be polluted with
        // wire forms that do not correspond to a marketplace install.
        for (String internal : List.of("upload", "skill-creator", "draft-approve", "evolution-fork")) {
            ResponseEntity<?> resp = controller.rescanMarketplace(internal, 42L);

            assertThat(resp.getStatusCode())
                    .as("internal source %s must be rejected", internal)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            Object body = resp.getBody();
            assertThat(body).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) body;
            assertThat(map.get("error")).asString()
                    .contains("source must be one of")
                    .contains(internal);
        }
        // Defence in depth — service must NEVER be invoked for any of the
        // internal sources, even though SkillSource.valueOf would have parsed
        // them successfully.
        verify(skillBatchImporter, never()).batchImportFromMarketplace(any(), anyLong());
    }

    @Test
    @DisplayName("rescanMarketplace_invalidSource_returns400 (AC-6)")
    void rescanMarketplace_invalidSource_returns400() {
        ResponseEntity<?> resp = controller.rescanMarketplace("hacker", 42L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Object body = resp.getBody();
        assertThat(body).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) body;
        assertThat(map.get("error")).asString().contains("invalid source").contains("hacker");

        // Defence in depth — service must not be invoked when source is invalid.
        verify(skillBatchImporter, never()).batchImportFromMarketplace(any(), anyLong());
    }

    @Test
    @DisplayName("rescanMarketplace_serviceThrows_propagatesAsRuntime")
    void rescanMarketplace_serviceThrows_propagatesAsRuntime() {
        // Service-level failures (unexpected, not the per-subdir kind which
        // are bucketed in BatchImportResult.failed) are not silently swallowed
        // — they propagate so the framework's error handler returns 500. We
        // assert the exception escapes; we don't depend on a specific HTTP
        // mapping inside this controller-level test.
        when(skillBatchImporter.batchImportFromMarketplace(any(), anyLong()))
                .thenThrow(new RuntimeException("disk full"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.rescanMarketplace("clawhub", 42L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("disk full");
    }
}
