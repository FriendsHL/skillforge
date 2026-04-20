package com.skillforge.server.channel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.feishu.FeishuClient;
import com.skillforge.server.channel.platform.telegram.TelegramBotClient;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.entity.ChannelConfigEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin REST for configuring channels. Lives under the authenticated /api surface.
 */
@RestController
@RequestMapping("/api/channel-configs")
public class ChannelConfigController {

    private final ChannelConfigService configService;
    private final ChannelAdapterRegistry registry;
    private final ObjectMapper objectMapper;
    private final FeishuClient feishuClient;
    private final TelegramBotClient telegramBotClient;

    public ChannelConfigController(ChannelConfigService configService,
                                   ChannelAdapterRegistry registry,
                                   ObjectMapper objectMapper,
                                   FeishuClient feishuClient,
                                   TelegramBotClient telegramBotClient) {
        this.configService = configService;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.feishuClient = feishuClient;
        this.telegramBotClient = telegramBotClient;
    }

    @GetMapping
    public ResponseEntity<List<ConfigView>> list() {
        List<ConfigView> out = configService.listAll().stream()
                .map(ConfigView::from)
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/platforms")
    public ResponseEntity<List<Map<String, String>>> listPlatforms() {
        List<Map<String, String>> out = registry.registeredPlatforms().stream()
                .map(id -> registry.get(id).orElseThrow())
                .map(a -> Map.of("platform", a.platformId(), "displayName", a.displayName()))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return configService.getById(id)
                .<ResponseEntity<?>>map(e -> ResponseEntity.ok(ConfigView.from(e)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "not found")));
    }

    @GetMapping("/{id}/test")
    public ResponseEntity<?> testConnection(@PathVariable Long id) {
        return configService.getById(id).<ResponseEntity<?>>map(e -> {
            try {
                ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                        e.getId(),
                        e.getPlatform(),
                        e.getWebhookSecret(),
                        e.getCredentialsJson(),
                        e.getConfigJson(),
                        e.getDefaultAgentId());
                String detail = switch (e.getPlatform()) {
                    case "feishu" -> feishuClient.testConnection(config);
                    case "telegram" -> telegramBotClient.testConnection(config);
                    default -> throw new IllegalArgumentException("unsupported platform: " + e.getPlatform());
                };
                return ResponseEntity.ok(new ChannelTestResult(true, "Connection test passed: " + detail));
            } catch (Exception ex) {
                return ResponseEntity.ok(new ChannelTestResult(false, ex.getMessage()));
            }
        }).orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("error", "not found")));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ConfigRequest req) {
        if (req.platform == null || req.platform.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "platform required"));
        }
        if (registry.get(req.platform).isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unknown platform: " + req.platform));
        }
        if (req.defaultAgentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "defaultAgentId required"));
        }
        ChannelConfigEntity e = new ChannelConfigEntity();
        e.setPlatform(req.platform);
        e.setDisplayName(req.displayName);
        e.setActive(req.active != null ? req.active : true);
        e.setWebhookSecret(resolveWebhookSecret(req, req.platform));
        e.setCredentialsJson(resolveCredentialsJson(req));
        e.setConfigJson(req.configJson);
        e.setDefaultAgentId(req.defaultAgentId);
        ChannelConfigEntity saved = configService.save(e);
        return ResponseEntity.ok(ConfigView.from(saved));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(@PathVariable Long id, @RequestBody ConfigRequest req) {
        return configService.getById(id).<ResponseEntity<?>>map(e -> {
            String oldMode = resolveMode(e.getConfigJson());
            if (req.displayName != null) e.setDisplayName(req.displayName);
            if (req.active != null) e.setActive(req.active);
            if (req.webhookSecret != null || req.credentials != null) {
                e.setWebhookSecret(resolveWebhookSecret(req, e.getPlatform()));
            }
            if (req.credentialsJson != null || req.credentials != null) {
                e.setCredentialsJson(resolveCredentialsJson(req));
            }
            if (req.configJson != null) e.setConfigJson(req.configJson);
            if (req.defaultAgentId != null) e.setDefaultAgentId(req.defaultAgentId);
            ChannelConfigEntity saved = configService.save(e);
            String newMode = resolveMode(saved.getConfigJson());
            String warning = oldMode.equals(newMode) ? null : "ws mode change requires server restart";
            return ResponseEntity.ok(ConfigView.from(saved, warning));
        }).orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("error", "not found")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (configService.getById(id).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        configService.deleteById(id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    /** Incoming payload — all fields optional on PATCH. */
    public static class ConfigRequest {
        public String platform;
        public String displayName;
        public Boolean active;
        public String webhookSecret;
        public Map<String, Object> credentials;
        public String credentialsJson;
        public String configJson;
        public Long defaultAgentId;
    }

    /** Redacted view — never returns webhookSecret or credentialsJson plaintext. */
    public record ConfigView(
            Long id, String platform, String displayName, boolean active,
            String configJson, Long defaultAgentId,
            boolean webhookSecretSet, boolean credentialsSet, String warning) {
        public static ConfigView from(ChannelConfigEntity e) {
            return new ConfigView(
                    e.getId(), e.getPlatform(), e.getDisplayName(), e.isActive(),
                    e.getConfigJson(), e.getDefaultAgentId(),
                    e.getWebhookSecret() != null && !e.getWebhookSecret().isBlank(),
                    hasCredentials(e.getCredentialsJson()),
                    null);
        }

        public static ConfigView from(ChannelConfigEntity e, String warning) {
            return new ConfigView(
                    e.getId(), e.getPlatform(), e.getDisplayName(), e.isActive(),
                    e.getConfigJson(), e.getDefaultAgentId(),
                    e.getWebhookSecret() != null && !e.getWebhookSecret().isBlank(),
                    hasCredentials(e.getCredentialsJson()),
                    warning);
        }

        private static boolean hasCredentials(String credentialsJson) {
            if (credentialsJson == null || credentialsJson.isBlank()) {
                return false;
            }
            String normalized = credentialsJson.trim();
            return !"{}".equals(normalized);
        }
    }

    public record ChannelTestResult(boolean ok, String message) {}

    private String resolveMode(String configJson) {
        String raw = configJson == null ? "{}" : configJson;
        try {
            JsonNode node = objectMapper.readTree(raw);
            String mode = node.path("mode").asText("");
            if ("websocket".equalsIgnoreCase(mode)) {
                return "websocket";
            }
        } catch (Exception ignored) {
            // keep webhook mode for invalid JSON
        }
        return "webhook";
    }

    private String resolveCredentialsJson(ConfigRequest req) {
        if (req.credentialsJson != null) {
            return req.credentialsJson;
        }
        if (req.credentials != null) {
            try {
                return objectMapper.writeValueAsString(req.credentials);
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid credentials payload");
            }
        }
        return "{}";
    }

    private String resolveWebhookSecret(ConfigRequest req, String platform) {
        if (req.webhookSecret != null) {
            return req.webhookSecret;
        }
        if (req.credentials == null) {
            return "";
        }
        if ("telegram".equals(platform)) {
            Object value = req.credentials.get("webhook_secret");
            return value != null ? String.valueOf(value) : "";
        }
        if ("feishu".equals(platform)) {
            Object value = req.credentials.get("encrypt_key");
            return value != null ? String.valueOf(value) : "";
        }
        return "";
    }
}
