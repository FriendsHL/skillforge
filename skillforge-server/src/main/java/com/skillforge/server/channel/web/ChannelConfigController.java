package com.skillforge.server.channel.web;

import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
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

    public ChannelConfigController(ChannelConfigService configService,
                                   ChannelAdapterRegistry registry) {
        this.configService = configService;
        this.registry = registry;
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
        e.setWebhookSecret(req.webhookSecret == null ? "" : req.webhookSecret);
        e.setCredentialsJson(req.credentialsJson == null ? "{}" : req.credentialsJson);
        e.setConfigJson(req.configJson);
        e.setDefaultAgentId(req.defaultAgentId);
        ChannelConfigEntity saved = configService.save(e);
        return ResponseEntity.ok(ConfigView.from(saved));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(@PathVariable Long id, @RequestBody ConfigRequest req) {
        return configService.getById(id).<ResponseEntity<?>>map(e -> {
            if (req.displayName != null) e.setDisplayName(req.displayName);
            if (req.active != null) e.setActive(req.active);
            if (req.webhookSecret != null) e.setWebhookSecret(req.webhookSecret);
            if (req.credentialsJson != null) e.setCredentialsJson(req.credentialsJson);
            if (req.configJson != null) e.setConfigJson(req.configJson);
            if (req.defaultAgentId != null) e.setDefaultAgentId(req.defaultAgentId);
            return ResponseEntity.ok(ConfigView.from(configService.save(e)));
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
        public String credentialsJson;
        public String configJson;
        public Long defaultAgentId;
    }

    /** Redacted view — never returns webhookSecret or credentialsJson plaintext. */
    public record ConfigView(
            Long id, String platform, String displayName, boolean active,
            String configJson, Long defaultAgentId,
            boolean webhookSecretSet, boolean credentialsSet) {
        public static ConfigView from(ChannelConfigEntity e) {
            return new ConfigView(
                    e.getId(), e.getPlatform(), e.getDisplayName(), e.isActive(),
                    e.getConfigJson(), e.getDefaultAgentId(),
                    e.getWebhookSecret() != null && !e.getWebhookSecret().isBlank(),
                    e.getCredentialsJson() != null && !e.getCredentialsJson().isBlank());
        }
    }
}
