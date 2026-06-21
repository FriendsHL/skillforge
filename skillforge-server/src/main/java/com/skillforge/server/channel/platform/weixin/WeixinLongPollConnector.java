package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.platform.feishu.FeishuWsReconnectPolicy;
import com.skillforge.server.channel.router.ChannelSessionRouter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.repository.ChannelMessageDedupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Weixin (iLink) long-poll inbound loop. Single worker thread repeatedly calls
 * {@code getupdates(cursor)}; each user message is parsed → deduped → routed. The cursor is
 * advanced in memory and persisted after each successful batch (INV-1). On error it backs off
 * (reusing {@link FeishuWsReconnectPolicy}) and retries; {@link #stop()} ends the loop and joins
 * the thread cleanly so no thread leaks (INV-3).
 *
 * <p>Reverse-engineered protocol — the actual end-to-end flow needs a live WeChat scan to verify
 * (cannot be exercised in CI).
 */
public class WeixinLongPollConnector {

    private static final Logger log = LoggerFactory.getLogger(WeixinLongPollConnector.class);
    /** How long to wait for the worker thread to finish on stop(). */
    private static final long JOIN_TIMEOUT_MS = 5_000L;

    private final WeixinIlinkClient client;
    private final WeixinMessageParser parser;
    private final WeixinCursorStore cursorStore;
    private final ChannelMessageDedupRepository dedupRepository;
    private final ChannelSessionRouter router;
    private final ObjectMapper objectMapper;
    private final FeishuWsReconnectPolicy backoffPolicy;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger backoffAttempt = new AtomicInteger(0);
    private final Object lifecycleLock = new Object();

    private volatile Thread worker;
    private volatile ChannelConfigDecrypted currentConfig;
    private volatile String cursor = "";
    private final WeixinIlinkClient.CancelHandle cancelHandle = new WeixinIlinkClient.CancelHandle();

    public WeixinLongPollConnector(
            WeixinIlinkClient client,
            WeixinMessageParser parser,
            WeixinCursorStore cursorStore,
            ChannelMessageDedupRepository dedupRepository,
            ChannelSessionRouter router,
            ObjectMapper objectMapper,
            FeishuWsReconnectPolicy backoffPolicy) {
        this.client = Objects.requireNonNull(client);
        this.parser = Objects.requireNonNull(parser);
        this.cursorStore = Objects.requireNonNull(cursorStore);
        this.dedupRepository = Objects.requireNonNull(dedupRepository);
        this.router = Objects.requireNonNull(router);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.backoffPolicy = Objects.requireNonNull(backoffPolicy);
    }

    public void start(ChannelConfigDecrypted config) {
        synchronized (lifecycleLock) {
            stopInternal();
            this.currentConfig = config;
            this.cursor = cursorStore.readCursor(config.configJson());
            this.backoffAttempt.set(0);
            running.set(true);
            Thread t = new Thread(this::runLoop, "weixin-longpoll-" + config.id());
            t.setDaemon(true);
            this.worker = t;
            t.start();
            log.info("weixin long-poll started. configId={} resumeCursor={}",
                    config.id(), cursor.isBlank() ? "<empty>" : "<persisted>");
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            stopInternal();
        }
    }

    private void stopInternal() {
        running.set(false);
        Thread t = worker;
        worker = null;
        if (t != null) {
            // Cancel the in-flight long-poll first: interrupt() does not unblock OkHttp's blocking
            // socket read, so the worker would otherwise sit until the ~35s server timeout.
            cancelHandle.cancel();
            t.interrupt();
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        while (running.get()) {
            ChannelConfigDecrypted config = currentConfig;
            if (config == null) {
                return;
            }
            String botToken = resolveBotToken(config);
            String baseurl = resolveBaseurl(config);
            if (botToken == null || botToken.isBlank()) {
                log.warn("weixin long-poll: no bot_token bound for configId={}, backing off", config.id());
                if (!backoffSleep()) return;
                continue;
            }

            try {
                WeixinIlinkClient.GetUpdatesResult result =
                        client.getUpdates(cursor, botToken, baseurl, cancelHandle);
                backoffAttempt.set(0);

                processMsgs(result.msgs(), config);

                // Advance + persist cursor only after the batch has been dispatched.
                this.cursor = result.cursor();
                cursorStore.writeCursor(config.id(), this.cursor);
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.warn("weixin getupdates failed (configId={}): {}", config.id(), e.getMessage());
                if (!backoffSleep()) {
                    return;
                }
            }
        }
    }

    private void processMsgs(JsonNode msgs, ChannelConfigDecrypted config) {
        if (msgs == null || !msgs.isArray()) {
            return;
        }
        for (JsonNode msgNode : msgs) {
            if (!running.get()) {
                return;
            }
            try {
                Optional<ChannelMessage> parsed = parser.parse(msgNode);
                if (parsed.isEmpty()) {
                    continue;
                }
                ChannelMessage message = parsed.get();
                // Slice 1 is text-only. UNSUPPORTED (image/voice/file/video) has null text;
                // routing it would run an agent loop with empty input (burns tokens/session).
                // Media support arrives with the deferred file slice.
                if (message.type() == ChannelMessage.MessageType.UNSUPPORTED) {
                    log.debug("weixin inbound unsupported media type, skipping routing");
                    continue;
                }
                boolean fresh = dedupRepository.tryInsert(message.platform(), message.platformMessageId());
                if (fresh) {
                    router.routeAsync(message, config);
                } else {
                    log.debug("weixin dedup hit: msgId={}", message.platformMessageId());
                }
            } catch (Exception e) {
                // Log class + encoded id so a silent drop (e.g. id-overflow guard, DB INSERT
                // failure) is diagnosable rather than an opaque message.
                log.warn("weixin failed to process inbound message [{}]: {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** @return false if the loop was stopped while sleeping. */
    private boolean backoffSleep() {
        int attempt = backoffAttempt.incrementAndGet();
        long delayMs = backoffPolicy.nextDelayMs(attempt);
        try {
            Thread.sleep(delayMs);
            return running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String resolveBotToken(ChannelConfigDecrypted config) {
        try {
            JsonNode creds = objectMapper.readTree(
                    config.credentialsJson() == null ? "{}" : config.credentialsJson());
            return creds.path("bot_token").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveBaseurl(ChannelConfigDecrypted config) {
        try {
            JsonNode creds = objectMapper.readTree(
                    config.credentialsJson() == null ? "{}" : config.credentialsJson());
            return creds.path("baseurl").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
