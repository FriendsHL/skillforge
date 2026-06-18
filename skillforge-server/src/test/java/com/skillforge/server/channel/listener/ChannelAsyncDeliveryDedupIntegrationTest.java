package com.skillforge.server.channel.listener;

import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.delivery.ReplyDeliveryService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CHANNEL-ASYNC-DELIVERY — integration-style dedup test wiring a REAL
 * {@link ChatWebSocketHandler} into a REAL {@link ChannelAsyncDeliveryListener}.
 * Only the downstream collaborators are mocked (ReplyDeliveryService /
 * ChannelConversationRepository / ChannelAdapterRegistry / ChannelConfigService).
 *
 * <p>Unlike {@link ChannelAsyncDeliveryListenerTest} (which mocks
 * {@code removeChannelTurnHandled} with canned values), this test exercises the FULL
 * dedup state machine: registerChannelTurn → sessionStatus → onLoopFinished. It is the
 * load-bearing lock on the invariant: it would FAIL if the {@code put(true)} on publish
 * were dropped/misordered, or if {@code sessionStatus} removed the false marker on
 * blank/error (the fixed blocker).
 */
@ExtendWith(MockitoExtension.class)
class ChannelAsyncDeliveryDedupIntegrationTest {

    private static final String SESSION_ID = "sess-dedup-int-1";
    private static final String PLATFORM = "weixin";
    private static final String CONV_ID = "from-user-99";

    @Mock private ReplyDeliveryService deliveryService;
    @Mock private ChannelConversationRepository conversationRepo;
    @Mock private ChannelAdapterRegistry adapterRegistry;
    @Mock private ChannelConfigService configService;
    @Mock private ChannelAdapter adapter;
    @Mock private ChannelConfigDecrypted config;

    private ChatWebSocketHandler realHandler;
    private ChannelAsyncDeliveryListener listener;

    @BeforeEach
    void setUp() {
        // Real handler: only the constructor deps it doesn't exercise in this path are mocked.
        realHandler = new ChatWebSocketHandler(
                mock(UserWebSocketHandler.class),
                mock(com.skillforge.server.repository.CollabRunRepository.class),
                mock(com.skillforge.server.repository.SessionRepository.class),
                mock(ApplicationEventPublisher.class));
        listener = new ChannelAsyncDeliveryListener(
                realHandler, conversationRepo, deliveryService, adapterRegistry, configService);
    }

    private ChannelConversationEntity channelConversation() {
        ChannelConversationEntity conv = new ChannelConversationEntity();
        conv.setPlatform(PLATFORM);
        conv.setConversationId(CONV_ID);
        conv.setSessionId(SESSION_ID);
        return conv;
    }

    /** Stub the downstream so a delivery (if reached) succeeds — used by the async-deliver cases. */
    private void stubDownstreamPresent() {
        when(conversationRepo.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(channelConversation()));
        when(adapterRegistry.get(PLATFORM)).thenReturn(Optional.of(adapter));
        when(configService.getDecryptedConfig(PLATFORM)).thenReturn(Optional.of(config));
    }

    /** Lenient downstream stubs for skip-cases where they must NOT be reached. */
    private void stubDownstreamLenient() {
        lenient().when(conversationRepo.findBySessionIdAndClosedAtIsNull(anyString()))
                .thenReturn(Optional.of(channelConversation()));
        lenient().when(adapterRegistry.get(anyString())).thenReturn(Optional.of(adapter));
        lenient().when(configService.getDecryptedConfig(anyString())).thenReturn(Optional.of(config));
    }

    private SessionLoopFinishedEvent event(String status, String message) {
        return new SessionLoopFinishedEvent(SESSION_ID, message, status, 11L);
    }

    @Test
    @DisplayName("(a) inbound non-blank: inbound path owns → async listener does NOT deliver")
    void inboundNonBlank_asyncDoesNotDeliver() {
        stubDownstreamLenient();

        // Full inbound sequence through the real handler.
        realHandler.registerChannelTurn(SESSION_ID, "wx1|msg-a", null);
        realHandler.assistantDelta(SESSION_ID, "the inbound answer");
        realHandler.assistantStreamEnd(SESSION_ID);
        realHandler.sessionStatus(SESSION_ID, "idle", null, null); // publishes → marker=true

        // Async listener fires on the same loop's SessionLoopFinishedEvent.
        listener.onLoopFinished(event("completed", "the inbound answer"));

        // marker was true (non-null) → async path must skip. Inbound delivery goes via
        // ChannelReplyEventListener (the ChannelSessionOutputEvent), NOT via this listener.
        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("(b) REGRESSION: inbound blank ctx.finalText but non-blank event.finalMessage → async still skips")
    void inboundBlankCtxButNonBlankEvent_asyncSkips_regression() {
        stubDownstreamLenient();

        // Inbound turn registered, but the WS delta accumulator (ctx.finalText) ends blank
        // (e.g. stream interrupted) → sessionStatus(idle) publishes nothing, marker stays false.
        realHandler.registerChannelTurn(SESSION_ID, "wx1|msg-b", null);
        realHandler.sessionStatus(SESSION_ID, "idle", null, null); // blank → no publish, marker=false

        // The ChatService loop result accumulator (event.finalMessage) IS non-blank — the
        // two accumulators diverged. Pre-fix code removed the marker here → async path got
        // null → would deliver. Post-fix the marker stays false → async path skips.
        listener.onLoopFinished(event("completed", "non-blank loop result text"));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("(c) pure async: no registerChannelTurn → delivers exactly once")
    void pureAsync_delivers() {
        stubDownstreamPresent();

        // No registerChannelTurn → marker absent → listener owns delivery.
        listener.onLoopFinished(event("completed", "async subagent merged report"));

        verify(deliveryService, times(1)).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("(d) inbound waiting_user → async skips (register-time false marker survives)")
    void inboundWaitingUser_asyncSkips() {
        stubDownstreamLenient();

        realHandler.registerChannelTurn(SESSION_ID, "wx1|msg-d", null);
        // waiting_user is never handled by sessionStatus's idle/error branch → marker stays false.
        realHandler.sessionStatus(SESSION_ID, "waiting_user", "waiting_control", null);

        listener.onLoopFinished(event("waiting_user", "do you want A or B?"));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("(d2) async waiting_user (no register) → delivers the ask text")
    void asyncWaitingUser_delivers() {
        stubDownstreamPresent();

        // No registerChannelTurn → marker absent → OQ-1: deliver waiting_user ask text.
        listener.onLoopFinished(event("waiting_user", "Which dataset should I use?"));

        verify(deliveryService, times(1)).deliver(any(), any(), any(), anyString());
    }
}
