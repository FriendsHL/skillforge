package com.skillforge.server.channel.router;

import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.repository.UserIdentityMappingRepository;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelConversationResolverTest {

    @Test
    @DisplayName("resolveSession should fallback to default user when mapping is missing")
    void resolveSession_fallbackToDefaultUser_whenMappingMissing() {
        ChannelConversationRepository conversationRepo = mock(ChannelConversationRepository.class);
        UserIdentityMappingRepository identityRepo = mock(UserIdentityMappingRepository.class);
        SessionService sessionService = mock(SessionService.class);
        ChannelConversationResolver resolver = new ChannelConversationResolver(
                conversationRepo, identityRepo, sessionService);

        ChannelMessage message = new ChannelMessage(
                "feishu",
                "oc_123",
                "ou_123",
                "om_123",
                ChannelMessage.MessageType.TEXT,
                "hello",
                null,
                Instant.now(),
                Map.of());
        ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                1L, "feishu", null, "{}", "{\"mode\":\"websocket\"}", 100L);

        when(conversationRepo.findActiveForUpdate("feishu", "oc_123")).thenReturn(Optional.empty());
        when(sessionService.createChannelSession(100L, "[feishu] hello", null))
                .thenReturn("session-1");
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId(SessionService.DEFAULT_CHANNEL_USER_ID);
        when(sessionService.getSession("session-1")).thenReturn(session);
        when(conversationRepo.save(any(ChannelConversationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SessionRouteResult result = resolver.resolveSession(message, config, null);

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.created()).isTrue();
        assertThat(result.skillforgeUserId()).isEqualTo(SessionService.DEFAULT_CHANNEL_USER_ID);
        verify(sessionService).createChannelSession(100L, "[feishu] hello", null);
    }

    @Test
    @DisplayName("resolveSession should use mapped user id when available")
    void resolveSession_useMappedUser_whenMappingExists() {
        ChannelConversationRepository conversationRepo = mock(ChannelConversationRepository.class);
        UserIdentityMappingRepository identityRepo = mock(UserIdentityMappingRepository.class);
        SessionService sessionService = mock(SessionService.class);
        ChannelConversationResolver resolver = new ChannelConversationResolver(
                conversationRepo, identityRepo, sessionService);

        ChannelMessage message = new ChannelMessage(
                "feishu",
                "oc_456",
                "ou_456",
                "om_456",
                ChannelMessage.MessageType.TEXT,
                "hey",
                null,
                Instant.now(),
                Map.of());
        ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                2L, "feishu", null, "{}", "{\"mode\":\"websocket\"}", 200L);

        when(conversationRepo.findActiveForUpdate("feishu", "oc_456")).thenReturn(Optional.empty());
        when(sessionService.createChannelSession(200L, "[feishu] hey", 42L)).thenReturn("session-2");
        SessionEntity session = new SessionEntity();
        session.setId("session-2");
        session.setUserId(42L);
        when(sessionService.getSession("session-2")).thenReturn(session);
        when(conversationRepo.save(any(ChannelConversationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SessionRouteResult result = resolver.resolveSession(message, config, 42L);

        assertThat(result.sessionId()).isEqualTo("session-2");
        assertThat(result.created()).isTrue();
        assertThat(result.skillforgeUserId()).isEqualTo(42L);
        verify(sessionService).createChannelSession(200L, "[feishu] hey", 42L);
    }

    @Test
    @DisplayName("resolveSession should reuse existing session owner for active conversation")
    void resolveSession_reuseExistingSessionOwner_whenConversationActive() {
        ChannelConversationRepository conversationRepo = mock(ChannelConversationRepository.class);
        UserIdentityMappingRepository identityRepo = mock(UserIdentityMappingRepository.class);
        SessionService sessionService = mock(SessionService.class);
        ChannelConversationResolver resolver = new ChannelConversationResolver(
                conversationRepo, identityRepo, sessionService);

        ChannelMessage message = new ChannelMessage(
                "feishu",
                "oc_789",
                "ou_789",
                "om_789",
                ChannelMessage.MessageType.TEXT,
                "reuse",
                null,
                Instant.now(),
                Map.of());
        ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                3L, "feishu", null, "{}", "{\"mode\":\"websocket\"}", 300L);

        ChannelConversationEntity existing = new ChannelConversationEntity();
        existing.setPlatform("feishu");
        existing.setConversationId("oc_789");
        existing.setSessionId("session-existing");
        when(conversationRepo.findActiveForUpdate("feishu", "oc_789")).thenReturn(Optional.of(existing));
        when(sessionService.isChannelSessionActive("session-existing")).thenReturn(true);
        SessionEntity session = new SessionEntity();
        session.setId("session-existing");
        session.setUserId(99L);
        when(sessionService.getSession("session-existing")).thenReturn(session);

        SessionRouteResult result = resolver.resolveSession(message, config, 42L);

        assertThat(result.sessionId()).isEqualTo("session-existing");
        assertThat(result.created()).isFalse();
        assertThat(result.skillforgeUserId()).isEqualTo(99L);
    }
}
