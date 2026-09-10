package com.skillforge.server.controller;

import com.skillforge.server.config.AuthInterceptor;
import com.skillforge.server.config.PlatformAccessPrincipal;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.session.UnknownOutcomeResolutionAck;
import com.skillforge.server.session.UnknownOutcomeResolutionActor;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest;
import com.skillforge.server.session.UnknownOutcomeResolutionService;
import com.skillforge.server.session.UnknownOutcomePostActionOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionToolAttemptResolutionControllerTest {

    @Test
    void explicitAdminPermissionUsesServerPrincipalActor_notRequestIdentity() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        UnknownOutcomePostActionOrchestrator service =
                mock(UnknownOutcomePostActionOrchestrator.class);
        SessionToolAttemptResolutionController controller =
                new SessionToolAttemptResolutionController(properties, service);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE))
                .thenReturn(PlatformAccessPrincipal.platformAdmin(
                        42L, Set.of(UnknownOutcomeResolutionService.ADMIN_AUTHORITY)));
        UnknownOutcomeResolutionRequest command = command();
        UnknownOutcomeResolutionAck acknowledgement = acknowledgement(command);
        when(service.resolve(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(command)))
                .thenReturn(acknowledgement);

        var response = controller.resolveUnknown(
                "session-1", 17L, command, servletRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(acknowledgement);
        ArgumentCaptor<UnknownOutcomeResolutionActor> actor =
                ArgumentCaptor.forClass(UnknownOutcomeResolutionActor.class);
        verify(service).resolve(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq(17L), actor.capture(),
                org.mockito.ArgumentMatchers.eq(command));
        assertThat(actor.getValue().actorId()).isEqualTo(42L);
        assertThat(actor.getValue().authorities())
                .containsExactly(UnknownOutcomeResolutionService.ADMIN_AUTHORITY);
    }

    @Test
    void ownerPrincipalIsForwardedWithoutManufacturedAdminPermission() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        UnknownOutcomePostActionOrchestrator service =
                mock(UnknownOutcomePostActionOrchestrator.class);
        SessionToolAttemptResolutionController controller =
                new SessionToolAttemptResolutionController(properties, service);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE))
                .thenReturn(PlatformAccessPrincipal.authenticatedUser(11L));
        UnknownOutcomeResolutionRequest command = command();
        UnknownOutcomeResolutionAck acknowledgement = new UnknownOutcomeResolutionAck(
                command.resolutionRequestId(), "session-1", 17L, UUID.randomUUID(),
                3L, 5L, 7L, UnknownOutcomeResolutionAck.ActorAuthority.OWNER,
                command.action(), UUID.randomUUID(), "RESOLVED_UNKNOWN", List.of(),
                "PENDING", false, 19L, Instant.now());
        when(service.resolve(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(command)))
                .thenReturn(acknowledgement);

        var response = controller.resolveUnknown(
                "session-1", 17L, command, servletRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<UnknownOutcomeResolutionActor> actor =
                ArgumentCaptor.forClass(UnknownOutcomeResolutionActor.class);
        verify(service).resolve(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq(17L), actor.capture(),
                org.mockito.ArgumentMatchers.eq(command));
        assertThat(actor.getValue().actorId()).isEqualTo(11L);
        assertThat(actor.getValue().authorities()).isEmpty();
    }

    @Test
    void missingTrustedPrincipalFailsBeforeServiceLookup() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        UnknownOutcomePostActionOrchestrator service =
                mock(UnknownOutcomePostActionOrchestrator.class);
        SessionToolAttemptResolutionController controller =
                new SessionToolAttemptResolutionController(properties, service);

        var response = controller.resolveUnknown(
                "secret-session", 17L, command(), mock(HttpServletRequest.class));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).resolve(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void masterOffHidesEndpointAndNeverTouchesService() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        UnknownOutcomePostActionOrchestrator service =
                mock(UnknownOutcomePostActionOrchestrator.class);
        SessionToolAttemptResolutionController controller =
                new SessionToolAttemptResolutionController(properties, service);

        var response = controller.resolveUnknown(
                "secret-session", 17L, command(), mock(HttpServletRequest.class));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(service, never()).resolve(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static UnknownOutcomeResolutionRequest command() {
        return new UnknownOutcomeResolutionRequest(
                UUID.randomUUID(), 3L, 5L, 7L,
                UnknownOutcomeResolutionRequest.PostAction.CONTINUE_CURRENT_TIMELINE,
                "Operator inspected the external system",
                List.of());
    }

    private static UnknownOutcomeResolutionAck acknowledgement(
            UnknownOutcomeResolutionRequest command) {
        return new UnknownOutcomeResolutionAck(
                command.resolutionRequestId(), "session-1", 17L, UUID.randomUUID(),
                3L, 5L, 7L, UnknownOutcomeResolutionAck.ActorAuthority.ADMIN,
                command.action(), UUID.randomUUID(), "RESOLVED_UNKNOWN", List.of(),
                "PENDING", false, 19L, Instant.now());
    }
}
