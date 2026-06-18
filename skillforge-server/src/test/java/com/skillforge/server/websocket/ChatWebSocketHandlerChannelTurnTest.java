package com.skillforge.server.websocket;

import com.skillforge.server.channel.event.ChannelSessionOutputEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CHANNEL-ASYNC-DELIVERY — verify the 3-state {@code channelTurnHandled} marker
 * transitions driven by {@code registerChannelTurn} / {@code sessionStatus}, read via
 * {@code removeChannelTurnHandled}. This is the inbound side of the dedup state machine.
 */
class ChatWebSocketHandlerChannelTurnTest {

    private static final String SESSION_ID = "sess-turn-1";

    private ChatWebSocketHandler handler;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        handler = new ChatWebSocketHandler(
                mock(UserWebSocketHandler.class),
                mock(com.skillforge.server.repository.CollabRunRepository.class),
                mock(com.skillforge.server.repository.SessionRepository.class),
                eventPublisher);
    }

    @Test
    @DisplayName("registerChannelTurn → marker is false (inbound registered, not delivered)")
    void register_marksFalse() {
        handler.registerChannelTurn(SESSION_ID, "wx1|msg", null);

        assertThat(handler.removeChannelTurnHandled(SESSION_ID)).isFalse();
    }

    @Test
    @DisplayName("sessionStatus(idle) with non-blank reply → publishes + marker is true")
    void idleNonBlank_marksTrue() {
        handler.registerChannelTurn(SESSION_ID, "wx1|msg", null);
        // Populate finalText: deltas accumulate, stream end snapshots into finalText.
        handler.assistantDelta(SESSION_ID, "hello from agent");
        handler.assistantStreamEnd(SESSION_ID);

        handler.sessionStatus(SESSION_ID, "idle", null, null);

        verify(eventPublisher, times(1)).publishEvent(any(ChannelSessionOutputEvent.class));
        assertThat(handler.removeChannelTurnHandled(SESSION_ID)).isTrue();
    }

    @Test
    @DisplayName("sessionStatus(idle) with blank reply → no publish + marker stays false (NOT removed)")
    void idleBlank_markerStaysFalse() {
        handler.registerChannelTurn(SESSION_ID, "wx1|msg", null);
        // No assistant output → finalText stays null/blank.

        handler.sessionStatus(SESSION_ID, "idle", null, null);

        verify(eventPublisher, never()).publishEvent(any(ChannelSessionOutputEvent.class));
        // BLOCKER FIX: marker must stay false (inbound owns the turn) so the async listener
        // skips even when ctx.finalText is blank but event.finalMessage may not be.
        assertThat(handler.removeChannelTurnHandled(SESSION_ID)).isFalse();
    }

    @Test
    @DisplayName("sessionStatus(error) with ctx present → no publish + marker stays false (NOT removed)")
    void error_markerStaysFalse() {
        handler.registerChannelTurn(SESSION_ID, "wx1|msg", null);
        handler.assistantDelta(SESSION_ID, "partial");
        handler.assistantStreamEnd(SESSION_ID);

        handler.sessionStatus(SESSION_ID, "error", null, "boom");

        verify(eventPublisher, never()).publishEvent(any(ChannelSessionOutputEvent.class));
        // BLOCKER FIX: error path leaves the false marker in place; the async listener's own
        // status guard handles error, but the marker guarantees dedup independently.
        assertThat(handler.removeChannelTurnHandled(SESSION_ID)).isFalse();
    }

    @Test
    @DisplayName("no registerChannelTurn (async/non-channel) → removeChannelTurnHandled returns null")
    void noRegister_returnsNull() {
        assertThat(handler.removeChannelTurnHandled(SESSION_ID)).isNull();
    }

    @Test
    @DisplayName("inbound waiting_user keeps register-time false marker (sessionStatus untouched)")
    void waitingUserInbound_keepsFalse() {
        handler.registerChannelTurn(SESSION_ID, "wx1|msg", null);
        // waiting_user is never passed to sessionStatus's idle/error branch, so the
        // register-time false marker survives → async listener will see false → skip.
        handler.sessionStatus(SESSION_ID, "waiting_user", "waiting_control", null);

        assertThat(handler.removeChannelTurnHandled(SESSION_ID)).isFalse();
    }
}
