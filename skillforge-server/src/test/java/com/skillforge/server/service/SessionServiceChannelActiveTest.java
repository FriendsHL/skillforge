package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the WeChat/channel "mid-run message spawns a new session + orphans the
 * original session's async delivery (频道断了)" bug.
 *
 * <p>Root cause: {@link SessionService#isChannelSessionActive(String)} — the ONLY caller is
 * {@code ChannelConversationResolver.resolveSession}, which reuses the existing conversation→session
 * binding iff this returns true, otherwise it CLOSES the conversation and spawns a NEW session. A
 * session that is currently {@code running} a task is STILL the live channel session for its
 * conversation (an inbound message must enqueue into it, not fork a new session), so {@code running}
 * MUST be treated as active. Before the fix only null/idle/waiting_user counted → a message arriving
 * mid-run forked a new session and the original session's async channel delivery was orphaned.
 */
@DisplayName("SessionService.isChannelSessionActive — running session stays active (channel routing)")
class SessionServiceChannelActiveTest {

    private SessionRepository sessionRepository;
    private SessionService sessionService;

    private static final String SID = "sess-channel-active";

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        SessionMessageRepository sessionMessageRepository = mock(SessionMessageRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                txManager);
    }

    private void stubSession(String status, String runtimeStatus) {
        SessionEntity s = new SessionEntity();
        s.setId(SID);
        s.setStatus(status);
        s.setRuntimeStatus(runtimeStatus);
        when(sessionRepository.findById(SID)).thenReturn(Optional.of(s));
    }

    @Test
    @DisplayName("running session is active → reused (THE regression guard: no new session / no orphaned delivery)")
    void runningSession_isActive() {
        stubSession("active", "running");
        assertThat(sessionService.isChannelSessionActive(SID)).isTrue();
    }

    @Test
    @DisplayName("idle session is active")
    void idleSession_isActive() {
        stubSession("active", "idle");
        assertThat(sessionService.isChannelSessionActive(SID)).isTrue();
    }

    @Test
    @DisplayName("waiting_user session is active")
    void waitingUserSession_isActive() {
        stubSession("active", "waiting_user");
        assertThat(sessionService.isChannelSessionActive(SID)).isTrue();
    }

    @Test
    @DisplayName("null runtime_status session is active")
    void nullRuntimeStatus_isActive() {
        stubSession("active", null);
        assertThat(sessionService.isChannelSessionActive(SID)).isTrue();
    }

    @Test
    @DisplayName("non-active status (closed/archived) is NOT active → resolver forks a fresh session")
    void closedStatus_isNotActive() {
        stubSession("closed", "idle");
        assertThat(sessionService.isChannelSessionActive(SID)).isFalse();
    }

    @Test
    @DisplayName("error runtime_status is NOT active → resolver forks a fresh session")
    void errorRuntimeStatus_isNotActive() {
        stubSession("active", "error");
        assertThat(sessionService.isChannelSessionActive(SID)).isFalse();
    }

    @Test
    @DisplayName("missing session is NOT active")
    void missingSession_isNotActive() {
        when(sessionRepository.findById(SID)).thenReturn(Optional.empty());
        assertThat(sessionService.isChannelSessionActive(SID)).isFalse();
    }
}
