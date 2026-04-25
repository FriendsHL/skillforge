package com.skillforge.server.controller;

import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.service.AgentAuthoredHookService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAuthoredHookControllerTest {

    @Test
    void approve_ignoresReviewerUserIdFromRequestBody() {
        FakeService service = new FakeService();
        AgentAuthoredHookController controller = new AgentAuthoredHookController(service);

        ResponseEntity<?> response = controller.approve(7L,
                Map.of("reviewerUserId", 12345L, "reviewNote", "looks ok"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(service.capturedReviewerUserId).isNull();
        assertThat(service.capturedReviewNote).isEqualTo("looks ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("reviewedByUserId", null);
    }

    @Test
    void reject_ignoresReviewerUserIdFromRequestBody() {
        FakeService service = new FakeService();
        AgentAuthoredHookController controller = new AgentAuthoredHookController(service);

        ResponseEntity<?> response = controller.reject(7L,
                Map.of("reviewerUserId", 12345L, "reviewNote", "no"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(service.capturedReviewerUserId).isNull();
        assertThat(service.capturedReviewNote).isEqualTo("no");
    }

    private static final class FakeService extends AgentAuthoredHookService {
        private Long capturedReviewerUserId;
        private String capturedReviewNote;

        private FakeService() {
            super(null, null);
        }

        @Override
        public AgentAuthoredHookEntity approve(Long id, Long reviewerUserId, String reviewNote) {
            capturedReviewerUserId = reviewerUserId;
            capturedReviewNote = reviewNote;
            return row(id, AgentAuthoredHookEntity.STATE_APPROVED, reviewerUserId, reviewNote);
        }

        @Override
        public AgentAuthoredHookEntity reject(Long id, Long reviewerUserId, String reviewNote) {
            capturedReviewerUserId = reviewerUserId;
            capturedReviewNote = reviewNote;
            return row(id, AgentAuthoredHookEntity.STATE_REJECTED, reviewerUserId, reviewNote);
        }

        private static AgentAuthoredHookEntity row(Long id,
                                                   String state,
                                                   Long reviewerUserId,
                                                   String reviewNote) {
            AgentAuthoredHookEntity entity = new AgentAuthoredHookEntity();
            entity.setId(id);
            entity.setTargetAgentId(42L);
            entity.setAuthorAgentId(99L);
            entity.setAuthorSessionId("s1");
            entity.setEvent("SessionEnd");
            entity.setMethodKind(AgentAuthoredHookEntity.METHOD_KIND_COMPILED);
            entity.setMethodId(5L);
            entity.setMethodRef("agent.safe");
            entity.setReviewState(state);
            entity.setReviewedByUserId(reviewerUserId);
            entity.setReviewNote(reviewNote);
            entity.setEnabled(AgentAuthoredHookEntity.STATE_APPROVED.equals(state));
            return entity;
        }
    }
}
