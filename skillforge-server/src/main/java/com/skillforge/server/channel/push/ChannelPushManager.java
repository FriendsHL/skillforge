package com.skillforge.server.channel.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelPushConnector;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle manager for push connectors (e.g. websocket mode).
 */
@Component
public class ChannelPushManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChannelPushManager.class);
    /** Graceful stop timeout to drain in-flight connector ACKs. */
    private static final Duration DEFAULT_STOP_DRAIN_TIMEOUT = Duration.ofSeconds(3);

    private final Map<String, ChannelPushConnector> connectorByPlatform = new ConcurrentHashMap<>();
    private final ChannelConfigService configService;
    private final ObjectMapper objectMapper;
    private final Duration stopDrainTimeout;
    private final Set<String> startedConnectorPlatforms = ConcurrentHashMap.newKeySet();

    private volatile boolean running;

    @Autowired
    public ChannelPushManager(
            List<ChannelPushConnector> connectors,
            ChannelConfigService configService,
            ObjectMapper objectMapper) {
        this(connectors, configService, objectMapper, DEFAULT_STOP_DRAIN_TIMEOUT);
    }

    ChannelPushManager(
            List<ChannelPushConnector> connectors,
            ChannelConfigService configService,
            ObjectMapper objectMapper,
            Duration stopDrainTimeout) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.stopDrainTimeout = stopDrainTimeout;
        connectors.forEach(c -> connectorByPlatform.put(c.platformId(), c));
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            List<ChannelConfigDecrypted> configs = configService.listActiveDecryptedConfigs().stream()
                    .sorted(Comparator.comparing(ChannelConfigDecrypted::id, Comparator.nullsLast(Long::compareTo)))
                    .toList();
            Set<String> seenPlatforms = new HashSet<>();
            startedConnectorPlatforms.clear();
            for (ChannelConfigDecrypted config : configs) {
                if (!"websocket".equals(resolveMode(config.configJson()))) {
                    continue;
                }
                if (!seenPlatforms.add(config.platform())) {
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
                    startedConnectorPlatforms.add(config.platform());
                } catch (Exception e) {
                    log.error("Failed to start push connector for platform {}: {}", config.platform(), e.getMessage(), e);
                }
            }
            running = true;
        } catch (Exception e) {
            startedConnectorPlatforms.clear();
            running = false;
            log.error("Failed to start ChannelPushManager: {}", e.getMessage(), e);
        }
    }

    @Override
    public synchronized void stop() {
        stop(() -> {
            // no-op callback for direct stop invocations
        });
    }

    @Override
    public synchronized void stop(Runnable callback) {
        running = false;
        List<ChannelPushConnector> startedConnectors = startedConnectorPlatforms.stream()
                .map(connectorByPlatform::get)
                .filter(Objects::nonNull)
                .toList();
        if (startedConnectors.isEmpty()) {
            startedConnectorPlatforms.clear();
            callback.run();
            return;
        }

        CountDownLatch drainLatch = new CountDownLatch(startedConnectors.size());
        for (ChannelPushConnector connector : startedConnectors) {
            launchStopTask(connector, drainLatch);
        }
        try {
            boolean allStopped = drainLatch.await(stopDrainTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!allStopped) {
                log.warn("Push connector drain timed out after {} ms", stopDrainTimeout.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting push connector drain");
        } finally {
            startedConnectorPlatforms.clear();
            callback.run();
        }
    }

    private void launchStopTask(ChannelPushConnector connector, CountDownLatch drainLatch) {
        Thread stopThread = new Thread(() -> {
            try {
                stopConnector(connector);
            } finally {
                drainLatch.countDown();
            }
        }, "channel-push-stop-" + connector.platformId());
        stopThread.setDaemon(true);
        stopThread.start();
    }

    private void stopConnector(ChannelPushConnector connector) {
        try {
            connector.stop();
        } catch (Exception e) {
            log.warn("Push connector stop failed [{}]: {}", connector.platformId(), e.getMessage());
        }
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
