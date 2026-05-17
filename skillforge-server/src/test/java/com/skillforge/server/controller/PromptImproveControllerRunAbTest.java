package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.improve.ImprovementConflictException;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.PromptPromotionService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.4g (2026-05-17) — focused contract test for the
 * {@code POST /api/agents/{agentId}/prompt-versions/{versionId}/run-ab} endpoint
 * added in Phase 1.4b. Mirrors the lightweight Mockito-direct-controller
 * pattern of {@link SkillDraftControllerTest} (no {@code @WebMvcTest} since
 * the endpoint just delegates to {@link PromptImproverService#runAbTestAgainst}
 * with exception → HTTP status mapping).
 *
 * <p>Coverage:
 * <ul>
 *   <li>happy path → 202 Accepted + body abRunId</li>
 *   <li>candidate not found ({@link IllegalArgumentException}) → 400 with
 *       error code (the controller does not yet 404-discriminate on prompt
 *       path — message-text lock comes only via the skill-draft controller
 *       which has the "not found" heuristic split)</li>
 *   <li>ephemeral fallback path (null scenarios + service-internal fallback) →
 *       same 202 + abRunId shape (response doesn't surface isEphemeral yet)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromptImproveController /run-ab endpoint (Phase 1.4g)")
class PromptImproveControllerRunAbTest {

    @Mock private PromptImproverService promptImproverService;
    @Mock private PromptPromotionService promptPromotionService;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private AgentRepository agentRepository;

    private PromptImproveController controller;

    @BeforeEach
    void setUp() {
        controller = new PromptImproveController(
                promptImproverService, promptPromotionService,
                promptAbRunRepository, promptVersionRepository,
                agentRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("happy: explicit scenarios → service.runAbTestAgainst called with "
            + "candidate UUID + scenarios; 202 + body abRunId")
    void runAb_happy_returnsAbRunId() {
        when(promptImproverService.runAbTestAgainst(
                eq("7"), isNull(), eq("candidate-uuid-1"), any()))
                .thenReturn("ab-run-id-happy");

        Map<String, Object> body = new HashMap<>();
        body.put("evalScenarioIds", List.of("scen-1", "scen-2"));

        ResponseEntity<?> resp = controller.runAbAgainstCandidate(
                "7", "candidate-uuid-1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody).isNotNull();
        assertThat(respBody.get("abRunId")).isEqualTo("ab-run-id-happy");
        assertThat(respBody.get("candidateVersionId")).isEqualTo("candidate-uuid-1");
        assertThat(respBody.get("agentId")).isEqualTo("7");
    }

    @Test
    @DisplayName("candidate not found: IllegalArgumentException w/ \"not found\" → 404 NOT_FOUND "
            + "(generic message, internal detail logged only)")
    void runAb_candidateNotFound_returns404() {
        when(promptImproverService.runAbTestAgainst(
                anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalArgumentException(
                        "Candidate prompt version not found: missing-uuid"));

        ResponseEntity<?> resp = controller.runAbAgainstCandidate(
                "7", "missing-uuid", null);

        // F3 fix (Phase 2 r2): controller now routes IAE-w/-"not found" to 404
        // (was 400 pre-fix) AND returns generic message instead of leaking
        // internal architecture text. The "not found" heuristic is preserved
        // as the status-code pivot but only appears in server log, not body.
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody).isNotNull();
        assertThat(respBody.get("error")).isEqualTo("NOT_FOUND");
        assertThat(respBody.get("message")).isEqualTo("Resource not found");
    }

    @Test
    @DisplayName("ephemeral fallback: null body / null scenarios → service-internal fallback → "
            + "202 + abRunId (response shape unchanged from explicit path)")
    void runAb_ephemeralFallback_returnsAbRunId() {
        when(promptImproverService.runAbTestAgainst(
                eq("7"), isNull(), eq("candidate-uuid-2"), isNull()))
                .thenReturn("ab-run-id-ephemeral");

        // null body — exercises the null-guard branch in the controller body.
        ResponseEntity<?> resp = controller.runAbAgainstCandidate(
                "7", "candidate-uuid-2", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("abRunId")).isEqualTo("ab-run-id-ephemeral");
    }

    @Test
    @DisplayName("active A/B conflict (ImprovementConflictException) → 409 CONFLICT")
    void runAb_activeAbConflict_returns409() {
        when(promptImproverService.runAbTestAgainst(
                anyString(), any(), anyString(), any()))
                .thenThrow(new ImprovementConflictException(
                        "An A/B run is already active for agent 7"));

        ResponseEntity<?> resp = controller.runAbAgainstCandidate(
                "7", "candidate-uuid-3", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("error")).isEqualTo("CONFLICT");
        // F3 fix: generic message, internal detail logged only.
        assertThat(respBody.get("message")).isEqualTo("Operation conflicts with current state");
    }
}
