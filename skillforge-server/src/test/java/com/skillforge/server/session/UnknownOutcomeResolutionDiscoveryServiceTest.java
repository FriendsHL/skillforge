package com.skillforge.server.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnknownOutcomeResolutionDiscoveryServiceTest {

    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000701";

    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final SessionToolAttemptRepository attemptRepository =
            mock(SessionToolAttemptRepository.class);
    private final SessionMessageInboxRepository inboxRepository =
            mock(SessionMessageInboxRepository.class);
    private final UnknownOutcomeAttemptVerifier attemptVerifier =
            mock(UnknownOutcomeAttemptVerifier.class);
    private UnknownOutcomeResolutionDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new UnknownOutcomeResolutionDiscoveryService(
                sessionRepository, attemptRepository, inboxRepository, attemptVerifier);
    }

    @Test
    void find_ownerReturnsAuthorizedOrderedManifestAndInboxIds() throws Exception {
        SessionEntity session = session();
        SessionToolAttemptEntity attempt = attempt();
        SessionMessageInboxEntity first = inbox(1L, UUID.randomUUID());
        SessionMessageInboxEntity second = inbox(2L, UUID.randomUUID());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(attemptRepository.findBySessionIdAndStateOrderByIdAsc(
                eq(SESSION_ID), eq("UNCERTAIN_PENDING_RESOLUTION"), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(inboxRepository.findBySessionIdOrderByIdAsc(
                eq(SESSION_ID), any(Pageable.class))).thenReturn(List.of(first, second));
        when(attemptVerifier.verify(attempt)).thenReturn(List.of(
                new UnknownOutcomeAttemptVerifier.ManifestCall(
                        0, "tool-use-1", "ShellTool",
                        new ObjectMapper().readTree(
                                "{\"command\":\"</pre><script>alert(1)</script>\"}"))));

        UnknownOutcomeResolutionDiscovery result = service.find(
                SESSION_ID,
                UnknownOutcomeResolutionActor.authenticated(11L, java.util.Set.of()))
                .orElseThrow();

        assertThat(result).isEqualTo(new UnknownOutcomeResolutionDiscovery(
                SESSION_ID, 17L, 3L, 5L, 7L,
                "UNCERTAIN_PENDING_RESOLUTION",
                UnknownOutcomeResolutionAck.ActorAuthority.OWNER,
                List.of(new UnknownOutcomeResolutionDiscovery.ToolCall(
                        0, "tool-use-1", "ShellTool",
                        "{\"command\":\"</pre><script>alert(1)</script>\"}")),
                List.of(first.getInboxId(), second.getInboxId())));
        verify(attemptVerifier).verify(attempt);
    }

    @Test
    void find_explicitAdminPermissionReturnsAdminAuthority() {
        SessionEntity session = session();
        SessionToolAttemptEntity attempt = attempt();
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(attemptRepository.findBySessionIdAndStateOrderByIdAsc(
                eq(SESSION_ID), eq("UNCERTAIN_PENDING_RESOLUTION"), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(inboxRepository.findBySessionIdOrderByIdAsc(
                eq(SESSION_ID), any(Pageable.class))).thenReturn(List.of());
        when(attemptVerifier.verify(attempt)).thenReturn(List.of(
                new UnknownOutcomeAttemptVerifier.ManifestCall(
                        0, "tool-use-1", "FileRead",
                        new ObjectMapper().createObjectNode())));

        UnknownOutcomeResolutionDiscovery result = service.find(
                SESSION_ID,
                UnknownOutcomeResolutionActor.authenticated(
                        99L, java.util.Set.of(UnknownOutcomeResolutionService.ADMIN_AUTHORITY)))
                .orElseThrow();

        assertThat(result.actorAuthority())
                .isEqualTo(UnknownOutcomeResolutionAck.ActorAuthority.ADMIN);
    }

    @Test
    void find_crossOwnerWithoutPermissionFailsLikeMissingSessionBeforeAttemptLookup() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session()));

        assertThatThrownBy(() -> service.find(
                SESSION_ID,
                UnknownOutcomeResolutionActor.authenticated(99L, java.util.Set.of())))
                .isInstanceOf(UnknownOutcomeResolutionException.class)
                .hasMessage("Unknown outcome resolution is not available");

        assertThatThrownBy(() -> service.find(
                "missing-session",
                UnknownOutcomeResolutionActor.authenticated(99L, java.util.Set.of())))
                .isInstanceOf(UnknownOutcomeResolutionException.class)
                .hasMessage("Unknown outcome resolution is not available");
        verify(attemptRepository, never()).findBySessionIdAndStateOrderByIdAsc(
                any(), any(), any(Pageable.class));
    }

    @Test
    void find_noUncertainAttemptReturnsEmptyWithoutReadingInbox() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session()));
        when(attemptRepository.findBySessionIdAndStateOrderByIdAsc(
                eq(SESSION_ID), eq("UNCERTAIN_PENDING_RESOLUTION"), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.find(
                SESSION_ID,
                UnknownOutcomeResolutionActor.authenticated(11L, java.util.Set.of())))
                .isEmpty();

        verify(inboxRepository, never()).findBySessionIdOrderByIdAsc(
                any(), any(Pageable.class));
    }

    @Test
    void find_acceptsCancellationStateAfterSessionLoopClaimWasReleased() {
        SessionEntity session = session();
        session.setActiveLoopId(null);
        session.setLoopOwnerInstanceId(null);
        session.setLoopLeaseUntil(null);
        SessionToolAttemptEntity attempt = attempt();
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(attemptRepository.findBySessionIdAndStateOrderByIdAsc(
                eq(SESSION_ID), eq("UNCERTAIN_PENDING_RESOLUTION"), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(inboxRepository.findBySessionIdOrderByIdAsc(
                eq(SESSION_ID), any(Pageable.class))).thenReturn(List.of());
        when(attemptVerifier.verify(attempt)).thenReturn(List.of(
                new UnknownOutcomeAttemptVerifier.ManifestCall(
                        0, "tool-use-1", "FileRead",
                        new ObjectMapper().createObjectNode())));

        assertThat(service.find(
                SESSION_ID,
                UnknownOutcomeResolutionActor.authenticated(11L, java.util.Set.of())))
                .isPresent();
    }

    @Test
    void find_multipleUncertainAttemptsFailsClosed() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session()));
        when(attemptRepository.findBySessionIdAndStateOrderByIdAsc(
                eq(SESSION_ID), eq("UNCERTAIN_PENDING_RESOLUTION"), any(Pageable.class)))
                .thenReturn(List.of(attempt(), attempt()));

        assertThatThrownBy(() -> service.find(
                SESSION_ID,
                UnknownOutcomeResolutionActor.authenticated(11L, java.util.Set.of())))
                .isInstanceOf(UnknownOutcomeResolutionException.class)
                .hasMessage("Unknown outcome resolution is not available");

        verify(attemptVerifier, never()).verify(any());
    }

    @Test
    void find_staleGenerationTupleFailsClosedBeforeManifestOrInboxRead() {
        SessionEntity session = session();
        SessionToolAttemptEntity attempt = attempt();
        attempt.setExecutionFence(8L);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(attemptRepository.findBySessionIdAndStateOrderByIdAsc(
                eq(SESSION_ID), eq("UNCERTAIN_PENDING_RESOLUTION"), any(Pageable.class)))
                .thenReturn(List.of(attempt));

        assertThatThrownBy(() -> service.find(
                SESSION_ID,
                UnknownOutcomeResolutionActor.authenticated(11L, java.util.Set.of())))
                .isInstanceOf(UnknownOutcomeResolutionException.class);

        verify(attemptVerifier, never()).verify(any());
        verify(inboxRepository, never()).findBySessionIdOrderByIdAsc(
                any(), any(Pageable.class));
    }

    private static SessionEntity session() {
        SessionEntity row = new SessionEntity();
        row.setId(SESSION_ID);
        row.setUserId(11L);
        row.setHistoryEpoch(3L);
        row.setActiveLoopId("loop-1");
        row.setLoopFence(7L);
        row.setLoopOwnerInstanceId("server-1");
        row.setLoopLeaseUntil(Instant.parse("2026-09-04T10:00:00Z"));
        return row;
    }

    private static SessionToolAttemptEntity attempt() {
        SessionToolAttemptEntity row = new SessionToolAttemptEntity();
        row.setId(17L);
        row.setSessionId(SESSION_ID);
        row.setHistoryEpoch(3L);
        row.setState("UNCERTAIN_PENDING_RESOLUTION");
        row.setExecutionLoopId("loop-1");
        row.setExecutionFence(7L);
        row.setExecutionOwnerInstanceId("server-1");
        row.setExecutionGeneration(5L);
        row.setClaimRequestId(UUID.randomUUID());
        row.setClaimedAt(Instant.parse("2026-09-04T09:00:00Z"));
        row.setExecutionLeaseUntil(Instant.parse("2026-09-04T10:00:00Z"));
        row.setArchivePreparationState("NOT_STARTED");
        row.setPostActionState("NONE");
        return row;
    }

    private static SessionMessageInboxEntity inbox(long id, UUID inboxId) {
        SessionMessageInboxEntity row = new SessionMessageInboxEntity();
        row.setId(id);
        row.setInboxId(inboxId);
        row.setSessionId(SESSION_ID);
        row.setUserId(11L);
        return row;
    }
}
