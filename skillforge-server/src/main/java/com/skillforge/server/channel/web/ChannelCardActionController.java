package com.skillforge.server.channel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.feishu.FeishuCardActionVerifier;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import com.skillforge.server.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Feishu interactive-card action callback endpoint. Strict signature verification
 * (B2 fix) via {@link FeishuCardActionVerifier}, then matches the posted
 * {@code confirmationId} to a {@link PendingConfirmation} and verifies the actor is
 * the turn triggerer (multi-user chat authorization).
 *
 * <p>Return contract: always HTTP 200 for permission / state errors so the card
 * renders a toast to the user; only signature failures return 401. Feishu expects
 * HTTP 200 for cards with a {@code toast} field in the response body.
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelCardActionController {

    private static final Logger log = LoggerFactory.getLogger(ChannelCardActionController.class);

    private final FeishuCardActionVerifier verifier;
    private final ChannelConfigService channelConfigService;
    private final PendingConfirmationRegistry pendingConfirmationRegistry;
    private final ObjectMapper objectMapper;
    private final ChatService chatService;

    public ChannelCardActionController(FeishuCardActionVerifier verifier,
                                       ChannelConfigService channelConfigService,
                                       PendingConfirmationRegistry pendingConfirmationRegistry,
                                       ObjectMapper objectMapper,
                                       ChatService chatService) {
        this.verifier = verifier;
        this.channelConfigService = channelConfigService;
        this.pendingConfirmationRegistry = pendingConfirmationRegistry;
        this.objectMapper = objectMapper;
        this.chatService = chatService;
    }

    ChannelCardActionController(FeishuCardActionVerifier verifier,
                                ChannelConfigService channelConfigService,
                                PendingConfirmationRegistry pendingConfirmationRegistry,
                                ObjectMapper objectMapper) {
        this(verifier, channelConfigService, pendingConfirmationRegistry, objectMapper, null);
    }

    @PostMapping("/feishu/card-action")
    public ResponseEntity<?> feishuCardAction(@RequestBody(required = false) byte[] rawBody,
                                              HttpServletRequest req) {
        byte[] body = rawBody != null ? rawBody : new byte[0];
        Optional<ChannelConfigDecrypted> cfgOpt = channelConfigService.getDecryptedConfig("feishu");
        if (cfgOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "feishu not configured"));
        }
        ChannelConfigDecrypted cfg = cfgOpt.get();
        WebhookContext ctx = new WebhookContext(headerMap(req), body);
        try {
            verifier.verifyStrict(ctx, cfg.webhookSecret());
        } catch (WebhookVerificationException e) {
            log.warn("Card-action verify failed: {}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "verification failed"));
        }

        // Parse body
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid json body"));
        }
        // Feishu card_action payload shape (simplified): { open_id, action: { value: { confirmationId, decision } } }
        String openId = firstNonNull(
                root.path("open_id").asText(null),
                root.path("operator").path("open_id").asText(null),
                root.path("user_id").asText(null));
        JsonNode actionValue = root.path("action").path("value");
        if (actionValue.isMissingNode() || actionValue.isNull()) {
            actionValue = root.path("action_value");
        }
        String confirmationId = actionValue.path("confirmationId").asText(null);
        String decisionRaw = actionValue.path("decision").asText(null);
        if (confirmationId == null || decisionRaw == null) {
            return ResponseEntity.ok(toast("Invalid card payload"));
        }

        PendingConfirmation pc = pendingConfirmationRegistry.peek(confirmationId);
        if (pc == null) {
            return ResponseEntity.ok(toast("授权请求已失效或已被处理"));
        }
        if (pc.triggererOpenId() != null && openId != null && !pc.triggererOpenId().equals(openId)) {
            log.warn("Card-action unauthorized: confirmationId={} triggerer={} actor={}",
                    confirmationId, pc.triggererOpenId(), openId);
            return ResponseEntity.ok(toast("仅请求者可授权,你无权操作"));
        }

        Decision decision;
        try {
            decision = Decision.fromJson(decisionRaw);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(toast("Invalid decision"));
        }
        if (decision == Decision.TIMEOUT) {
            return ResponseEntity.ok(toast("Invalid decision"));
        }

        if (chatService != null) {
            try {
                chatService.answerConfirmation(pc.sessionId(), confirmationId, decision, null);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return ResponseEntity.ok(toast("授权请求已失效或已被处理"));
            }
        } else {
            boolean ok = pendingConfirmationRegistry.complete(confirmationId, decision, openId);
            if (!ok) {
                return ResponseEntity.ok(toast("授权请求已失效或已被处理"));
            }
        }
        return ResponseEntity.ok(toast(decision == Decision.APPROVED ? "✅ 已批准" : "❌ 已拒绝"));
    }

    private static Map<String, Object> toast(String text) {
        // Feishu card response contract: { "toast": { "type": "info", "content": "..." } }
        Map<String, Object> toast = new HashMap<>();
        toast.put("type", "info");
        toast.put("content", text);
        return Map.of("toast", toast);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        }
        return null;
    }

    private Map<String, String> headerMap(HttpServletRequest request) {
        Map<String, String> out = new HashMap<>();
        var names = request.getHeaderNames();
        if (names == null) return Collections.emptyMap();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            out.put(n.toLowerCase(), request.getHeader(n));
        }
        return out;
    }
}
