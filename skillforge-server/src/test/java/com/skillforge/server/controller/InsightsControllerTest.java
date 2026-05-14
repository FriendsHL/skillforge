package com.skillforge.server.controller;

import com.skillforge.server.controller.InsightsController.PatternListItem;
import com.skillforge.server.controller.InsightsController.PatternMemberItem;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.4): unit tests for {@link InsightsController}.
 *
 * <p>Mockito-style — no @WebMvcTest. Controller is plain @RestController with
 * constructor-injected repositories so direct invocation + ResponseEntity
 * assertions cover the cases without spinning a Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InsightsController")
class InsightsControllerTest {

    @Mock private SessionPatternRepository sessionPatternRepository;
    @Mock private PatternSessionMemberRepository patternSessionMemberRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private AgentRepository agentRepository;

    private InsightsController controller;

    @BeforeEach
    void setUp() {
        controller = new InsightsController(
                sessionPatternRepository,
                patternSessionMemberRepository,
                sessionRepository,
                agentRepository);
    }

    @Test
    @DisplayName("getPatterns returns rows sorted by repository, mapped to PatternListItem")
    void getPatterns_returnsListSortedByMemberCount() {
        SessionPatternEntity big = makePattern(1L, "failure|skill|BashTool|7", "failure", "skill", "BashTool", 7L, 12);
        SessionPatternEntity small = makePattern(2L, "failure|prompt||7", "failure", "prompt", null, 7L, 5);
        when(sessionPatternRepository.findWithFilters(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(big, small));

        ResponseEntity<List<PatternListItem>> resp =
                controller.listPatterns(null, null, null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<PatternListItem> body = resp.getBody();
        assertThat(body).hasSize(2);
        // Sorted by repository — we trust the JPQL ORDER BY. Controller test only
        // asserts the mapping preserves repo order.
        assertThat(body.get(0).id()).isEqualTo(1L);
        assertThat(body.get(0).memberCount()).isEqualTo(12);
        assertThat(body.get(0).signature()).isEqualTo("failure|skill|BashTool|7");
        assertThat(body.get(1).id()).isEqualTo(2L);
        assertThat(body.get(1).memberCount()).isEqualTo(5);
        assertThat(body.get(1).topFailingTool()).isNull();
    }

    @Test
    @DisplayName("getPatterns forwards outcome filter to repository")
    void getPatterns_respectsOutcomeFilter() {
        when(sessionPatternRepository.findWithFilters(eq("failure"), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        controller.listPatterns("failure", null, null, null);

        verify(sessionPatternRepository).findWithFilters(eq("failure"), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("getPatterns forwards surface filter to repository")
    void getPatterns_respectsSurfaceFilter() {
        when(sessionPatternRepository.findWithFilters(isNull(), eq("skill"), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        controller.listPatterns(null, "skill", null, null);

        verify(sessionPatternRepository).findWithFilters(isNull(), eq("skill"), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("getPatterns forwards agent filter to repository")
    void getPatterns_respectsAgentFilter() {
        when(sessionPatternRepository.findWithFilters(isNull(), isNull(), eq(42L), any(Pageable.class)))
                .thenReturn(List.of());

        controller.listPatterns(null, null, 42L, null);

        verify(sessionPatternRepository).findWithFilters(isNull(), isNull(), eq(42L), any(Pageable.class));
    }

    @Test
    @DisplayName("getPatterns clamps limit to [1, 200] and applies default 50 when null")
    void getPatterns_clampsLimit() {
        when(sessionPatternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        controller.listPatterns(null, null, null, null);
        controller.listPatterns(null, null, null, 0);     // < min
        controller.listPatterns(null, null, null, 1000);  // > max

        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionPatternRepository, org.mockito.Mockito.times(3))
                .findWithFilters(any(), any(), any(), pg.capture());
        // null → default 50
        assertThat(pg.getAllValues().get(0).getPageSize()).isEqualTo(50);
        // 0 → 1
        assertThat(pg.getAllValues().get(1).getPageSize()).isEqualTo(1);
        // 1000 → 200
        assertThat(pg.getAllValues().get(2).getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("getPatternMembers returns member rows with agent name + truncated runtime error")
    void getPatternMembers_returnsMemberSessions() {
        SessionPatternEntity p = makePattern(50L, "failure|skill|BashTool|7", "failure", "skill", "BashTool", 7L, 3);
        when(sessionPatternRepository.findById(50L)).thenReturn(Optional.of(p));

        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        PatternSessionMemberEntity m1 = makeMember(50L, "sess-A", t.plusSeconds(30));
        PatternSessionMemberEntity m2 = makeMember(50L, "sess-B", t.plusSeconds(10));
        when(patternSessionMemberRepository.findByPatternIdOrderByAddedAtDesc(eq(50L), any(Pageable.class)))
                .thenReturn(List.of(m1, m2));

        SessionEntity sA = new SessionEntity();
        sA.setId("sess-A");
        sA.setAgentId(7L);
        sA.setCompletedAt(t);
        sA.setRuntimeError("boom");
        SessionEntity sB = new SessionEntity();
        sB.setId("sess-B");
        sB.setAgentId(7L);
        sB.setCompletedAt(t.minusSeconds(60));
        // Long error to verify truncation kicks in
        String longErr = "x".repeat(InsightsController.RUNTIME_ERROR_TRUNCATE_LEN + 50);
        sB.setRuntimeError(longErr);
        when(sessionRepository.findAllById(anyIterable())).thenReturn(List.of(sA, sB));

        AgentEntity a = new AgentEntity();
        a.setId(7L);
        a.setName("code-agent");
        when(agentRepository.findAllById(anyIterable())).thenReturn(List.of(a));

        ResponseEntity<List<PatternMemberItem>> resp =
                controller.listPatternMembers(50L, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<PatternMemberItem> body = resp.getBody();
        assertThat(body).hasSize(2);
        PatternMemberItem first = body.get(0);
        assertThat(first.sessionId()).isEqualTo("sess-A");
        assertThat(first.agentName()).isEqualTo("code-agent");
        assertThat(first.completedAt()).isEqualTo(t);
        assertThat(first.runtimeError()).isEqualTo("boom");

        PatternMemberItem second = body.get(1);
        // Long runtime error truncated to 200 chars + ellipsis
        assertThat(second.runtimeError())
                .hasSize(InsightsController.RUNTIME_ERROR_TRUNCATE_LEN + 1)
                .endsWith("…");
    }

    @Test
    @DisplayName("getPatternMembers returns 404 when pattern not found")
    void getPatternMembers_returns404WhenPatternNotFound() {
        when(sessionPatternRepository.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<List<PatternMemberItem>> resp =
                controller.listPatternMembers(999L, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(patternSessionMemberRepository, org.mockito.Mockito.never())
                .findByPatternIdOrderByAddedAtDesc(anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("getPatternMembers clamps limit to [1, 500] and applies default 100 when null")
    void getPatternMembers_clampsLimit() {
        SessionPatternEntity p = makePattern(50L, "failure|skill||7", "failure", "skill", null, 7L, 0);
        when(sessionPatternRepository.findById(50L)).thenReturn(Optional.of(p));
        when(patternSessionMemberRepository.findByPatternIdOrderByAddedAtDesc(eq(50L), any(Pageable.class)))
                .thenReturn(List.of());

        controller.listPatternMembers(50L, null);
        controller.listPatternMembers(50L, 0);
        controller.listPatternMembers(50L, 10_000);

        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        verify(patternSessionMemberRepository, org.mockito.Mockito.times(3))
                .findByPatternIdOrderByAddedAtDesc(eq(50L), pg.capture());
        assertThat(pg.getAllValues().get(0).getPageSize()).isEqualTo(100);
        assertThat(pg.getAllValues().get(1).getPageSize()).isEqualTo(1);
        assertThat(pg.getAllValues().get(2).getPageSize()).isEqualTo(500);
    }

    // ---------- helpers ----------

    private static SessionPatternEntity makePattern(Long id, String signature, String outcome,
                                                    String surface, String topTool, Long agentId,
                                                    int memberCount) {
        SessionPatternEntity p = new SessionPatternEntity();
        p.setId(id);
        p.setSignature(signature);
        p.setOutcome(outcome);
        p.setSuspectSurface(surface);
        p.setTopFailingTool(topTool);
        p.setAgentId(agentId);
        p.setMemberCount(memberCount);
        p.setSuggestedSurface(surface);
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        p.setFirstSeenAt(t);
        p.setLastSeenAt(t);
        p.setCreatedAt(t);
        p.setUpdatedAt(t);
        return p;
    }

    private static PatternSessionMemberEntity makeMember(Long patternId, String sessionId, Instant at) {
        PatternSessionMemberEntity m = new PatternSessionMemberEntity();
        m.setPatternId(patternId);
        m.setSessionId(sessionId);
        m.setAddedAt(at);
        return m;
    }
}
