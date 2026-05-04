package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.HighSimilarityRejectedException;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.improve.SkillNameConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan r2 §8 + Code Judge r1 B-BE-2 — contract test for the
 * {@code PATCH /api/skill-drafts/{id}} endpoint. Exercises every branch the FE relies on:
 * <ul>
 *   <li>JSON body field name: {@code reviewedBy} (not {@code userId})</li>
 *   <li>Missing reviewedBy → 400</li>
 *   <li>Action approve / discard dispatch</li>
 *   <li>{@code forceCreate} bool plumbed into service</li>
 *   <li>{@link HighSimilarityRejectedException} mapped to 409 with {@code code=HIGH_SIMILARITY}</li>
 * </ul>
 * <p>ObjectMapper deserialisation of the wire JSON is exercised in
 * {@link #wireJson_isCompatibleWithFePayloadShape()} — verifies BE-side parsing matches what
 * FE actually sends.
 */
@ExtendWith(MockitoExtension.class)
class SkillDraftControllerTest {

    @Mock private SkillDraftService skillDraftService;
    private ExecutorService coordinatorExecutor;
    private SkillDraftController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        coordinatorExecutor = Executors.newSingleThreadExecutor();
        controller = new SkillDraftController(skillDraftService, coordinatorExecutor);
    }

    private SkillDraftEntity newApprovedResult(String id) {
        SkillDraftEntity e = new SkillDraftEntity();
        e.setId(id);
        e.setOwnerId(7L);
        e.setName("Foo");
        e.setStatus("approved");
        return e;
    }

    @Test
    @DisplayName("approve action: reviewedBy + forceCreate plumbed into service; 200 + body")
    void approve_dispatchesToService() {
        SkillDraftEntity stub = newApprovedResult("d1");
        when(skillDraftService.approveDraft(eq("d1"), eq(11L), eq(true))).thenReturn(stub);

        Map<String, Object> body = new HashMap<>();
        body.put("action", "approve");
        body.put("reviewedBy", 11);
        body.put("forceCreate", true);

        ResponseEntity<Map<String, Object>> resp = controller.reviewDraft("d1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("status")).isEqualTo("approved");
        verify(skillDraftService).approveDraft("d1", 11L, true);
    }

    @Test
    @DisplayName("approve action: forceCreate omitted → defaults to false")
    void approve_forceCreateOmitted_defaultsFalse() {
        when(skillDraftService.approveDraft(any(), any(), eq(false))).thenReturn(newApprovedResult("d1"));

        Map<String, Object> body = new HashMap<>();
        body.put("action", "approve");
        body.put("reviewedBy", 11);

        controller.reviewDraft("d1", body);

        verify(skillDraftService).approveDraft("d1", 11L, false);
    }

    @Test
    @DisplayName("discard action: dispatched without forceCreate")
    void discard_dispatchesToService() {
        SkillDraftEntity stub = newApprovedResult("d2");
        stub.setStatus("discarded");
        when(skillDraftService.discardDraft(eq("d2"), eq(11L))).thenReturn(stub);

        Map<String, Object> body = new HashMap<>();
        body.put("action", "discard");
        body.put("reviewedBy", 11);

        ResponseEntity<Map<String, Object>> resp = controller.reviewDraft("d2", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("status")).isEqualTo("discarded");
        verify(skillDraftService).discardDraft("d2", 11L);
    }

    @Test
    @DisplayName("missing reviewedBy → 400")
    void missingReviewedBy_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "approve");

        ResponseEntity<Map<String, Object>> resp = controller.reviewDraft("d1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("error")).isEqualTo("reviewedBy is required");
        verify(skillDraftService, never()).approveDraft(any(), any(), anyBoolean());
        verify(skillDraftService, never()).discardDraft(any(), any());
    }

    @Test
    @DisplayName("invalid action → 400")
    void invalidAction_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "purge");
        body.put("reviewedBy", 1);

        ResponseEntity<Map<String, Object>> resp = controller.reviewDraft("d1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("error")).asString().contains("'approve'");
    }

    @Test
    @DisplayName("HighSimilarityRejectedException → 409 + HIGH_SIMILARITY structured body")
    void highSimilarityRejected_returns409() {
        when(skillDraftService.approveDraft(eq("d1"), eq(11L), eq(false)))
                .thenThrow(new HighSimilarityRejectedException(
                        "Draft has high similarity (0.92) with 'ExistingSkill'.",
                        0.92, 7L, "ExistingSkill"));

        Map<String, Object> body = new HashMap<>();
        body.put("action", "approve");
        body.put("reviewedBy", 11);
        body.put("forceCreate", false);

        ResponseEntity<Map<String, Object>> resp = controller.reviewDraft("d1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("code")).isEqualTo("HIGH_SIMILARITY");
        assertThat(resp.getBody().get("similarity")).isEqualTo(0.92);
        assertThat(resp.getBody().get("mergeCandidateId")).isEqualTo(7L);
        assertThat(resp.getBody().get("mergeCandidateName")).isEqualTo("ExistingSkill");
    }

    @Test
    @DisplayName("SkillNameConflictException → 409 + NAME_CONFLICT structured body")
    void reviewDraft_nameConflict_returns409() {
        when(skillDraftService.approveDraft(eq("d1"), eq(11L), eq(false)))
                .thenThrow(new SkillNameConflictException(
                        "Skill named 'MyName' already exists for this owner.",
                        "MyName"));

        Map<String, Object> body = new HashMap<>();
        body.put("action", "approve");
        body.put("reviewedBy", 11);

        ResponseEntity<Map<String, Object>> resp = controller.reviewDraft("d1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("code")).isEqualTo("NAME_CONFLICT");
        assertThat(resp.getBody().get("existingSkillName")).isEqualTo("MyName");
        assertThat(resp.getBody().get("error")).asString().contains("already exists");
    }

    @Test
    @DisplayName("wire JSON shape (FE-sent) deserialises into the body Map the controller expects")
    @SuppressWarnings("unchecked")
    void wireJson_isCompatibleWithFePayloadShape() throws Exception {
        // This is exactly what FE's reviewSkillDraft sends — the contract test that catches
        // the original B-BE-2 / B-FE-1 field-name mismatch before it ships.
        String fePayload = "{\"action\":\"approve\",\"reviewedBy\":11,\"forceCreate\":true}";

        Map<String, Object> parsed = objectMapper.readValue(fePayload, Map.class);

        // Sanity-check: keys are exactly what reviewDraft reads (action / reviewedBy / forceCreate).
        assertThat(parsed).containsOnlyKeys("action", "reviewedBy", "forceCreate");

        when(skillDraftService.approveDraft(eq("d1"), eq(11L), eq(true)))
                .thenReturn(newApprovedResult("d1"));

        ResponseEntity<Map<String, Object>> resp = controller.reviewDraft("d1", parsed);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(skillDraftService).approveDraft("d1", 11L, true);
    }
}
