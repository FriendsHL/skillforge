package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.config.SessionMessageStoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-AGENT-TYPING Phase 2 visibility follow-up (2026-05-18) —
 * unit tests for {@link SessionService#listSessionsByAgentType}.
 *
 * <p>Three scenarios per brief:
 * <ol>
 *   <li>{@code agentType='system'} → new JOIN-by-agent_type repo path (no userId filter)</li>
 *   <li>{@code agentType='user'} → legacy {@code listUserSessions(userId)} path (origin='production')</li>
 *   <li>{@code agentType=null} (omitted) → controller falls through to legacy path (covered by ChatController test;
 *       service-level rejects null/blank with {@link NullPointerException} / {@link IllegalArgumentException})</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionService.listSessionsByAgentType")
class SessionServiceAgentTypeTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SessionMessageRepository sessionMessageRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                transactionManager
        );
    }

    private SessionEntity stubSession(Long userId, Long agentId) {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setAgentId(agentId);
        return s;
    }

    @Test
    @DisplayName("agentType='system' delegates to findByAgentTypeAndOrigin (production default), bypasses userId filter")
    void listByAgentType_system_callsJoinRepoAndIgnoresUserId() {
        SessionEntity sys1 = stubSession(0L, 100L);
        SessionEntity sys2 = stubSession(0L, 101L);
        when(sessionRepository.findByAgentTypeAndOriginOrderByUpdatedAtDesc(
                eq("system"), eq(SessionEntity.ORIGIN_PRODUCTION)))
                .thenReturn(List.of(sys1, sys2));

        List<SessionEntity> result = sessionService.listSessionsByAgentType("system");

        assertThat(result).containsExactly(sys1, sys2);
        verify(sessionRepository).findByAgentTypeAndOriginOrderByUpdatedAtDesc("system",
                SessionEntity.ORIGIN_PRODUCTION);
        // Legacy userId-scoped path must NOT be touched on the system branch.
        verify(sessionRepository, never())
                .findByUserIdAndParentSessionIdIsNullAndOriginOrderByUpdatedAtDesc(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("agentType='user' explicit origin overload routes to JOIN repo with 'user' type")
    void listByAgentType_user_explicitOriginEval() {
        SessionEntity u1 = stubSession(7L, 200L);
        when(sessionRepository.findByAgentTypeAndOriginOrderByUpdatedAtDesc(
                eq("user"), eq(SessionEntity.ORIGIN_EVAL)))
                .thenReturn(List.of(u1));

        List<SessionEntity> result = sessionService.listSessionsByAgentType("user",
                SessionEntity.ORIGIN_EVAL);

        assertThat(result).containsExactly(u1);
        verify(sessionRepository).findByAgentTypeAndOriginOrderByUpdatedAtDesc("user",
                SessionEntity.ORIGIN_EVAL);
    }

    @Test
    @DisplayName("agentType null → NPE; blank → IllegalArgumentException; repo not touched")
    void listByAgentType_null_rejected() {
        assertThatThrownBy(() -> sessionService.listSessionsByAgentType(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sessionService.listSessionsByAgentType(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessionService.listSessionsByAgentType("   "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sessionRepository);
    }
}
