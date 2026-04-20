package com.skillforge.server.channel.web;

import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.router.ChannelSessionRouter;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import com.skillforge.server.repository.ChannelMessageDedupRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unauthenticated webhook entrypoint: POST /api/channels/{platform}/webhook.
 * Security is signature-based (adapter.verifyWebhook), not session-auth.
 * <p>
 * Flow: lookup config → verify → parse → (challenge short-circuit) →
 * dedup → async route.
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookController.class);

    private final ChannelAdapterRegistry registry;
    private final ChannelConfigService configService;
    private final ChannelMessageDedupRepository dedupRepo;
    private final ChannelSessionRouter router;

    public ChannelWebhookController(ChannelAdapterRegistry registry,
                                    ChannelConfigService configService,
                                    ChannelMessageDedupRepository dedupRepo,
                                    ChannelSessionRouter router) {
        this.registry = registry;
        this.configService = configService;
        this.dedupRepo = dedupRepo;
        this.router = router;
    }

    @PostMapping("/{platform}/webhook")
    public ResponseEntity<?> webhook(@PathVariable String platform,
                                     @RequestBody(required = false) byte[] rawBody,
                                     HttpServletRequest request) {
        byte[] body = rawBody != null ? rawBody : new byte[0];

        Optional<ChannelAdapter> adapterOpt = registry.get(platform);
        if (adapterOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown platform"));
        }
        ChannelAdapter adapter = adapterOpt.get();

        Optional<ChannelConfigDecrypted> cfgOpt = configService.getDecryptedConfig(platform);
        if (cfgOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "platform not configured"));
        }
        ChannelConfigDecrypted config = cfgOpt.get();

        WebhookContext ctx = new WebhookContext(headerMap(request), body);
        try {
            adapter.verifyWebhook(ctx, config);
        } catch (WebhookVerificationException e) {
            log.warn("Webhook verification failed [{}]: {}", platform, e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "verification failed"));
        }

        Optional<ChannelMessage> parsed = adapter.parseIncoming(body, config);
        if (parsed.isEmpty()) {
            // URL verification challenge OR bot self-message OR unsupported event
            return adapter.handleVerificationChallenge(body);
        }

        ChannelMessage msg = parsed.get();
        boolean fresh = dedupRepo.tryInsert(msg.platform(), msg.platformMessageId());
        if (!fresh) {
            log.debug("Dedup hit: platform={} msgId={}", msg.platform(), msg.platformMessageId());
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }

        router.routeAsync(msg, config);
        return ResponseEntity.ok(Map.of("status", "accepted"));
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
