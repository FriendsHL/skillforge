package com.skillforge.server.channel.platform.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Feishu websocket connector with reconnect and ping/pong handling.
 */
public class FeishuWsConnector {

    private static final Logger log = LoggerFactory.getLogger(FeishuWsConnector.class);

    private final FeishuClient feishuClient;
    private final FeishuWsEventDispatcher dispatcher;
    private final FeishuWsReconnectPolicy reconnectPolicy;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final Object lifecycleLock = new Object();

    private volatile OkHttpClient wsClient;
    private volatile ScheduledExecutorService scheduler;
    private volatile ChannelConfigDecrypted currentConfig;
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> reconnectFuture;

    public FeishuWsConnector(
            FeishuClient feishuClient,
            FeishuWsEventDispatcher dispatcher,
            FeishuWsReconnectPolicy reconnectPolicy,
            ObjectMapper objectMapper) {
        this.feishuClient = Objects.requireNonNull(feishuClient);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.reconnectPolicy = Objects.requireNonNull(reconnectPolicy);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public void start(ChannelConfigDecrypted config) {
        synchronized (lifecycleLock) {
            this.currentConfig = config;
            running.set(true);
            reconnectAttempt.set(0);
            cancelReconnect();
            ensureInfra();
            closeCurrentSocket();
            connectNow(0);
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            running.set(false);
            cancelReconnect();
            closeCurrentSocket();
            shutdownInfra();
        }
    }

    private void connectNow(int attempt) {
        ChannelConfigDecrypted config;
        OkHttpClient client;
        synchronized (lifecycleLock) {
            if (!running.get() || currentConfig == null || wsClient == null) {
                return;
            }
            closeCurrentSocket();
            config = currentConfig;
            client = wsClient;
        }

        final String wsEndpointUrl;
        try {
            wsEndpointUrl = resolveWsUrl(config);
        } catch (Exception e) {
            log.warn("Feishu WS connect failed to fetch endpoint: {}", e.getMessage());
            scheduleReconnect();
            return;
        }

        try {
            Request request = new Request.Builder()
                    .url(wsEndpointUrl)
                    .build();
            synchronized (lifecycleLock) {
                if (!running.get() || wsClient == null || wsClient != client) {
                    return;
                }
                webSocket = client.newWebSocket(request, new Listener());
            }
            log.info("Feishu WS connect requested. platform={} attempt={}", config.platform(), attempt);
        } catch (Exception e) {
            log.warn("Feishu WS connect failed early: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        synchronized (lifecycleLock) {
            if (!running.get() || scheduler == null || scheduler.isShutdown()) {
                return;
            }
            int attempt = reconnectAttempt.incrementAndGet();
            long delayMs = reconnectPolicy.nextDelayMs(attempt);
            cancelReconnect();
            reconnectFuture = scheduler.schedule(
                    () -> connectNow(attempt),
                    delayMs,
                    TimeUnit.MILLISECONDS);
            log.info("Feishu WS reconnect scheduled in {}ms (attempt={})", delayMs, attempt);
        }
    }

    private void cancelReconnect() {
        ScheduledFuture<?> scheduled = reconnectFuture;
        if (scheduled != null) {
            scheduled.cancel(true);
            reconnectFuture = null;
        }
    }

    private String resolveWsUrl(ChannelConfigDecrypted config) throws Exception {
        String rawConfig = config.configJson() == null ? "{}" : config.configJson();
        JsonNode configNode = objectMapper.readTree(rawConfig);
        String custom = configNode.path("wsUrl").asText("");
        if (!custom.isBlank()) {
            return custom;
        }
        return feishuClient.getWsEndpoint(config);
    }

    private final class Listener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            reconnectAttempt.set(0);
            log.info("Feishu WS connected: {}", response.code());
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (!running.get()) {
                return;
            }
            ChannelConfigDecrypted config = currentConfig;
            if (config == null) {
                return;
            }
            dispatcher.dispatch(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), config)
                    .map(okio.ByteString::of)
                    .ifPresent(webSocket::send);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            if (bytes == null || bytes.size() == 0) {
                return;
            }
            ChannelConfigDecrypted config = currentConfig;
            if (config == null || !running.get()) {
                return;
            }
            dispatcher.dispatch(bytes.toByteArray(), config)
                    .map(okio.ByteString::of)
                    .ifPresent(webSocket::send);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            log.warn("Feishu WS closed: code={} reason={}", code, reason);
            scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            String responseCode = response != null ? String.valueOf(response.code()) : "n/a";
            log.warn("Feishu WS failure: status={} msg={}", responseCode, t.getMessage());
            scheduleReconnect();
        }
    }

    private void ensureInfra() {
        if (wsClient == null) {
            wsClient = new OkHttpClient.Builder()
                    .readTimeout(Duration.ZERO)
                    .pingInterval(Duration.ofSeconds(30))
                    .build();
        }
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new WsThreadFactory());
        }
    }

    private void closeCurrentSocket() {
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            ws.close(1000, "stopped");
        }
    }

    private void shutdownInfra() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
        OkHttpClient client = wsClient;
        wsClient = null;
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private static final class WsThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "feishu-ws-reconnect");
            t.setDaemon(true);
            return t;
        }
    }
}
