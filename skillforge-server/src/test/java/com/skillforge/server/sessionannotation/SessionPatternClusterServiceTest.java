package com.skillforge.server.sessionannotation;

import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.sessionannotation.SessionPatternClusterService.RecomputeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.4): unit tests for the cluster service.
 *
 * <p>All collaborators are mocked — no Docker / no real Postgres (mirrors the
 * other tests in this package). Tests focus on:
 * <ul>
 *   <li>4-dim bucket grouping</li>
 *   <li>admission filters (success outcome / confidence < 0.5 / member &lt; 3)</li>
 *   <li>idempotency (re-run + repeated bucket member)</li>
 *   <li>incremental member append when bucket grows</li>
 *   <li>top_failing_tool null / blank handled as empty in signature</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionPatternClusterService")
class SessionPatternClusterServiceTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private SessionAnnotationRepository sessionAnnotationRepository;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private SessionPatternRepository sessionPatternRepository;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private PatternSessionMemberRepository patternSessionMemberRepository;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private SessionRepository sessionRepository;

    private SessionPatternClusterService service;

    @BeforeEach
    void setUp() {
        service = new SessionPatternClusterService(
                sessionAnnotationRepository,
                sessionPatternRepository,
                patternSessionMemberRepository,
                sessionRepository);
    }

    @Test
    @DisplayName("groups 3 sessions sharing a 4-tuple into one new pattern with member_count=3")
    void recompute_groupsSessionsBy4DimBucket() {
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2", "s3"));
        primeAnnotations("s1", t.plusSeconds(10), "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s2", t.plusSeconds(20), "failure", "skill", "BashTool", 0.85);
        primeAnnotations("s3", t.plusSeconds(30), "failure", "skill", "BashTool", 0.95);
        primeSessions(Map.of("s1", 7L, "s2", 7L, "s3", 7L));
        when(sessionPatternRepository.findBySignature(anyString())).thenReturn(java.util.Optional.empty());
        AtomicLong patternIdGen = new AtomicLong(500L);
        when(sessionPatternRepository.save(any(SessionPatternEntity.class))).thenAnswer(inv -> {
            SessionPatternEntity p = inv.getArgument(0);
            if (p.getId() == null) p.setId(patternIdGen.getAndIncrement());
            return p;
        });

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        assertThat(res.patternsUpserted()).isEqualTo(1);
        assertThat(res.membersAdded()).isEqualTo(3);

        ArgumentCaptor<SessionPatternEntity> patternCap = ArgumentCaptor.forClass(SessionPatternEntity.class);
        verify(sessionPatternRepository, times(2)).save(patternCap.capture());  // once before members, once after to update count
        SessionPatternEntity created = patternCap.getAllValues().get(0);
        assertThat(created.getSignature()).isEqualTo("failure|skill|BashTool|7");
        assertThat(created.getOutcome()).isEqualTo("failure");
        assertThat(created.getSuspectSurface()).isEqualTo("skill");
        assertThat(created.getTopFailingTool()).isEqualTo("BashTool");
        assertThat(created.getAgentId()).isEqualTo(7L);
        assertThat(created.getSuggestedSurface()).isEqualTo("skill");
        // last save = updated count
        SessionPatternEntity afterMembers = patternCap.getAllValues().get(1);
        assertThat(afterMembers.getMemberCount()).isEqualTo(3);

        verify(patternSessionMemberRepository, times(3)).saveAndFlush(any(PatternSessionMemberEntity.class));
    }

    @Test
    @DisplayName("buckets with fewer than 3 members are NOT upserted")
    void recompute_skipsBucketsBelowMinMembers() {
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2"));
        primeAnnotations("s1", t, "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s2", t, "failure", "skill", "BashTool", 0.9);
        primeSessions(Map.of("s1", 7L, "s2", 7L));

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        assertThat(res.patternsUpserted()).isZero();
        assertThat(res.membersAdded()).isZero();
        verify(sessionPatternRepository, never()).save(any(SessionPatternEntity.class));
        verify(patternSessionMemberRepository, never()).saveAndFlush(any(PatternSessionMemberEntity.class));
    }

    @Test
    @DisplayName("success outcome sessions are excluded from clustering")
    void recompute_skipsSuccessOutcome() {
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2", "s3", "s4"));
        // s1-s3 are success — they should be filtered out
        primeAnnotations("s1", t, "success", "skill", null, 1.0);
        primeAnnotations("s2", t, "success", "skill", null, 1.0);
        primeAnnotations("s3", t, "success", "skill", null, 1.0);
        // s4 is a lone failure — bucket size 1 → also no pattern
        primeAnnotations("s4", t, "failure", "skill", "BashTool", 0.9);
        primeSessions(Map.of("s1", 7L, "s2", 7L, "s3", 7L, "s4", 7L));

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        assertThat(res.patternsUpserted()).isZero();
        assertThat(res.membersAdded()).isZero();
        verify(sessionPatternRepository, never()).save(any(SessionPatternEntity.class));
    }

    @Test
    @DisplayName("LLM annotations with confidence < 0.5 are excluded from clustering")
    void recompute_skipsLowConfidenceLlmAnnotations() {
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2", "s3", "s4"));
        primeAnnotations("s1", t, "failure", "skill", "BashTool", 0.4);  // below threshold
        primeAnnotations("s2", t, "failure", "skill", "BashTool", 0.49); // just below
        primeAnnotations("s3", t, "failure", "skill", "BashTool", 0.5);  // exactly threshold (allowed)
        primeAnnotations("s4", t, "failure", "skill", "BashTool", 0.9);
        primeSessions(Map.of("s1", 7L, "s2", 7L, "s3", 7L, "s4", 7L));

        // s3 + s4 = 2 confident sessions; bucket needs ≥ 3 → no pattern
        RecomputeResult res = service.recompute(Duration.ofDays(7));
        assertThat(res.patternsUpserted()).isZero();
        verify(sessionPatternRepository, never()).save(any(SessionPatternEntity.class));
    }

    @Test
    @DisplayName("re-run on same bucket is idempotent (UNIQUE conflicts caught, member_count unchanged)")
    void recompute_isIdempotent_onRerun() {
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2", "s3"));
        primeAnnotations("s1", t, "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s2", t, "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s3", t, "failure", "skill", "BashTool", 0.9);
        primeSessions(Map.of("s1", 7L, "s2", 7L, "s3", 7L));

        // Pattern already exists with member_count=3 from a prior run.
        SessionPatternEntity existing = new SessionPatternEntity();
        existing.setId(900L);
        existing.setSignature("failure|skill|BashTool|7");
        existing.setOutcome("failure");
        existing.setSuspectSurface("skill");
        existing.setTopFailingTool("BashTool");
        existing.setAgentId(7L);
        existing.setMemberCount(3);
        existing.setFirstSeenAt(t);
        existing.setLastSeenAt(t);
        existing.setCreatedAt(t);
        existing.setUpdatedAt(t);
        when(sessionPatternRepository.findBySignature("failure|skill|BashTool|7"))
                .thenReturn(java.util.Optional.of(existing));
        when(sessionPatternRepository.save(any(SessionPatternEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // All 3 member inserts conflict on composite PK — already members from prior run.
        when(patternSessionMemberRepository.saveAndFlush(any(PatternSessionMemberEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate (pattern_id, session_id)"));

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        // Pattern is counted as upserted (we always update last_seen_at + updated_at on existing).
        assertThat(res.patternsUpserted()).isEqualTo(1);
        assertThat(res.membersAdded()).isZero();
        // member_count was NOT inflated — stays at 3.
        assertThat(existing.getMemberCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("re-run with growing bucket only appends the NEW members (incremental)")
    void recompute_appendsNewMembers_onRerunWithGrowingBucket() {
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2", "s3", "s4", "s5"));
        primeAnnotations("s1", t, "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s2", t, "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s3", t, "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s4", t, "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s5", t, "failure", "skill", "BashTool", 0.9);
        primeSessions(Map.of("s1", 7L, "s2", 7L, "s3", 7L, "s4", 7L, "s5", 7L));

        SessionPatternEntity existing = new SessionPatternEntity();
        existing.setId(900L);
        existing.setSignature("failure|skill|BashTool|7");
        existing.setOutcome("failure");
        existing.setSuspectSurface("skill");
        existing.setTopFailingTool("BashTool");
        existing.setAgentId(7L);
        existing.setMemberCount(3);  // s1, s2, s3 from prior run
        existing.setFirstSeenAt(t);
        existing.setLastSeenAt(t);
        existing.setCreatedAt(t);
        existing.setUpdatedAt(t);
        when(sessionPatternRepository.findBySignature("failure|skill|BashTool|7"))
                .thenReturn(java.util.Optional.of(existing));
        when(sessionPatternRepository.save(any(SessionPatternEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // s1, s2, s3 = conflict (existing); s4, s5 = new inserts
        when(patternSessionMemberRepository.saveAndFlush(any(PatternSessionMemberEntity.class)))
                .thenAnswer(inv -> {
                    PatternSessionMemberEntity m = inv.getArgument(0);
                    if (Arrays.asList("s1", "s2", "s3").contains(m.getSessionId())) {
                        throw new DataIntegrityViolationException("dup");
                    }
                    return m;
                });

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        assertThat(res.patternsUpserted()).isEqualTo(1);
        assertThat(res.membersAdded()).isEqualTo(2);
        // member_count went 3 → 5
        assertThat(existing.getMemberCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("top_failing_tool null is rendered as empty in signature; bucket merges with blank")
    void recompute_topFailingToolNull_treatedAsEmptyInSignature() {
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2", "s3"));
        // 3 sessions, no top_failing_tool row
        primeAnnotations("s1", t, "failure", "prompt", null, 0.9);
        primeAnnotations("s2", t, "failure", "prompt", null, 0.9);
        primeAnnotations("s3", t, "failure", "prompt", null, 0.9);
        primeSessions(Map.of("s1", 7L, "s2", 7L, "s3", 7L));
        when(sessionPatternRepository.findBySignature(anyString())).thenReturn(java.util.Optional.empty());
        AtomicLong patternIdGen = new AtomicLong(500L);
        when(sessionPatternRepository.save(any(SessionPatternEntity.class))).thenAnswer(inv -> {
            SessionPatternEntity p = inv.getArgument(0);
            if (p.getId() == null) p.setId(patternIdGen.getAndIncrement());
            return p;
        });

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        assertThat(res.patternsUpserted()).isEqualTo(1);
        ArgumentCaptor<SessionPatternEntity> cap = ArgumentCaptor.forClass(SessionPatternEntity.class);
        verify(sessionPatternRepository, times(2)).save(cap.capture());
        SessionPatternEntity created = cap.getAllValues().get(0);
        // signature should render null tool as ""; tech-design §5.1
        assertThat(created.getSignature()).isEqualTo("failure|prompt||7");
        assertThat(created.getTopFailingTool()).isNull();
    }

    @Test
    @DisplayName("static buildSignature renders null tool / null agent as empty")
    void buildSignature_renderNulls() {
        assertThat(SessionPatternClusterService.buildSignature("failure", "skill", null, 7L))
                .isEqualTo("failure|skill||7");
        assertThat(SessionPatternClusterService.buildSignature("failure", "skill", "Bash", null))
                .isEqualTo("failure|skill|Bash|");
        assertThat(SessionPatternClusterService.buildSignature("failure", "skill", null, null))
                .isEqualTo("failure|skill||");
    }

    // ---------- helpers ----------

    /**
     * Make {@code sessionAnnotationRepository.findBySessionId(sessionId)} return
     * a 2-row LLM tuple (outcome + suspect_surface) plus optional
     * top_failing_tool row.
     */
    private void primeAnnotations(String sessionId,
                                  Instant at,
                                  String outcome,
                                  String surface,
                                  String topFailingTool,
                                  double confidence) {
        List<SessionAnnotationEntity> rows = new ArrayList<>();
        rows.add(makeRow(sessionId, "outcome", outcome, confidence, at));
        rows.add(makeRow(sessionId, "suspect_surface", surface, confidence, at));
        if (topFailingTool != null) {
            rows.add(makeRow(sessionId, "top_failing_tool", topFailingTool, confidence, at));
        }
        when(sessionAnnotationRepository.findBySessionId(sessionId)).thenReturn(rows);
    }

    private static SessionAnnotationEntity makeRow(String sessionId,
                                                   String type,
                                                   String value,
                                                   double confidence,
                                                   Instant at) {
        SessionAnnotationEntity r = new SessionAnnotationEntity();
        r.setSessionId(sessionId);
        r.setAnnotationType(type);
        r.setAnnotationValue(value);
        r.setSource("llm");
        r.setConfidence(new BigDecimal(Double.toString(confidence)));
        r.setReasoning("test");
        r.setCreatedAt(at);
        return r;
    }

    /**
     * Make {@code sessionRepository.findAllById(...)} return SessionEntity rows
     * with the given agentId per sessionId.
     */
    private void primeSessions(Map<String, Long> agentIdBySession) {
        List<SessionEntity> entities = new ArrayList<>();
        for (Map.Entry<String, Long> e : agentIdBySession.entrySet()) {
            SessionEntity s = new SessionEntity();
            s.setId(e.getKey());
            s.setAgentId(e.getValue());
            entities.add(s);
        }
        when(sessionRepository.findAllById(anyIterable())).thenReturn(entities);
    }
}
