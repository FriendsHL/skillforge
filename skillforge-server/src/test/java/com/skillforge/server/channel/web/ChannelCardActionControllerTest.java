package com.skillforge.server.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.feishu.FeishuCardActionVerifier;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelCardActionControllerTest {

    private FeishuCardActionVerifier verifier;
    private ChannelConfigService configService;
    private PendingConfirmationRegistry registry;
    private ChannelCardActionController controller;
    private final ObjectMapper om = new ObjectMapper();
    private HttpServletRequest req;

    @BeforeEach
    void setup() {
        verifier = mock(FeishuCardActionVerifier.class);
        configService = mock(ChannelConfigService.class);
        registry = new PendingConfirmationRegistry();
        controller = new ChannelCardActionController(verifier, configService, registry, om);
        req = mock(HttpServletRequest.class);
        Enumeration<String> empty = Collections.enumeration(Collections.emptyList());
        when(req.getHeaderNames()).thenReturn(empty);

        when(configService.getDecryptedConfig("feishu")).thenReturn(Optional.of(
                new ChannelConfigDecrypted(1L, "feishu", "encrypt-key", null, null, null)));
    }

    private PendingConfirmation pc(String cid, String sid, String triggerer) {
        return new PendingConfirmation(cid, sid, "tu-" + cid,
                "clawhub", "obsidian", "clawhub install obsidian", triggerer, 30);
    }

    @Test
    @DisplayName("verify fails → 401")
    void verifyFails() throws Exception {
        doThrow(new WebhookVerificationException("feishu-card-action", "bad sig"))
                .when(verifier).verifyStrict(any(), anyString());
        byte[] body = om.writeValueAsBytes(java.util.Map.of());
        ResponseEntity<?> r = controller.feishuCardAction(body, req);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("unknown confirmationId → 200 toast expired")
    void unknownId() throws Exception {
        doNothing().when(verifier).verifyStrict(any(), anyString());
        byte[] body = om.writeValueAsBytes(java.util.Map.of(
                "open_id", "u1",
                "action", java.util.Map.of("value", java.util.Map.of(
                        "confirmationId", "nope", "decision", "approved"))));
        ResponseEntity<?> r = controller.feishuCardAction(body, req);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().toString()).contains("失效");
    }

    @Test
    @DisplayName("non-triggerer click → 200 toast unauthorized, does NOT complete")
    void nonTriggererClick() throws Exception {
        doNothing().when(verifier).verifyStrict(any(), anyString());
        registry.register(pc("c1", "s1", "trigger-user"));
        byte[] body = om.writeValueAsBytes(java.util.Map.of(
                "open_id", "other-user",
                "action", java.util.Map.of("value", java.util.Map.of(
                        "confirmationId", "c1", "decision", "approved"))));
        ResponseEntity<?> r = controller.feishuCardAction(body, req);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().toString()).contains("无权");
        // pending still present and not completed — a subsequent legitimate caller
        // can still complete it.
        assertThat(registry.peek("c1")).isNotNull();
        assertThat(registry.complete("c1", Decision.APPROVED, "trigger-user")).isTrue();
    }

    @Test
    @DisplayName("triggerer approves → registry completes, toast approved")
    void happyApprove() throws Exception {
        doNothing().when(verifier).verifyStrict(any(), anyString());
        registry.register(pc("c1", "s1", "trigger-user"));
        byte[] body = om.writeValueAsBytes(java.util.Map.of(
                "open_id", "trigger-user",
                "action", java.util.Map.of("value", java.util.Map.of(
                        "confirmationId", "c1", "decision", "approved"))));
        ResponseEntity<?> r = controller.feishuCardAction(body, req);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().toString()).contains("已批准");
        // Verify completion: a second complete() call must return false now (decision already set)
        assertThat(registry.complete("c1", Decision.DENIED, null)).isFalse();
    }

    @Test
    @DisplayName("feishu not configured → 404")
    void notConfigured() throws Exception {
        when(configService.getDecryptedConfig("feishu")).thenReturn(Optional.empty());
        byte[] body = om.writeValueAsBytes(java.util.Map.of());
        ResponseEntity<?> r = controller.feishuCardAction(body, req);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
    }
}
