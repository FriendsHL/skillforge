package com.skillforge.server.controller;

import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.acp.AcpAgentRunner;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership / authorization tests for {@link AcpRunController}'s confirmation
 * endpoint (BLOCKER-1b). Plain unit test — runner + registry + sessionService
 * mocked; no Spring context, no real cc.
 */
class AcpRunControllerTest {

    private static final String SESSION_ID = "sub-1";
    private static final long OWNER = 1L;
    private static final long ATTACKER = 2L;
    private static final String CONF_ID = "conf-1";

    private AcpAgentRunner runner;
    private PendingConfirmationRegistry registry;
    private SessionService sessionService;
    private AcpRunController controller;

    @BeforeEach
    void setUp() {
        runner = mock(AcpAgentRunner.class);
        registry = mock(PendingConfirmationRegistry.class);
        sessionService = mock(SessionService.class);
        controller = new AcpRunController(runner, registry, sessionService);
    }

    private SessionEntity sessionOwnedBy(Long userId) {
        SessionEntity s = new SessionEntity();
        s.setId(SESSION_ID);
        s.setUserId(userId);
        return s;
    }

    private PendingConfirmation pendingFor(String sessionId) {
        return new PendingConfirmation(CONF_ID, sessionId, "tc-1", "edit", "x", "", null, 300);
    }

    @Test
    @DisplayName("BLOCKER-1b: a different user answering another user's confirmation is rejected (403), latch not woken")
    void differentUser_rejected() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));

        ResponseEntity<?> resp = controller.answerConfirmation(SESSION_ID,
                new AcpRunController.ConfirmRequest(CONF_ID, "approved", ATTACKER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(registry, never()).complete(any(), any(), any());
        verify(registry, never()).peek(any());
    }

    @Test
    @DisplayName("BLOCKER-1b: a confirmationId bound to a DIFFERENT session is rejected (404), latch not woken")
    void wrongSession_rejected() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));
        // The pending confirmation belongs to some OTHER session, not the path var.
        when(registry.peek(CONF_ID)).thenReturn(pendingFor("some-other-session"));

        ResponseEntity<?> resp = controller.answerConfirmation(SESSION_ID,
                new AcpRunController.ConfirmRequest(CONF_ID, "approved", OWNER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(registry, never()).complete(any(), any(), any());
    }

    @Test
    @DisplayName("owner answering their own confirmation succeeds and wakes the latch")
    void owner_succeeds() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));
        when(registry.peek(CONF_ID)).thenReturn(pendingFor(SESSION_ID));
        when(registry.complete(eq(CONF_ID), eq(Decision.APPROVED), any())).thenReturn(true);

        ResponseEntity<?> resp = controller.answerConfirmation(SESSION_ID,
                new AcpRunController.ConfirmRequest(CONF_ID, "approved", OWNER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registry).complete(CONF_ID, Decision.APPROVED, null);
    }

    @Test
    @DisplayName("security-W1: decision=timeout from the wire is rejected (400)")
    void timeoutDecision_rejected() {
        ResponseEntity<?> resp = controller.answerConfirmation(SESSION_ID,
                new AcpRunController.ConfirmRequest(CONF_ID, "timeout", OWNER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(registry, never()).complete(any(), any(), any());
    }

    @Test
    @DisplayName("missing userId on confirmation is rejected (400)")
    void missingUserId_rejected() {
        ResponseEntity<?> resp = controller.answerConfirmation(SESSION_ID,
                new AcpRunController.ConfirmRequest(CONF_ID, "approved", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(registry, never()).complete(any(), any(), any());
    }

    @Test
    @DisplayName("nonexistent session on confirmation is 404 (existence not leaked)")
    void unknownSession_404() {
        when(sessionService.getSession(SESSION_ID)).thenThrow(new RuntimeException("not found"));

        ResponseEntity<?> resp = controller.answerConfirmation(SESSION_ID,
                new AcpRunController.ConfirmRequest(CONF_ID, "approved", OWNER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(registry, never()).complete(any(), any(), any());
    }

    @Test
    @DisplayName("run endpoint requires userId (400) and otherwise owns the run by the caller")
    void run_requiresUserId_andPassesOwner() {
        ResponseEntity<?> missing = controller.run(
                new AcpRunController.RunRequest("do x", null, null));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(runner, never()).run(any(), any(), any());

        when(runner.run("do x", null, OWNER)).thenReturn(SESSION_ID);
        ResponseEntity<?> ok = controller.run(
                new AcpRunController.RunRequest("do x", null, OWNER));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(runner).run("do x", null, OWNER); // BLOCKER-1a: caller userId plumbed through
    }
}
