package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.improve.behavior.AgentRoleResolver;
import com.skillforge.server.improve.behavior.BehaviorRuleAbEvalService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V4 Phase 1.4 — {@link BehaviorRuleVersionController} REST shape + status
 * code mapping tests. MockMvc standalone setup (same pattern as
 * {@code CanaryRolloutControllerTest}).
 */
@EnableWebMvc
@DisplayName("BehaviorRuleVersionController")
class BehaviorRuleVersionControllerTest {

    private BehaviorRuleVersionRepository versionRepository;
    private BehaviorRuleAbRunRepository abRunRepository;
    /**
     * r2-BE-1: stored as field so tests can stub {@code findById} to exercise
     * the "agent deleted → ownerAgentRole null" branch in
     * {@link BehaviorRuleVersionController#latestAbRun(String)}.
     */
    private AgentRepository agentRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        versionRepository = mock(BehaviorRuleVersionRepository.class);
        abRunRepository = mock(BehaviorRuleAbRunRepository.class);
        agentRepository = mock(AgentRepository.class);
        // java.md footgun #1: register JavaTimeModule so Instant fields
        // serialize as ISO-8601 strings instead of epoch arrays.
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        BehaviorRuleVersionController controller =
                new BehaviorRuleVersionController(
                        versionRepository,
                        abRunRepository,
                        mock(BehaviorRuleAbEvalService.class),
                        mock(BehaviorRulePromotionService.class),
                        objectMapper,
                        agentRepository,
                        new AgentRoleResolver());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private BehaviorRuleVersionEntity version(String id, String agentId, int versionNumber, String status) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(id);
        v.setAgentId(agentId);
        v.setVersionNumber(versionNumber);
        v.setStatus(status);
        v.setSource(BehaviorRuleVersionEntity.SOURCE_ATTRIBUTION);
        v.setRulesJson("[{\"id\":\"ALWAYS_USE_BASH\"}]");
        v.setImprovementRationale("curator rationale");
        v.setSourceEventId(42L);
        v.setBaselineVersionId("br-prev-uuid");
        v.setCreatedAt(Instant.parse("2026-05-15T10:00:00Z"));
        if (BehaviorRuleVersionEntity.STATUS_ACTIVE.equals(status)) {
            v.setPromotedAt(Instant.parse("2026-05-15T11:00:00Z"));
        }
        return v;
    }

    // ───────────────────────── GET list ────────────────────────

    @Test
    @DisplayName("GET /api/behavior-rules/versions?agentId=42 returns all versions newest-first")
    void list_happyPath_returnsAllVersionsForAgent() throws Exception {
        BehaviorRuleVersionEntity active = version("br-v2-uuid", "42", 2, BehaviorRuleVersionEntity.STATUS_ACTIVE);
        BehaviorRuleVersionEntity retired = version("br-v1-uuid", "42", 1, BehaviorRuleVersionEntity.STATUS_RETIRED);
        when(versionRepository.findByAgentIdOrderByVersionNumberDesc("42"))
                .thenReturn(List.of(active, retired));

        mvc.perform(get("/api/behavior-rules/versions").param("agentId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // First row = active (versionNumber=2)
                .andExpect(jsonPath("$[0].id").value("br-v2-uuid"))
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].agentId").value("42"))
                .andExpect(jsonPath("$[0].source").value("attribution"))
                .andExpect(jsonPath("$[0].rulesJson").value("[{\"id\":\"ALWAYS_USE_BASH\"}]"))
                .andExpect(jsonPath("$[0].promotedAt").exists())
                // Second row = retired (versionNumber=1)
                .andExpect(jsonPath("$[1].id").value("br-v1-uuid"))
                .andExpect(jsonPath("$[1].status").value("retired"));

        // status= not supplied → unfiltered finder used, not the status one.
        verify(versionRepository, never())
                .findByAgentIdAndStatusOrderByVersionNumberDesc(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET /api/behavior-rules/versions?agentId=42&status=active uses status-filter finder")
    void list_withStatusFilter_callsStatusOrderedFinder() throws Exception {
        BehaviorRuleVersionEntity active = version("br-v2-uuid", "42", 2, BehaviorRuleVersionEntity.STATUS_ACTIVE);
        when(versionRepository.findByAgentIdAndStatusOrderByVersionNumberDesc("42", "active"))
                .thenReturn(List.of(active));

        mvc.perform(get("/api/behavior-rules/versions")
                        .param("agentId", "42")
                        .param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("br-v2-uuid"))
                .andExpect(jsonPath("$[0].status").value("active"));

        // Unfiltered finder NOT called when status param supplied.
        verify(versionRepository, never())
                .findByAgentIdOrderByVersionNumberDesc(org.mockito.ArgumentMatchers.anyString());
    }

    // ───────────────────────── GET detail ──────────────────────

    @Test
    @DisplayName("GET /api/behavior-rules/versions/{id} returns single version when present")
    void getOne_happyPath_returnsVersion() throws Exception {
        BehaviorRuleVersionEntity v = version("br-v2-uuid", "42", 2, BehaviorRuleVersionEntity.STATUS_ACTIVE);
        when(versionRepository.findById("br-v2-uuid")).thenReturn(Optional.of(v));

        mvc.perform(get("/api/behavior-rules/versions/{id}", "br-v2-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("br-v2-uuid"))
                .andExpect(jsonPath("$.agentId").value("42"))
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.source").value("attribution"))
                .andExpect(jsonPath("$.rulesJson").value("[{\"id\":\"ALWAYS_USE_BASH\"}]"))
                .andExpect(jsonPath("$.improvementRationale").value("curator rationale"))
                .andExpect(jsonPath("$.sourceEventId").value(42))
                .andExpect(jsonPath("$.baselineVersionId").value("br-prev-uuid"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.promotedAt").exists());
    }

    @Test
    @DisplayName("GET /api/behavior-rules/versions/{id} returns 404 when not found")
    void getOne_notFound_returns404() throws Exception {
        when(versionRepository.findById("ghost-uuid")).thenReturn(Optional.empty());

        mvc.perform(get("/api/behavior-rules/versions/{id}", "ghost-uuid"))
                .andExpect(status().isNotFound());
    }

    // ───────────────────────── GET latest-ab-run (C4) ──────────────

    @Test
    @DisplayName("GET /api/behavior-rules/versions/{id}/latest-ab-run returns 200+null when no run yet "
            + "(FE-BE contract C4 — NOT 404 so FE doesn't need try/catch on the initial state)")
    void latestAbRun_noRun_returns200WithNullBody() throws Exception {
        when(abRunRepository.findByCandidateVersionIdOrderByStartedAtDesc("br-v2-uuid"))
                .thenReturn(List.of());

        mvc.perform(get("/api/behavior-rules/versions/{id}/latest-ab-run", "br-v2-uuid"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("")); // Spring serializes null body as empty
    }

    @Test
    @DisplayName("GET /api/behavior-rules/versions/{id}/latest-ab-run returns most recent by startedAt (r2-BE-4: repo-side ordering)")
    void latestAbRun_returnsLatestByStartedAt() throws Exception {
        com.skillforge.server.entity.BehaviorRuleAbRunEntity newer =
                new com.skillforge.server.entity.BehaviorRuleAbRunEntity();
        newer.setId("ab-new");
        newer.setAgentId("42");
        newer.setCandidateVersionId("br-v2-uuid");
        newer.setBaselineVersionId("");
        newer.setStatus("RUNNING");
        newer.setAbRunKind("with_vs_without");
        newer.setStartedAt(Instant.parse("2026-05-24T10:00:00Z"));

        // r2-BE-4: controller now delegates ordering to repo finder.
        when(abRunRepository.findFirstByCandidateVersionIdOrderByStartedAtDesc("br-v2-uuid"))
                .thenReturn(Optional.of(newer));

        mvc.perform(get("/api/behavior-rules/versions/{id}/latest-ab-run", "br-v2-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ab-new"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.candidateVersionId").value("br-v2-uuid"))
                // dualCriteriaSatisfied is the derived flag — non-null on wire.
                .andExpect(jsonPath("$.dualCriteriaSatisfied").exists());
    }

    @Test
    @DisplayName("r2-BE-1: GET /latest-ab-run — agent deleted (orphan ab_run) → ownerAgentRole null "
            + "(NOT 'general' which would mislead FE)")
    void latestAbRun_agentDeleted_ownerRoleNull() throws Exception {
        com.skillforge.server.entity.BehaviorRuleAbRunEntity run =
                new com.skillforge.server.entity.BehaviorRuleAbRunEntity();
        run.setId("ab-orphan");
        run.setAgentId("999");  // agent_id pointing to a deleted row
        run.setCandidateVersionId("br-v2-uuid");
        run.setBaselineVersionId("");
        run.setStatus("COMPLETED");
        run.setAbRunKind("with_vs_without");
        run.setStartedAt(Instant.parse("2026-05-25T10:00:00Z"));
        when(abRunRepository.findFirstByCandidateVersionIdOrderByStartedAtDesc("br-v2-uuid"))
                .thenReturn(Optional.of(run));
        // Simulate orphan: AgentRepository.findById(999) returns empty.
        when(agentRepository.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/behavior-rules/versions/{id}/latest-ab-run", "br-v2-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ab-orphan"))
                // Critical: ownerAgentRole must serialize as JSON null, NOT
                // "general" — FE renders `data?.ownerAgentRole && <Tag>` and
                // would otherwise show a misleading "General" tag for a row
                // whose agent has been deleted. Jackson serializes null
                // record fields by default, so the field is present with
                // value null.
                .andExpect(jsonPath("$.ownerAgentRole").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("r2-BE-1: GET /latest-ab-run — agent exists w/ known name → ownerAgentRole = resolved role")
    void latestAbRun_agentExists_ownerRoleResolved() throws Exception {
        com.skillforge.server.entity.BehaviorRuleAbRunEntity run =
                new com.skillforge.server.entity.BehaviorRuleAbRunEntity();
        run.setId("ab-design");
        run.setAgentId("1");
        run.setCandidateVersionId("br-v2-uuid");
        run.setBaselineVersionId("");
        run.setStatus("COMPLETED");
        run.setAbRunKind("with_vs_without");
        run.setStartedAt(Instant.parse("2026-05-25T10:00:00Z"));
        when(abRunRepository.findFirstByCandidateVersionIdOrderByStartedAtDesc("br-v2-uuid"))
                .thenReturn(Optional.of(run));
        com.skillforge.server.entity.AgentEntity agent =
                new com.skillforge.server.entity.AgentEntity();
        agent.setId(1L);
        agent.setName("Design Agent");
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));

        mvc.perform(get("/api/behavior-rules/versions/{id}/latest-ab-run", "br-v2-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ab-design"))
                .andExpect(jsonPath("$.ownerAgentRole").value("design"));
    }

    // ───────────────────────── POST run-ab / promote error mapping (r2-BE-1) ─────────────

    @Test
    @DisplayName("r2-BE-1: POST /promote dual-criteria-fail → 400 + body.reason contains 'Dual-criteria'")
    void promote_dualCriteriaFail_returns400WithReason() throws Exception {
        BehaviorRulePromotionService promotionService = retrievePromotionMock();
        when(promotionService.promoteManual(eqStr("v1"), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new IllegalStateException("Dual-criteria not satisfied: target_delta=5.0 ..."));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/behavior-rules/versions/{id}/promote", "v1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("Dual-criteria")));
    }

    @Test
    @DisplayName("r2-BE-1: POST /promote no completed run → 400 + body.reason contains 'No completed'")
    void promote_noCompletedRun_returns400() throws Exception {
        BehaviorRulePromotionService promotionService = retrievePromotionMock();
        when(promotionService.promoteManual(eqStr("v1"), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new IllegalStateException("No completed A/B run for version v1 — run A/B first"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/behavior-rules/versions/{id}/promote", "v1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("No completed")));
    }

    @Test
    @DisplayName("r2-BE-1: POST /promote version not found → 404 + body.reason")
    void promote_versionNotFound_returns404() throws Exception {
        BehaviorRulePromotionService promotionService = retrievePromotionMock();
        when(promotionService.promoteManual(eqStr("ghost"), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new IllegalArgumentException("BehaviorRuleVersion not found: ghost"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/behavior-rules/versions/{id}/promote", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("not found")));
    }

    @Test
    @DisplayName("r2-BE-1: POST /run-ab non-candidate → 400 + body.reason contains 'Only candidate'")
    void runAb_nonCandidate_returns400() throws Exception {
        BehaviorRuleAbEvalService abEvalService = retrieveAbEvalMock();
        when(abEvalService.startAbForVersion(eqStr("v1"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("Only candidate versions can start A/B: id=v1 state=active"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/behavior-rules/versions/{id}/run-ab", "v1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("Only candidate")));
    }

    // ───────── helpers to introspect controller's mock dependencies ─────────

    private static String eqStr(String s) { return org.mockito.ArgumentMatchers.eq(s); }

    /** Helper to retrieve the mock plumbed into setUp()'s controller ctor.
     *  We re-spin a fresh controller for these tests so we can stub the mock,
     *  since the @BeforeEach setUp() created throwaway mocks. */
    private BehaviorRulePromotionService retrievePromotionMock() {
        return rebuildControllerWithStubbableMocks().promotionMock;
    }

    private BehaviorRuleAbEvalService retrieveAbEvalMock() {
        return rebuildControllerWithStubbableMocks().abEvalMock;
    }

    private static class Wired {
        BehaviorRulePromotionService promotionMock;
        BehaviorRuleAbEvalService abEvalMock;
    }

    private Wired rebuildControllerWithStubbableMocks() {
        Wired w = new Wired();
        w.promotionMock = mock(BehaviorRulePromotionService.class);
        w.abEvalMock = mock(BehaviorRuleAbEvalService.class);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BehaviorRuleVersionController controller =
                new BehaviorRuleVersionController(
                        versionRepository, abRunRepository,
                        w.abEvalMock, w.promotionMock, om,
                        agentRepository,
                        new AgentRoleResolver());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
        return w;
    }
}
