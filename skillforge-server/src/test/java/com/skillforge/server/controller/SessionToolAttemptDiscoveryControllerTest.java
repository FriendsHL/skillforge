package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.config.AuthInterceptor;
import com.skillforge.server.config.PlatformAccessPrincipal;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.session.UnknownOutcomeResolutionDiscovery;
import com.skillforge.server.session.UnknownOutcomeResolutionActor;
import com.skillforge.server.session.UnknownOutcomeResolutionAck;
import com.skillforge.server.session.UnknownOutcomeResolutionException;
import com.skillforge.server.session.UnknownOutcomeResolutionService;
import com.skillforge.server.session.UnknownOutcomeResolutionDiscoveryService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class SessionToolAttemptDiscoveryControllerTest {

    @Test
    void sessionOwnerCanDiscoverExactCandidateUsingServerPrincipal() {
        SessionHistoryProperties properties = enabled();
        UnknownOutcomeResolutionDiscoveryService service =
                mock(UnknownOutcomeResolutionDiscoveryService.class);
        SessionToolAttemptDiscoveryController controller =
                new SessionToolAttemptDiscoveryController(properties, service);
        HttpServletRequest request = principalRequest(
                PlatformAccessPrincipal.authenticatedUser(11L));
        UnknownOutcomeResolutionDiscovery candidate = candidate(
                UnknownOutcomeResolutionAck.ActorAuthority.OWNER);
        when(service.find(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(candidate));

        var response = controller.findUnknownOutcome("session-1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(candidate);
        JsonNode wire = new ObjectMapper().valueToTree(response.getBody());
        assertThat(wire.path("actorAuthority").textValue()).isEqualTo("OWNER");
        assertThat(wire.path("calls").size()).isEqualTo(1);
        assertThat(wire.path("calls").get(0).path("toolName").textValue())
                .isEqualTo("FileWrite");
        assertThat(wire.path("calls").get(0).path("input").textValue())
                .isEqualTo("{\"path\":\"/tmp/a\"}");
        assertThat(wire.has("manifestHash")).isFalse();
        ArgumentCaptor<UnknownOutcomeResolutionActor> actor =
                ArgumentCaptor.forClass(UnknownOutcomeResolutionActor.class);
        verify(service).find(org.mockito.ArgumentMatchers.eq("session-1"), actor.capture());
        assertThat(actor.getValue().actorId()).isEqualTo(11L);
        assertThat(actor.getValue().authorities()).isEmpty();
    }

    @Test
    void explicitAdminPermissionCanDiscoverCandidate() {
        SessionHistoryProperties properties = enabled();
        UnknownOutcomeResolutionDiscoveryService service =
                mock(UnknownOutcomeResolutionDiscoveryService.class);
        SessionToolAttemptDiscoveryController controller =
                new SessionToolAttemptDiscoveryController(properties, service);
        PlatformAccessPrincipal principal = PlatformAccessPrincipal.platformAdmin(
                42L, Set.of(UnknownOutcomeResolutionService.ADMIN_AUTHORITY));
        UnknownOutcomeResolutionDiscovery candidate = candidate(
                UnknownOutcomeResolutionAck.ActorAuthority.ADMIN);
        when(service.find(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(candidate));

        var response = controller.findUnknownOutcome(
                "session-1", principalRequest(principal));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<UnknownOutcomeResolutionActor> actor =
                ArgumentCaptor.forClass(UnknownOutcomeResolutionActor.class);
        verify(service).find(org.mockito.ArgumentMatchers.eq("session-1"), actor.capture());
        assertThat(actor.getValue().actorId()).isEqualTo(42L);
        assertThat(actor.getValue().authorities())
                .containsExactly(UnknownOutcomeResolutionService.ADMIN_AUTHORITY);
    }

    @Test
    void noCandidateReturnsNoContent() {
        SessionHistoryProperties properties = enabled();
        UnknownOutcomeResolutionDiscoveryService service =
                mock(UnknownOutcomeResolutionDiscoveryService.class);
        SessionToolAttemptDiscoveryController controller =
                new SessionToolAttemptDiscoveryController(properties, service);
        when(service.find(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        var response = controller.findUnknownOutcome(
                "session-1", principalRequest(PlatformAccessPrincipal.authenticatedUser(11L)));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void missingTrustedPrincipalFailsBeforeDiscovery() {
        SessionHistoryProperties properties = enabled();
        UnknownOutcomeResolutionDiscoveryService service =
                mock(UnknownOutcomeResolutionDiscoveryService.class);
        SessionToolAttemptDiscoveryController controller =
                new SessionToolAttemptDiscoveryController(properties, service);

        var response = controller.findUnknownOutcome(
                "secret-session", mock(HttpServletRequest.class));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).find(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void masterOffHidesEndpointBeforeDiscovery() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        UnknownOutcomeResolutionDiscoveryService service =
                mock(UnknownOutcomeResolutionDiscoveryService.class);
        SessionToolAttemptDiscoveryController controller =
                new SessionToolAttemptDiscoveryController(properties, service);

        var response = controller.findUnknownOutcome(
                "secret-session", principalRequest(PlatformAccessPrincipal.platformAdmin()));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(service, never()).find(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void crossOwnerAndMissingSessionUseSameGenericConflict() {
        SessionHistoryProperties properties = enabled();
        UnknownOutcomeResolutionDiscoveryService service =
                mock(UnknownOutcomeResolutionDiscoveryService.class);
        SessionToolAttemptDiscoveryController controller =
                new SessionToolAttemptDiscoveryController(properties, service);
        UnknownOutcomeResolutionException unavailable =
                mock(UnknownOutcomeResolutionException.class);
        when(service.find(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenThrow(unavailable);

        var crossOwner = controller.findUnknownOutcome(
                "owned-by-someone-else",
                principalRequest(PlatformAccessPrincipal.authenticatedUser(99L)));
        var missing = controller.findUnknownOutcome(
                "missing",
                principalRequest(PlatformAccessPrincipal.authenticatedUser(99L)));

        assertThat(crossOwner.getStatusCode().value()).isEqualTo(409);
        assertThat(missing.getStatusCode().value()).isEqualTo(409);
        assertThat(crossOwner.getBody()).isEqualTo(missing.getBody());
    }

    private static SessionHistoryProperties enabled() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static HttpServletRequest principalRequest(PlatformAccessPrincipal principal) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE))
                .thenReturn(principal);
        return request;
    }

    private static UnknownOutcomeResolutionDiscovery candidate(
            UnknownOutcomeResolutionAck.ActorAuthority authority) {
        return new UnknownOutcomeResolutionDiscovery(
                "session-1", 17L, 3L, 5L, 7L,
                "UNCERTAIN_PENDING_RESOLUTION", authority,
                List.of(new UnknownOutcomeResolutionDiscovery.ToolCall(
                        0, "tool-use-1", "FileWrite", "{\"path\":\"/tmp/a\"}")),
                List.of());
    }
}
