package com.skillforge.server.sessionannotation;

import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.sessionannotation.SessionAnnotationLlmService.SessionAnnotationConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.3 — regression test locking the user_sim
 * origin filter in {@link SessionPatternClusterService#recompute}.
 *
 * <p>Invariant: when input session list mixes production + user_sim sessions,
 * only production sessions feed into clustering. user_sim sessions never reach
 * {@code buildTuple} → never cluster → never trigger {@code upsertBucket}.
 */
@ExtendWith(MockitoExtension.class)
class SessionPatternClusterServiceOriginFilterTest {

    @Mock private SessionAnnotationRepository sessionAnnotationRepository;
    @Mock private SessionPatternRepository sessionPatternRepository;
    @Mock private PatternSessionMemberRepository patternSessionMemberRepository;
    @Mock private SessionRepository sessionRepository;

    private SessionPatternClusterService service;

    @BeforeEach
    void setUp() {
        service = new SessionPatternClusterService(
                sessionAnnotationRepository, sessionPatternRepository,
                patternSessionMemberRepository, sessionRepository);
    }

    @Test
    @DisplayName("recompute excludes user_sim sessions before clustering — only production sessions reach buildTuple")
    void recompute_filtersUserSimOriginBeforeClustering() {
        // 2 sessions: 1 production + 1 user_sim, both with outcome=failure annotations.
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("prod-1", "usersim-1"));

        SessionEntity prod = newSession("prod-1", 42L, SessionEntity.ORIGIN_PRODUCTION);
        SessionEntity userSim = newSession("usersim-1", 42L, "user_sim");
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(prod, userSim));

        // Stub buildTuple inputs only for prod-1 (cluster service calls findBySessionId
        // for each remaining session). Don't bother with the user_sim path because it
        // should be filtered before that point. Use lenient() so unused stubs don't trip
        // Mockito strict mode.
        lenient().when(sessionAnnotationRepository.findBySessionId("prod-1"))
                .thenReturn(List.of(
                        annotation("prod-1", SessionAnnotationConstants.TYPE_OUTCOME, "failure"),
                        annotation("prod-1", SessionAnnotationConstants.TYPE_SUSPECT_SURFACE, "skill:CodeSandbox")
                ));
        lenient().when(sessionAnnotationRepository.findBySessionId("usersim-1"))
                .thenReturn(Collections.emptyList());

        service.recompute(Duration.ofHours(1));

        // user_sim never reaches buildTuple (which would have called findBySessionId)
        verify(sessionAnnotationRepository, never()).findBySessionId("usersim-1");
        // production session DOES reach buildTuple
        verify(sessionAnnotationRepository).findBySessionId("prod-1");
    }

    @Test
    @DisplayName("recompute returns zero counts when ALL sessions in window are user_sim origin")
    void recompute_allUserSimOrigin_returnsZero() {
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("usersim-1", "usersim-2"));

        SessionEntity us1 = newSession("usersim-1", 42L, "user_sim");
        SessionEntity us2 = newSession("usersim-2", 42L, "user_sim");
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(us1, us2));

        SessionPatternClusterService.RecomputeResult result = service.recompute(Duration.ofHours(1));

        assertThat(result.patternsUpserted()).isZero();
        assertThat(result.membersAdded()).isZero();
        verify(sessionAnnotationRepository, never()).findBySessionId(any());
    }

    private SessionEntity newSession(String id, Long agentId, String origin) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(agentId);
        s.setOrigin(origin);
        return s;
    }

    private SessionAnnotationEntity annotation(String sessionId, String type, String value) {
        SessionAnnotationEntity a = new SessionAnnotationEntity();
        a.setSessionId(sessionId);
        a.setAnnotationType(type);
        a.setAnnotationValue(value);
        a.setSource(SessionAnnotationEntity.SOURCE_LLM);
        a.setConfidence(new BigDecimal("0.9"));
        a.setCreatedAt(Instant.now());
        a.setId(System.nanoTime());
        return a;
    }
}
