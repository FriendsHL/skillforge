package com.skillforge.server.channel.listener;

import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.delivery.ReplyDeliveryService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CHANNEL-ASYNC-DELIVERY — unit tests for {@link ChannelAsyncDeliveryListener}.
 * Covers the dedup state machine (handled marker), terminal-status filtering
 * (incl. OQ-1 waiting_user delivery), blank filtering, and the non-channel /
 * child-subagent skip (no conversation row).
 */
@ExtendWith(MockitoExtension.class)
class ChannelAsyncDeliveryListenerTest {

    private static final String SESSION_ID = "sess-async-1";
    private static final String PLATFORM = "weixin";
    private static final String CONV_ID = "from-user-42";

    @Mock private ChatWebSocketHandler chatWebSocketHandler;
    @Mock private ChannelConversationRepository conversationRepo;
    @Mock private ReplyDeliveryService deliveryService;
    @Mock private ChannelAdapterRegistry adapterRegistry;
    @Mock private ChannelConfigService configService;
    @Mock private ChannelAdapter adapter;
    @Mock private ChannelConfigDecrypted config;

    private ChannelAsyncDeliveryListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChannelAsyncDeliveryListener(
                chatWebSocketHandler, conversationRepo, deliveryService,
                adapterRegistry, configService);
    }

    private ChannelConversationEntity channelConversation() {
        ChannelConversationEntity conv = new ChannelConversationEntity();
        conv.setPlatform(PLATFORM);
        conv.setConversationId(CONV_ID);
        conv.setSessionId(SESSION_ID);
        return conv;
    }

    /** Wire up the full happy-path collaborators (handled=null, conv present, adapter+config present). */
    private void stubHappyPath() {
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(null);
        when(conversationRepo.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(channelConversation()));
        when(adapterRegistry.get(PLATFORM)).thenReturn(Optional.of(adapter));
        when(configService.getDecryptedConfig(PLATFORM)).thenReturn(Optional.of(config));
    }

    private SessionLoopFinishedEvent event(String status, String message) {
        return new SessionLoopFinishedEvent(SESSION_ID, message, status, 7L);
    }

    @Test
    @DisplayName("async turn on a channel session delivers exactly once")
    void asyncTurn_channelSession_delivers() {
        stubHappyPath();

        listener.onLoopFinished(event("completed", "the merged research report"));

        ArgumentCaptor<ChannelReply> replyCaptor = ArgumentCaptor.forClass(ChannelReply.class);
        verify(deliveryService, times(1))
                .deliver(replyCaptor.capture(), eq(adapter), eq(config), eq(SESSION_ID));
        ChannelReply reply = replyCaptor.getValue();
        assertThat(reply.platform()).isEqualTo(PLATFORM);
        assertThat(reply.conversationId()).isEqualTo(CONV_ID);
        assertThat(reply.markdownText()).isEqualTo("the merged research report");
        assertThat(reply.replyToMessageId()).isNull();
        assertThat(reply.inboundMessageId()).startsWith("async:");
    }

    @Test
    @DisplayName("non-channel session (no conversation row) skips delivery")
    void nonChannelSession_skips() {
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(null);
        when(conversationRepo.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.empty());

        listener.onLoopFinished(event("completed", "plain UI chat output"));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("inbound path already delivered (handled=true) → skip")
    void inboundHandledTrue_skips() {
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(Boolean.TRUE);

        listener.onLoopFinished(event("completed", "inbound reply"));

        verify(conversationRepo, never()).findBySessionIdAndClosedAtIsNull(anyString());
        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("inbound turn registered but not delivered (handled=false) → skip")
    void inboundFalse_skips() {
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(Boolean.FALSE);

        listener.onLoopFinished(event("waiting_user", "do you want A or B?"));

        verify(conversationRepo, never()).findBySessionIdAndClosedAtIsNull(anyString());
        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("blank final message → skip")
    void blankText_skips() {
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(null);

        listener.onLoopFinished(event("completed", "   "));

        verify(conversationRepo, never()).findBySessionIdAndClosedAtIsNull(anyString());
        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("error status → skip")
    void errorStatus_skips() {
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(null);

        listener.onLoopFinished(event("error", "Agent loop failed"));

        verify(conversationRepo, never()).findBySessionIdAndClosedAtIsNull(anyString());
        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("cancelled status → skip")
    void cancelledStatus_skips() {
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(null);

        listener.onLoopFinished(event("cancelled", "partial output"));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("aborted_by_hook status → skip")
    void abortedByHook_skips() {
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(null);

        listener.onLoopFinished(event("aborted_by_hook", "blocked output"));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("OQ-1: async turn ending in waiting_user delivers the ask text")
    void waitingUser_delivers() {
        stubHappyPath();

        listener.onLoopFinished(event("waiting_user", "Which dataset should I use?"));

        ArgumentCaptor<ChannelReply> replyCaptor = ArgumentCaptor.forClass(ChannelReply.class);
        verify(deliveryService, times(1))
                .deliver(replyCaptor.capture(), eq(adapter), eq(config), eq(SESSION_ID));
        assertThat(replyCaptor.getValue().markdownText()).isEqualTo("Which dataset should I use?");
    }

    @Test
    @DisplayName("child subagent session (no conversation row) skips — parent owns delivery")
    void childSubagentSession_skips() {
        // A child session resumes/finishes its own loop; it has no channel conversation row
        // (the channel binds to the parent root session). handled=null (never registered).
        when(chatWebSocketHandler.removeChannelTurnHandled(SESSION_ID)).thenReturn(null);
        when(conversationRepo.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.empty());
        // adapter/config stubs are lenient — they must NOT be reached.
        lenient().when(adapterRegistry.get(anyString())).thenReturn(Optional.of(adapter));
        lenient().when(configService.getDecryptedConfig(anyString())).thenReturn(Optional.of(config));

        listener.onLoopFinished(event("completed", "child subagent finding"));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }
}
