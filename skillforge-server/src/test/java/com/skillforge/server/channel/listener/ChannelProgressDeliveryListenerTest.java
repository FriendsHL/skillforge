package com.skillforge.server.channel.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.delivery.ReplyDeliveryService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.service.event.AssistantTurnAppendedEvent;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CHANNEL-MIDTURN-PROGRESS — unit tests for {@link ChannelProgressDeliveryListener}.
 * Covers the OQ-1 tool_use dedup (text-only terminal turn is NOT delivered here),
 * non-channel skip, throttling, per-channel enable flag, short-text skip, best-effort
 * swallow, and loop-finished throttle reset.
 */
@ExtendWith(MockitoExtension.class)
class ChannelProgressDeliveryListenerTest {

    private static final String SESSION_ID = "sess-progress-1";
    private static final String PLATFORM = "feishu";
    private static final String CONV_ID = "open-chat-42";

    @Mock private ChannelConversationRepository conversationRepo;
    @Mock private ReplyDeliveryService deliveryService;
    @Mock private ChannelAdapterRegistry adapterRegistry;
    @Mock private ChannelConfigService configService;
    @Mock private ChannelAdapter adapter;
    @Mock private ChannelConfigDecrypted config;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChannelProgressDeliveryListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChannelProgressDeliveryListener(
                conversationRepo, deliveryService, adapterRegistry, configService, objectMapper);
    }

    private ChannelConversationEntity channelConversation() {
        ChannelConversationEntity conv = new ChannelConversationEntity();
        conv.setPlatform(PLATFORM);
        conv.setConversationId(CONV_ID);
        conv.setSessionId(SESSION_ID);
        return conv;
    }

    /** assistant message with one text block + one tool_use block (narration-before-action). */
    private Message narrationWithToolUse(String text) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(
                ContentBlock.text(text),
                ContentBlock.toolUse("tu-1", "Bash", Map.of("command", "ls"))));
        return m;
    }

    /** assistant message with ONLY a text block (terminal final answer). */
    private Message textOnly(String text) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(ContentBlock.text(text)));
        return m;
    }

    private AssistantTurnAppendedEvent event(Message message) {
        return new AssistantTurnAppendedEvent(SESSION_ID, "trace-1", message);
    }

    /** Wire up the channel-bound happy path: conv present, adapter+config present, progress enabled. */
    private void stubChannelBound(String configJson) {
        when(conversationRepo.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(channelConversation()));
        when(adapterRegistry.get(PLATFORM)).thenReturn(Optional.of(adapter));
        when(configService.getDecryptedConfig(PLATFORM)).thenReturn(Optional.of(config));
        lenient().when(config.configJson()).thenReturn(configJson);
    }

    @Test
    @DisplayName("assistant turn WITH tool_use + text → delivers with prefix, text extracted")
    void turnWithToolUse_delivers() {
        stubChannelBound("{\"progressDelivery\":{\"enabled\":true,\"minIntervalMs\":2000,\"maxPerRun\":12,\"minChars\":8,\"prefix\":\"PFX \"}}");

        listener.onAssistantTurn(event(narrationWithToolUse("Let me search the codebase first")));

        ArgumentCaptor<ChannelReply> captor = ArgumentCaptor.forClass(ChannelReply.class);
        verify(deliveryService).deliver(captor.capture(), eq(adapter), eq(config), eq(SESSION_ID));
        ChannelReply reply = captor.getValue();
        assertThat(reply.markdownText()).isEqualTo("PFX Let me search the codebase first");
        assertThat(reply.platform()).isEqualTo(PLATFORM);
        assertThat(reply.conversationId()).isEqualTo(CONV_ID);
    }

    @Test
    @DisplayName("REGRESSION-CRITICAL: text-only terminal turn (NO tool_use) is NOT delivered by progress")
    void textOnlyTurn_notDelivered() {
        // No stubbing needed beyond the message — the tool_use guard returns before any lookup.
        listener.onAssistantTurn(event(textOnly("Here is the final answer to your question.")));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
        verify(conversationRepo, never()).findBySessionIdAndClosedAtIsNull(anyString());
    }

    @Test
    @DisplayName("non-channel session (no conversation row) → does NOT deliver")
    void nonChannelSession_notDelivered() {
        when(conversationRepo.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.empty());

        listener.onAssistantTurn(event(narrationWithToolUse("Working on it now please wait")));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("throttle: 2nd call within minIntervalMs is dropped")
    void throttle_minInterval_dropsSecond() {
        stubChannelBound("{\"progressDelivery\":{\"enabled\":true,\"minIntervalMs\":60000,\"maxPerRun\":12,\"minChars\":1,\"prefix\":\"\"}}");

        listener.onAssistantTurn(event(narrationWithToolUse("first step running")));
        listener.onAssistantTurn(event(narrationWithToolUse("second step running")));

        // Only the first should reach delivery (interval gate drops the second).
        verify(deliveryService, times(1)).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("throttle: calls after maxPerRun reached are dropped")
    void throttle_maxPerRun_dropsExtra() {
        // minIntervalMs=0 so interval never blocks; maxPerRun=2 caps the run.
        stubChannelBound("{\"progressDelivery\":{\"enabled\":true,\"minIntervalMs\":0,\"maxPerRun\":2,\"minChars\":1,\"prefix\":\"\"}}");

        listener.onAssistantTurn(event(narrationWithToolUse("step one here")));
        listener.onAssistantTurn(event(narrationWithToolUse("step two here")));
        listener.onAssistantTurn(event(narrationWithToolUse("step three here")));

        verify(deliveryService, times(2)).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("progressDelivery.enabled=false → does NOT deliver")
    void disabled_notDelivered() {
        stubChannelBound("{\"progressDelivery\":{\"enabled\":false}}");

        listener.onAssistantTurn(event(narrationWithToolUse("this should not be delivered")));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("weixin default (no config block) → OFF, does NOT deliver")
    void weixinPlatformDefault_off_notDelivered() {
        ChannelConversationEntity conv = new ChannelConversationEntity();
        conv.setPlatform("weixin");
        conv.setConversationId(CONV_ID);
        conv.setSessionId(SESSION_ID);
        when(conversationRepo.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(conv));
        when(adapterRegistry.get("weixin")).thenReturn(Optional.of(adapter));
        when(configService.getDecryptedConfig("weixin")).thenReturn(Optional.of(config));
        lenient().when(config.configJson()).thenReturn("{}"); // no progressDelivery block → platform default

        listener.onAssistantTurn(event(narrationWithToolUse("weixin should default off")));

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("feishu default (no config block) → ON, delivers")
    void feishuPlatformDefault_on_delivers() {
        stubChannelBound("{}"); // platform default for feishu = enabled

        listener.onAssistantTurn(event(narrationWithToolUse("feishu defaults on so this sends")));

        verify(deliveryService, times(1)).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("empty / short text → dropped")
    void shortText_dropped() {
        stubChannelBound("{\"progressDelivery\":{\"enabled\":true,\"minIntervalMs\":0,\"maxPerRun\":12,\"minChars\":8,\"prefix\":\"\"}}");

        listener.onAssistantTurn(event(narrationWithToolUse("hi")));   // 2 chars < 8

        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("exception from ReplyDeliveryService is swallowed (no rethrow)")
    void deliveryException_swallowed() {
        stubChannelBound("{\"progressDelivery\":{\"enabled\":true,\"minIntervalMs\":0,\"maxPerRun\":12,\"minChars\":1,\"prefix\":\"\"}}");
        doThrow(new RuntimeException("boom"))
                .when(deliveryService).deliver(any(), any(), any(), anyString());

        // Must not throw.
        listener.onAssistantTurn(event(narrationWithToolUse("this throws downstream")));

        verify(deliveryService, times(1)).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("loop-finished event resets throttle state (next run delivers again)")
    void loopFinished_resetsThrottle() {
        stubChannelBound("{\"progressDelivery\":{\"enabled\":true,\"minIntervalMs\":0,\"maxPerRun\":1,\"minChars\":1,\"prefix\":\"\"}}");

        // First run: 1 delivery, then cap reached → second dropped.
        listener.onAssistantTurn(event(narrationWithToolUse("run one step one")));
        listener.onAssistantTurn(event(narrationWithToolUse("run one step two")));
        verify(deliveryService, times(1)).deliver(any(), any(), any(), anyString());

        // Loop finished → reset throttle for this conversation.
        when(conversationRepo.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(channelConversation()));
        listener.onLoopFinished(new SessionLoopFinishedEvent(SESSION_ID, "done", "completed", 7L));

        // Next run delivers again (count reset).
        listener.onAssistantTurn(event(narrationWithToolUse("run two step one")));
        verify(deliveryService, times(2)).deliver(any(), any(), any(), anyString());
    }
}
