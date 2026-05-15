package com.skillforge.server.attribution;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.2: unit tests for
 * {@link AttributionDispatcherService}. Covers the 4 ratify-locked filters
 * (surface allowlist / member count threshold / 24h cooldown / no in-flight
 * event), the {@code maxDispatchPerRun} cap, per-pattern error isolation, and
 * the curator-agent lookup.
 *
 * <p>Also covers the two scenarios formerly pinned by
 * {@code AttributionDispatcherRedTest.java} (which was deleted now that the
 * real service exists and the cases are exercised here):
 * <ul>
 *   <li>{@code dispatcher_skipsPattern_whenInCooldown}</li>
 *   <li>{@code dispatcher_skipsPattern_whenSurfaceNotInAllowList}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AttributionDispatcherServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-15T10:00:00Z");

    @Mock private SessionPatternRepository patternRepository;
    @Mock private OptimizationEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private SessionService sessionService;
    @Mock private ChatService chatService;
    @Mock private AttributionEventBroadcaster broadcaster;

    private AttributionDispatcherService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new AttributionDispatcherService(
                patternRepository, eventRepository, agentRepository,
                sessionService, chatService, fixed, broadcaster);

        // attribution-curator agent lookup succeeds by default; per-test
        // overrides cover the missing-agent path.
        AgentEntity curator = new AgentEntity();
        curator.setId(777L);
        curator.setName(AttributionDispatcherService.CURATOR_AGENT_NAME);
        org.mockito.Mockito.lenient().when(agentRepository.findFirstByName(
                AttributionDispatcherService.CURATOR_AGENT_NAME)).thenReturn(Optional.of(curator));

        // Filter 4 stub defaults to "no active event" — set true per-test where
        // a stage match needs to be present (Phase 1.2 reviewer fix collapsed the
        // per-stage findByPatternIdAndStage loop into one existsByPatternIdAndStageIn).
        org.mockito.Mockito.lenient().when(eventRepository.existsByPatternIdAndStageIn(
                anyLong(), org.mockito.ArgumentMatchers.<java.util.Collection<String>>any()))
                .thenReturn(false);
        org.mockito.Mockito.lenient().when(eventRepository.findByPatternIdAndCooldownExpiresAtAfter(anyLong(), any()))
                .thenReturn(List.of());

        // Default session creation stub (overridden in specific tests).
        SessionEntity sess = new SessionEntity();
        sess.setId("sess-curator-stub");
        org.mockito.Mockito.lenient().when(sessionService.createSession(anyLong(), anyLong())).thenReturn(sess);

        // Phase 1.3 sentinel write — default stub returns the input arg with id
        // assigned so dispatchOne()'s persistedSentinel.getId() doesn't NPE.
        // Per-test overrides cover the failure path.
        org.mockito.Mockito.lenient().when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenAnswer(inv -> {
                    OptimizationEventEntity arg = inv.getArgument(0);
                    if (arg.getId() == null) arg.setId(999L);
                    return arg;
                });
    }

    private SessionPatternEntity pattern(Long id, String surface, int memberCount) {
        SessionPatternEntity p = new SessionPatternEntity();
        p.setId(id);
        p.setSignature("sig-" + id);
        p.setOutcome("failure");
        p.setSuspectSurface(surface);
        p.setMemberCount(memberCount);
        p.setAgentId(42L);
        p.setFirstSeenAt(FIXED_NOW.minusSeconds(7200));
        p.setLastSeenAt(FIXED_NOW.minusSeconds(60));
        return p;
    }

    @Test
    @DisplayName("empty patterns table returns all-zeros DispatchResult and never dispatches")
    void emptyPatterns_returnsZeroAndDispatchesNothing() {
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(AttributionDispatcherService.DEFAULT_MAX_DISPATCH_PER_RUN);

        assertThat(result.scanned()).isZero();
        assertThat(result.dispatched()).isZero();
        assertThat(result.skippedSurface()).isZero();
        assertThat(result.skippedCooldown()).isZero();
        assertThat(result.skippedActive()).isZero();
        verify(sessionService, never()).createSession(anyLong(), anyLong());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("pattern with surface='other' is skipped (ratify #6 — V3 only auto-dispatches skill/prompt)")
    void dispatcher_skipsPattern_whenSurfaceNotInAllowList() {
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(pattern(42L, "unclear", 5),
                                    pattern(43L, "behavior_rule", 5),
                                    pattern(44L, "other", 5)));

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.dispatched()).isZero();
        assertThat(result.skippedSurface()).isEqualTo(3);
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("pattern with active 24h cooldown is skipped (ratify #2)")
    void dispatcher_skipsPattern_whenInCooldown() {
        SessionPatternEntity p = pattern(42L, OptimizationEventEntity.SURFACE_SKILL, 5);
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(p));
        // A prior event with cooldown_expires_at still in the future.
        OptimizationEventEntity priorEvent = new OptimizationEventEntity();
        priorEvent.setId(99L);
        priorEvent.setPatternId(42L);
        priorEvent.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        priorEvent.setCooldownExpiresAt(FIXED_NOW.plusSeconds(3600));
        when(eventRepository.findByPatternIdAndCooldownExpiresAtAfter(eq(42L), eq(FIXED_NOW)))
                .thenReturn(List.of(priorEvent));

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.dispatched()).isZero();
        assertThat(result.skippedCooldown()).isEqualTo(1);
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("pattern with active in-flight event (defensive Filter 4) is skipped")
    void dispatcher_skipsPattern_whenActiveEventInPreTerminalStage() {
        SessionPatternEntity p = pattern(42L, OptimizationEventEntity.SURFACE_PROMPT, 5);
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(p));
        // cooldown row absent — but the single existsByPatternIdAndStageIn call
        // for this pattern returns true (Phase 1.2 reviewer fix collapsed the
        // per-stage findByPatternIdAndStage loop into one boolean call).
        when(eventRepository.existsByPatternIdAndStageIn(eq(42L),
                org.mockito.ArgumentMatchers.<java.util.Collection<String>>any()))
                .thenReturn(true);

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.dispatched()).isZero();
        assertThat(result.skippedActive()).isEqualTo(1);
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("pattern with member_count < 3 is silently skipped (defensive duplicate of V1 cluster rule)")
    void dispatcher_skipsPattern_whenMemberCountBelowThreshold() {
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(pattern(42L, OptimizationEventEntity.SURFACE_SKILL, 2)));

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.dispatched()).isZero();
        // Member-count skip doesn't increment any specific bucket (intentional).
        assertThat(result.skippedSurface()).isZero();
        assertThat(result.skippedCooldown()).isZero();
        assertThat(result.skippedActive()).isZero();
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("eligible patterns beyond maxDispatchPerRun cap are not dispatched this run")
    void dispatcher_capsToMaxDispatchPerRun() {
        List<SessionPatternEntity> patterns = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            patterns.add(pattern(i, OptimizationEventEntity.SURFACE_SKILL, 5));
        }
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(patterns);

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(8);
        assertThat(result.dispatched()).isEqualTo(5);
        verify(chatService, times(5)).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("a failing dispatch on one pattern does not block subsequent patterns (per-pattern error isolation)")
    void dispatcher_isolatesFailurePerPattern() {
        List<SessionPatternEntity> patterns = List.of(
                pattern(1L, OptimizationEventEntity.SURFACE_SKILL, 5),
                pattern(2L, OptimizationEventEntity.SURFACE_SKILL, 5),
                pattern(3L, OptimizationEventEntity.SURFACE_SKILL, 5));
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(patterns);

        // Make session creation for pattern 2 fail with a DataAccessException.
        SessionEntity ok1 = new SessionEntity(); ok1.setId("sess-1");
        SessionEntity ok3 = new SessionEntity(); ok3.setId("sess-3");
        when(sessionService.createSession(anyLong(), eq(777L)))
                .thenReturn(ok1)
                .thenThrow(new DataAccessResourceFailureException("simulated DB hiccup"))
                .thenReturn(ok3);

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(3);
        // Two succeed; the middle one's failure is caught + logged.
        assertThat(result.dispatched()).isEqualTo(2);
        verify(chatService, times(2)).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("missing attribution-curator agent — early-exit with all zeros (V81 migration not yet applied)")
    void dispatcher_returnsZeroWhenCuratorAgentMissing() {
        when(agentRepository.findFirstByName(AttributionDispatcherService.CURATOR_AGENT_NAME))
                .thenReturn(Optional.empty());

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isZero();
        assertThat(result.dispatched()).isZero();
        // No pattern scan attempted when curator agent missing.
        verify(patternRepository, never()).findWithFilters(any(), any(), any(), any(Pageable.class));
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("eligible pattern triggers SessionService.createSession + ChatService.chatAsync with per-pattern prompt")
    void dispatcher_invokesChatAsyncWithPerPatternPrompt() {
        SessionPatternEntity p = pattern(42L, OptimizationEventEntity.SURFACE_PROMPT, 5);
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(p));
        SessionEntity curatorSess = new SessionEntity();
        curatorSess.setId("sess-curator-42");
        when(sessionService.createSession(eq(AttributionDispatcherService.SYSTEM_USER_ID), eq(777L)))
                .thenReturn(curatorSess);

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.dispatched()).isEqualTo(1);
        verify(sessionService).createSession(AttributionDispatcherService.SYSTEM_USER_ID, 777L);
        verify(chatService).chatAsync(eq("sess-curator-42"),
                org.mockito.ArgumentMatchers.contains("patternId=42"),
                eq(AttributionDispatcherService.SYSTEM_USER_ID));
    }

    @Test
    @DisplayName("Phase 1.3 sentinel: dispatchOne writes dispatch_initiated event BEFORE chatAsync (race-window close)")
    void dispatcher_writesSentinelBeforeChatAsync() {
        SessionPatternEntity p = pattern(42L, OptimizationEventEntity.SURFACE_SKILL, 5);
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(p));
        org.mockito.ArgumentCaptor<OptimizationEventEntity> captor =
                org.mockito.ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> {
            OptimizationEventEntity arg = inv.getArgument(0);
            arg.setId(123L);
            return arg;
        });
        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(eventRepository, sessionService, chatService);

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.dispatched()).isEqualTo(1);
        OptimizationEventEntity sentinel = captor.getValue();
        assertThat(sentinel.getStage()).isEqualTo(OptimizationEventEntity.STAGE_DISPATCH_INITIATED);
        assertThat(sentinel.getPatternId()).isEqualTo(42L);
        assertThat(sentinel.getSurfaceType()).isEqualTo(OptimizationEventEntity.SURFACE_SKILL);
        // Strict ordering: sentinel save → createSession → chatAsync.
        ordered.verify(eventRepository).save(any(OptimizationEventEntity.class));
        ordered.verify(sessionService).createSession(anyLong(), anyLong());
        ordered.verify(chatService).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Phase 1.4 cleanup: deletes sentinels older than ORPHAN_SENTINEL_TTL")
    void cleanup_deletesSentinelsOlderThanTwoHours() {
        Instant cutoff = FIXED_NOW.minus(AttributionDispatcherService.ORPHAN_SENTINEL_TTL);
        OptimizationEventEntity oldSentinel = new OptimizationEventEntity();
        oldSentinel.setId(555L);
        oldSentinel.setStage(OptimizationEventEntity.STAGE_DISPATCH_INITIATED);
        oldSentinel.setCreatedAt(cutoff.minusSeconds(60));
        when(eventRepository.findByStageAndCreatedAtBefore(
                eq(OptimizationEventEntity.STAGE_DISPATCH_INITIATED), eq(cutoff)))
                .thenReturn(List.of(oldSentinel));

        service.cleanupOrphanSentinels();

        verify(eventRepository).deleteAll(List.of(oldSentinel));
    }

    @Test
    @DisplayName("Phase 1.4 cleanup: skips when no sentinels older than TTL")
    void cleanup_skipsRecentSentinels() {
        Instant cutoff = FIXED_NOW.minus(AttributionDispatcherService.ORPHAN_SENTINEL_TTL);
        when(eventRepository.findByStageAndCreatedAtBefore(
                eq(OptimizationEventEntity.STAGE_DISPATCH_INITIATED), eq(cutoff)))
                .thenReturn(List.of());

        service.cleanupOrphanSentinels();

        verify(eventRepository, never()).deleteAll(any(Iterable.class));
    }

    @Test
    @DisplayName("Phase 1.3 sentinel: chatAsync NOT invoked when sentinel write fails (no orphan curator session)")
    void dispatcher_doesNotCallChatAsync_whenSentinelWriteFails() {
        SessionPatternEntity p = pattern(42L, OptimizationEventEntity.SURFACE_SKILL, 5);
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(p));
        // Override default save stub to throw a DB error on first attempt.
        when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenThrow(new DataAccessResourceFailureException("simulated sentinel write failure"));

        AttributionDispatcherService.DispatchResult result =
                service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.dispatched()).isZero();
        // Per-pattern catch swallowed the DB error (V1 W2 lesson: narrow catch +
        // continue). Critical assertion: chatAsync was NOT invoked → no orphan
        // curator session waiting for an event row that doesn't exist.
        verify(sessionService, never()).createSession(anyLong(), anyLong());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }
}
