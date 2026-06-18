package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.entity.ChannelConfigEntity;
import com.skillforge.server.hook.method.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * QR-login REST for the weixin (ClawBot) channel — the only inbound HTTP surface for this
 * platform (INV-4). FE 扫码 UX comes in a later slice; this is backend-only, testable via curl.
 *
 * <ul>
 *   <li>{@code POST /api/channel-configs/weixin/qr-login/start} → {@code {qrcode, qrcode_img_content}}</li>
 *   <li>{@code GET  /api/channel-configs/weixin/qr-login/status?qrcode=} → {@code {status}}; on
 *       {@code confirmed} persists {@code bot_token} + {@code baseurl} into the weixin config
 *       credentials_json.</li>
 * </ul>
 *
 * <p>Note: the bot_token is persisted but never returned in responses or logged.
 */
@RestController
@RequestMapping("/api/channel-configs/weixin/qr-login")
public class WeixinChannelController {

    private static final Logger log = LoggerFactory.getLogger(WeixinChannelController.class);
    private static final String PLATFORM = "weixin";

    private final WeixinIlinkClient client;
    private final ChannelConfigService configService;
    private final ObjectMapper objectMapper;

    public WeixinChannelController(WeixinIlinkClient client,
                                   ChannelConfigService configService,
                                   ObjectMapper objectMapper) {
        this.client = client;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start() {
        try {
            String baseurl = existingBaseurl();
            WeixinIlinkClient.QrCode qr = client.getBotQrcode(baseurl);
            return ResponseEntity.ok(Map.of(
                    "qrcode", qr.qrcode(),
                    "qrcode_img_content", qr.qrcodeImgContent()));
        } catch (Exception e) {
            log.warn("weixin qr-login start failed: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "iLink API error"));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam("qrcode") String qrcode) {
        if (qrcode == null || qrcode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "qrcode required"));
        }
        WeixinIlinkClient.QrStatus status;
        try {
            status = client.getQrcodeStatus(qrcode, existingBaseurl());
        } catch (Exception e) {
            log.warn("weixin qr-login status failed: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "iLink API error"));
        }
        if (status.confirmed() && status.botToken() != null && !status.botToken().isBlank()) {
            try {
                persistCredentials(status.botToken(), status.baseurl());
            } catch (BindRejectedException e) {
                // Operator-actionable config error (no config row / bad baseurl) — safe to surface.
                log.warn("weixin bind rejected: {}", e.getMessage());
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.ok(Map.of("status", "confirmed", "bound", true));
        }
        return ResponseEntity.ok(Map.of("status", status.status(), "bound", false));
    }

    /** Thrown when a confirmed scan cannot be persisted (missing config / invalid baseurl). */
    private static final class BindRejectedException extends RuntimeException {
        BindRejectedException(String message) {
            super(message);
        }
    }

    /** Read the baseurl from an existing weixin config, if any, to override the default host. */
    private String existingBaseurl() {
        return configService.getById(weixinConfigIdOrNull())
                .map(this::baseurlFromCreds)
                .orElse(null);
    }

    private Long weixinConfigIdOrNull() {
        return configService.listAll().stream()
                .filter(c -> PLATFORM.equals(c.getPlatform()))
                .map(ChannelConfigEntity::getId)
                .findFirst()
                .orElse(-1L);
    }

    private String baseurlFromCreds(ChannelConfigEntity e) {
        try {
            JsonNode creds = objectMapper.readTree(
                    e.getCredentialsJson() == null ? "{}" : e.getCredentialsJson());
            String baseurl = creds.path("baseurl").asText("");
            return baseurl.isBlank() ? null : baseurl;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Persist bot_token + baseurl into the weixin config credentials_json. Requires a pre-created
     * weixin config row (created via the standard channel-config create flow with mode=push); the
     * scan only fills in the credentials.
     */
    private void persistCredentials(String botToken, String baseurl) {
        Optional<ChannelConfigEntity> existing = configService.listAll().stream()
                .filter(c -> PLATFORM.equals(c.getPlatform()))
                .findFirst();
        if (existing.isEmpty()) {
            throw new BindRejectedException(
                    "no weixin channel config — create one (mode=push) before scanning");
        }
        // BLOCKER 1 defense-in-depth: the baseurl comes from the (untrusted, reverse-engineered)
        // iLink response. SSRF-validate before persisting so a spoofed host never reaches config /
        // future requests (which carry the bot_token).
        if (baseurl != null && !baseurl.isBlank()) {
            String error = UrlValidator.validate(baseurl.trim());
            if (error != null) {
                throw new BindRejectedException("invalid baseurl from iLink (rejected): " + error);
            }
        }
        ChannelConfigEntity entity = existing.get();
        try {
            ObjectNode creds;
            String raw = entity.getCredentialsJson();
            if (raw == null || raw.isBlank()) {
                creds = objectMapper.createObjectNode();
            } else {
                JsonNode parsed = objectMapper.readTree(raw);
                creds = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();
            }
            creds.put("bot_token", botToken);
            if (baseurl != null && !baseurl.isBlank()) {
                creds.put("baseurl", baseurl.trim());
            }
            entity.setCredentialsJson(objectMapper.writeValueAsString(creds));
            configService.save(entity);
            log.info("weixin bot bound for configId={} (token persisted, baseurl={})",
                    entity.getId(), baseurl == null ? "<default>" : "<set>");
        } catch (BindRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to persist weixin credentials: " + e.getMessage(), e);
        }
    }
}
