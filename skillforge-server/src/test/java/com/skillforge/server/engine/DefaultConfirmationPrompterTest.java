package com.skillforge.server.engine;

import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.confirm.ChannelUnavailableException;
import com.skillforge.core.engine.confirm.ConfirmationPrompter;
import com.skillforge.core.engine.confirm.ConfirmationPromptPayload;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.feishu.FeishuClient;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.DeliveryResult;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultConfirmationPrompterTest {

    private PendingConfirmationRegistry registry;
    private ChatEventBroadcaster broadcaster;
    private ChatWebSocketHandler wsHandler;
    private ChannelConversationRepository convRepo;
    private ChannelConfigService cfgSvc;
    private FeishuClient feishu;

    private DefaultConfirmationPrompter prompter;

    @BeforeEach
    void setup() {
        registry = new PendingConfirmationRegistry();
        broadcaster = mock(ChatEventBroadcaster.class);
        wsHandler = mock(ChatWebSocketHandler.class);
        convRepo = mock(ChannelConversationRepository.class);
        cfgSvc = mock(ChannelConfigService.class);
        feishu = mock(FeishuClient.class);
        prompter = new DefaultConfirmationPrompter(registry, broadcaster, wsHandler,
                convRepo, cfgSvc, feishu);
    }

    private ConfirmationPrompter.ConfirmationRequest req(String sid, long timeout) {
        return new ConfirmationPrompter.ConfirmationRequest(sid, 1L, "tu-1",
                "clawhub", "obsidian", "clawhub install obsidian", null, timeout);
    }

    @Test
    @DisplayName("web channel happy: complete(APPROVED) unblocks prompt with APPROVED")
    void webHappyApproved() throws Exception {
        when(convRepo.findBySessionIdAndClosedAtIsNull(anyString())).thenReturn(Optional.empty());
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> {
            ConfirmationPromptPayload payload = inv.getArgument(1);
            captured.set(payload.confirmationId());
            return null;
        }).when(broadcaster).confirmationRequired(anyString(), any());

        var ex = Executors.newSingleThreadExecutor();
        try {
            Future<Decision> f = ex.submit(() -> prompter.prompt(req("s1", 5)));
            // Busy-wait for the prompter to broadcast (and thereby expose the id).
            long deadline = System.currentTimeMillis() + 2000;
            while (captured.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(captured.get()).isNotNull();
            assertThat(registry.complete(captured.get(), Decision.APPROVED, null)).isTrue();
            assertThat(f.get(3, TimeUnit.SECONDS)).isEqualTo(Decision.APPROVED);
        } finally {
            ex.shutdownNow();
        }
        verify(broadcaster).sessionStatus(eq("s1"), eq("waiting_user"), anyString(), any());
        // Cleaned up after finally
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("feishu encryptKey blank → ChannelUnavailableException before any registration")
    void feishuNoKey() {
        ChannelConversationEntity conv = new ChannelConversationEntity();
        conv.setPlatform("feishu");
        conv.setConversationId("chat-1");
        conv.setSessionId("s1");
        when(convRepo.findBySessionIdAndClosedAtIsNull(anyString())).thenReturn(Optional.of(conv));
        when(cfgSvc.getDecryptedConfig("feishu")).thenReturn(Optional.of(
                new ChannelConfigDecrypted(1L, "feishu", null, null, null, null)));
        assertThatThrownBy(() -> prompter.prompt(req("s1", 5)))
                .isInstanceOf(ChannelUnavailableException.class)
                .hasMessageContaining("encryptKey not configured");
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("feishu sendInteractiveAction fails → ChannelUnavailableException, registry cleaned")
    void feishuSendFails() {
        ChannelConversationEntity conv = new ChannelConversationEntity();
        conv.setPlatform("feishu");
        conv.setConversationId("chat-1");
        conv.setSessionId("s1");
        when(convRepo.findBySessionIdAndClosedAtIsNull(anyString())).thenReturn(Optional.of(conv));
        when(cfgSvc.getDecryptedConfig("feishu")).thenReturn(Optional.of(
                new ChannelConfigDecrypted(1L, "feishu", "encrypt-key", null, null, null)));
        when(feishu.sendInteractiveAction(anyString(), any(), any()))
                .thenReturn(DeliveryResult.failed("boom"));

        assertThatThrownBy(() -> prompter.prompt(req("s1", 5)))
                .isInstanceOf(ChannelUnavailableException.class);
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("timeout returns TIMEOUT, registry cleaned")
    void timeout() {
        when(convRepo.findBySessionIdAndClosedAtIsNull(anyString())).thenReturn(Optional.empty());
        Decision d = prompter.prompt(req("s1", 1));
        assertThat(d).isEqualTo(Decision.TIMEOUT);
        assertThat(registry.size()).isZero();
    }
}
