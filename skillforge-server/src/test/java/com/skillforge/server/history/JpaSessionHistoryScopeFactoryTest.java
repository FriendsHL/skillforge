package com.skillforge.server.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaSessionHistoryScopeFactoryTest {

    private static final Instant NOW = Instant.parse("2026-09-04T04:00:00Z");

    private SessionRepository sessionRepository;
    private SessionMessageRepository messageRepository;
    private SessionToolAttemptRepository attemptRepository;
    private JpaSessionHistoryScopeFactory factory;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(SessionMessageRepository.class);
        attemptRepository = mock(SessionToolAttemptRepository.class);
        factory = new JpaSessionHistoryScopeFactory(
                sessionRepository,
                messageRepository,
                attemptRepository,
                mock(SessionMessageInboxRepository.class),
                new ObjectMapper());
    }

    @Test
    void resolvesScopeOnlyFromMatchingTrustedLoopAndCurrentExecutingAttempt() {
        SkillContext context = context("session-1", 41L, 7L, "loop-1", 3L, "node-a", "history-call");
        SessionEntity session = session("session-1", 41L, 7L, "loop-1", 3L, "node-a");
        SessionToolAttemptEntity attempt = attempt("session-1", 7L, "loop-1", 3L, "node-a",
                "history-call", "SessionHistorySearch");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(sessionRepository.currentDatabaseTime()).thenReturn(NOW);
        when(messageRepository.countBySessionId("session-1")).thenReturn(10L);
        when(attemptRepository.findBySessionIdAndStateIn("session-1", List.of("EXECUTING")))
                .thenReturn(List.of(attempt));

        CurrentSessionHistoryScope scope = factory.resolve(context, "SessionHistorySearch");

        assertThat(scope.sessionId()).isEqualTo("session-1");
        assertThat(scope.userId()).isEqualTo(41L);
        assertThat(scope.historyEpoch()).isEqualTo(7L);
        assertThat(scope.preIntentMaxMessageId()).isEqualTo(90L);
        assertThat(scope.preIntentMaxSeq()).isEqualTo(40L);
        assertThat(scope.currentToolUseId()).isEqualTo("history-call");
    }

    @Test
    void crossSessionOwnerEpochAndFenceAllFailWithSameNonEnumeratingError() {
        SessionEntity session = session("session-1", 41L, 7L, "loop-1", 3L, "node-a");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(sessionRepository.currentDatabaseTime()).thenReturn(NOW);

        SkillContext wrongOwner = context(
                "session-1", 99L, 7L, "loop-1", 3L, "node-a", "history-call");
        SkillContext staleEpoch = context(
                "session-1", 41L, 6L, "loop-1", 3L, "node-a", "history-call");
        SkillContext staleFence = context(
                "session-1", 41L, 7L, "loop-1", 2L, "node-a", "history-call");
        SkillContext crossSession = context(
                "other-session", 41L, 7L, "loop-1", 3L, "node-a", "history-call");

        for (SkillContext invalid : List.of(wrongOwner, staleEpoch, staleFence, crossSession)) {
            assertThatThrownBy(() -> factory.resolve(invalid, "SessionHistorySearch"))
                    .isInstanceOf(SessionHistoryUnavailableException.class)
                    .hasMessage("History is unavailable for the current Session");
        }
    }

    @Test
    void mismatchedCurrentToolIdentityOrCorruptManifestFailsClosed() {
        SkillContext context = context("session-1", 41L, 7L, "loop-1", 3L, "node-a", "other-call");
        SessionEntity session = session("session-1", 41L, 7L, "loop-1", 3L, "node-a");
        SessionToolAttemptEntity attempt = attempt("session-1", 7L, "loop-1", 3L, "node-a",
                "history-call", "SessionHistorySearch");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(sessionRepository.currentDatabaseTime()).thenReturn(NOW);
        when(messageRepository.countBySessionId("session-1")).thenReturn(10L);
        when(attemptRepository.findBySessionIdAndStateIn("session-1", List.of("EXECUTING")))
                .thenReturn(List.of(attempt));

        assertThatThrownBy(() -> factory.resolve(context, "SessionHistorySearch"))
                .isInstanceOf(SessionHistoryUnavailableException.class);

        attempt.setManifestHash("0".repeat(64));
        context.setToolUseId("history-call");
        assertThatThrownBy(() -> factory.resolve(context, "SessionHistorySearch"))
                .isInstanceOf(SessionHistoryUnavailableException.class);
    }

    @Test
    void readinessMarksLegacyOnlySessionNonAuthoritative() {
        SkillContext context = context("session-1", 41L, 7L, "loop-1", 3L, "node-a", null);
        SessionEntity session = session("session-1", 41L, 7L, "loop-1", 3L, "node-a");
        session.setMessagesJson("[{\"role\":\"user\"}]");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(sessionRepository.currentDatabaseTime()).thenReturn(NOW);
        when(messageRepository.countBySessionId("session-1")).thenReturn(0L);

        SessionHistoryAvailabilityPolicy.StoreReadiness readiness = factory.readiness(context);

        assertThat(readiness.legacyOnlySession()).isTrue();
        assertThat(readiness.authoritative()).isFalse();
    }

    @Test
    void readinessRejectsLegacyTranscriptEvenWhenUnrelatedRowsExist() {
        SkillContext context = context("session-1", 41L, 7L, "loop-1", 3L, "node-a", null);
        SessionEntity session = session("session-1", 41L, 7L, "loop-1", 3L, "node-a");
        session.setMessagesJson("[{\"role\":\"user\",\"content\":\"old exact fact\"}]");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(sessionRepository.currentDatabaseTime()).thenReturn(NOW);
        when(messageRepository.countBySessionId("session-1")).thenReturn(1L);

        assertThat(factory.readiness(context).authoritative()).isFalse();
    }

    private static SkillContext context(
            String sessionId,
            long userId,
            long epoch,
            String loopId,
            long fence,
            String owner,
            String toolUseId) {
        SkillContext context = new SkillContext("/workspace", sessionId, userId);
        context.setDurabilityScope(new LoopDurabilityScope(
                sessionId, userId, epoch, loopId, fence, owner));
        context.setToolUseId(toolUseId);
        return context;
    }

    private static SessionEntity session(
            String sessionId,
            long userId,
            long epoch,
            String loopId,
            long fence,
            String owner) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setHistoryEpoch(epoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(fence);
        session.setLoopOwnerInstanceId(owner);
        session.setLoopLeaseUntil(NOW.plusSeconds(60));
        session.setRestorePreparing(false);
        session.setMessagesJson("[]");
        return session;
    }

    private static SessionToolAttemptEntity attempt(
            String sessionId,
            long epoch,
            String loopId,
            long fence,
            String owner,
            String toolUseId,
            String toolName) {
        String manifest = "{\"schemaVersion\":1,\"calls\":[{\"providerOrdinal\":0,"
                + "\"toolUseId\":\"" + toolUseId + "\",\"toolName\":\"" + toolName + "\","
                + "\"input\":{},\"replaySafety\":\"READ_ONLY_REPLAYABLE\"}],"
                + "\"replaySafety\":\"READ_ONLY_REPLAYABLE\"}";
        SessionToolAttemptEntity attempt = new SessionToolAttemptEntity();
        attempt.setSessionId(sessionId);
        attempt.setHistoryEpoch(epoch);
        attempt.setState("EXECUTING");
        attempt.setExecutionLoopId(loopId);
        attempt.setExecutionFence(fence);
        attempt.setExecutionOwnerInstanceId(owner);
        attempt.setExecutionGeneration(1L);
        attempt.setClaimRequestId(UUID.randomUUID());
        attempt.setClaimedAt(NOW.minusSeconds(1));
        attempt.setExecutionLeaseUntil(NOW.plusSeconds(60));
        attempt.setPreIntentMaxMessageId(90L);
        attempt.setPreIntentMaxSeq(40L);
        attempt.setManifestJson(manifest);
        attempt.setManifestHash(sha256(manifest));
        attempt.setReplaySafety("READ_ONLY_REPLAYABLE");
        return attempt;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
