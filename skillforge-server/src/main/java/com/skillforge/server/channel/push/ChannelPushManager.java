package com.skillforge.server.channel.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelPushConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle manager for push connectors (e.g. websocket mode).
 */
@Component
public class ChannelPushManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChannelPushManager.class);

    private final Map<String, ChannelPushConnector> connectorByPlatform = new ConcurrentHashMap<>();
    private final ChannelConfigService configService;
    private final ObjectMapper objectMapper;

    private volatile boolean running;

    public ChannelPushManager(
            List<ChannelPushConnector> connectors,
            ChannelConfigService configService,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        connectors.forEach(c -> connectorByPlatform.put(c.platformId(), c));
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        List<ChannelConfigDecrypted> configs = configService.listActiveDecryptedConfigs().stream()
                .sorted(Comparator.comparing(ChannelConfigDecrypted::id, Comparator.nullsLast(Long::compareTo)))
                .toList();
        Set<String> startedPlatforms = new HashSet<>();
        for (ChannelConfigDecrypted config : configs) {
            if (!"websocket".equals(resolveMode(config.configJson()))) {
                continue;
            }
            if (!startedPlatforms.add(config.platform())) {
                log.warn("Skip websocket connector start for duplicate platform config. platform={}, configId={}",
                        config.platform(), config.id());
                continue;
            }
            ChannelPushConnector connector = connectorByPlatform.get(config.platform());
            if (connector == null) {
                continue;
            }
            try {
                connector.start(config);
            } catch (Exception e) {
                log.error("Failed to start push connector for platform {}: {}", config.platform(), e.getMessage(), e);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        for (ChannelPushConnector connector : connectorByPlatform.values()) {
            try {
                connector.stop();
            } catch (Exception e) {
                log.warn("Push connector stop failed [{}]: {}", connector.platformId(), e.getMessage());
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        for (ChannelPushConnector connector : connectorByPlatform.values()) {
            try {
                connector.stop();
            } catch (Exception e) {
                log.warn("Push connector stop failed [{}]: {}", connector.platformId(), e.getMessage());
            }
        }
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE + 100;
    }

    private String resolveMode(String configJson) {
        String raw = configJson == null ? "{}" : configJson;
        try {
            JsonNode node = objectMapper.readTree(raw);
            String mode = node.path("mode").asText("");
            if ("websocket".equalsIgnoreCase(mode)) {
                return "websocket";
            }
        } catch (Exception ignored) {
            // keep webhook mode for invalid json
        }
        return "webhook";
    }
}
