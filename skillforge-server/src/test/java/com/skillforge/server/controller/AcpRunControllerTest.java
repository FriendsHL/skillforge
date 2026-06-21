package com.skillforge.server.controller;

import com.skillforge.server.acp.AcpAgentRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AcpRunController}'s trigger endpoint (P1a-2).
 *
 * <p>P1c-2 (Seam 2): the separate ACP confirmation endpoint that used to live on
 * this controller has been UNIFIED into {@code ChatController POST
 * /api/chat/{sessionId}/confirmation} (→ {@code ChatService.answerConfirmation},
 * discriminated engine-vs-ACP internally). The ownership / binding / routing
 * assertions that used to live here now live in
 * {@code ChatServiceConfirmationRoutingTest} (routing) + the ChatController
 * confirmation tests (ownership gate). This test only covers the run trigger.
 */
class AcpRunControllerTest {

    private static final String SESSION_ID = "sub-1";
    private static final long OWNER = 1L;

    private AcpAgentRunner runner;
    private AcpRunController controller;

    @BeforeEach
    void setUp() {
        runner = mock(AcpAgentRunner.class);
        controller = new AcpRunController(runner);
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

    @Test
    @DisplayName("run endpoint requires a non-blank prompt (400)")
    void run_requiresPrompt() {
        ResponseEntity<?> resp = controller.run(
                new AcpRunController.RunRequest("  ", null, OWNER));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(runner, never()).run(any(), any(), any());
    }
}
