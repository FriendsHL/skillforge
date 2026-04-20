package com.skillforge.server.channel.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelPushConnector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelPushManagerTest {

    @Test
    @DisplayName("stop(callback) should return after timeout when connector stop is slow")
    void stopCallback_shouldReturnAfterTimeout() throws Exception {
        SlowStopConnector slowConnector = new SlowStopConnector("feishu", 200);
        ChannelConfigService configService = mock(ChannelConfigService.class);
        when(configService.listActiveDecryptedConfigs()).thenReturn(List.of(new ChannelConfigDecrypted(
                1L,
                "feishu",
                "",
                "{}",
                "{\"mode\":\"websocket\"}",
                100L)));
        ChannelPushManager manager = new ChannelPushManager(
                List.of(slowConnector),
                configService,
                new ObjectMapper(),
                Duration.ofMillis(30));
        manager.start();

        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        long beginNs = System.nanoTime();
        manager.stop(() -> callbackCalled.set(true));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginNs);

        assertThat(callbackCalled.get()).isTrue();
        assertThat(elapsedMs).isLessThan(2_000);
        assertThat(slowConnector.awaitStopCalled(1, TimeUnit.SECONDS)).isTrue();
        assertThat(slowConnector.stopCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("stop(callback) should skip connectors that were never started")
    void stopCallback_shouldSkipNeverStartedConnectors() {
        SlowStopConnector connector = new SlowStopConnector("feishu", 10);
        ChannelConfigService configService = mock(ChannelConfigService.class);
        when(configService.listActiveDecryptedConfigs()).thenReturn(List.of(new ChannelConfigDecrypted(
                1L,
                "feishu",
                "",
                "{}",
                "{\"mode\":\"webhook\"}",
                100L)));
        ChannelPushManager manager = new ChannelPushManager(
                List.of(connector),
                configService,
                new ObjectMapper(),
                Duration.ofMillis(30));
        manager.start();

        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        manager.stop(() -> callbackCalled.set(true));

        assertThat(callbackCalled.get()).isTrue();
        assertThat(connector.stopCalls()).isZero();
    }

    @Test
    @DisplayName("stop(callback) should invoke callback immediately when no connectors")
    void stopCallback_noConnectors_shouldInvokeCallback() {
        ChannelPushManager manager = new ChannelPushManager(
                List.of(),
                mock(ChannelConfigService.class),
                new ObjectMapper(),
                Duration.ofMillis(30));

        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        manager.stop(() -> callbackCalled.set(true));

        assertThat(callbackCalled.get()).isTrue();
    }

    @Test
    @DisplayName("start should keep manager stopped when config loading fails")
    void start_configLoadFailure_keepsStopped() {
        SlowStopConnector connector = new SlowStopConnector("feishu", 10);
        ChannelConfigService configService = mock(ChannelConfigService.class);
        when(configService.listActiveDecryptedConfigs()).thenThrow(new RuntimeException("db unavailable"));
        ChannelPushManager manager = new ChannelPushManager(
                List.of(connector),
                configService,
                new ObjectMapper(),
                Duration.ofMillis(30));

        manager.start();

        assertThat(manager.isRunning()).isFalse();
        assertThat(connector.startCalls()).isZero();
        verify(configService).listActiveDecryptedConfigs();
    }

    private static final class SlowStopConnector implements ChannelPushConnector {
        private final String platformId;
        private final long stopSleepMs;
        private final AtomicInteger startCalls = new AtomicInteger(0);
        private final AtomicInteger stopCalls = new AtomicInteger(0);
        private final CountDownLatch stopCalled = new CountDownLatch(1);

        private SlowStopConnector(String platformId, long stopSleepMs) {
            this.platformId = platformId;
            this.stopSleepMs = stopSleepMs;
        }

        @Override
        public String platformId() {
            return platformId;
        }

        @Override
        public void start(ChannelConfigDecrypted config) {
            startCalls.incrementAndGet();
        }

        @Override
        public void stop() {
            stopCalls.incrementAndGet();
            stopCalled.countDown();
            try {
                Thread.sleep(stopSleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitStopCalled(long timeout, TimeUnit unit) throws InterruptedException {
            return stopCalled.await(timeout, unit);
        }

        private int stopCalls() {
            return stopCalls.get();
        }

        private int startCalls() {
            return startCalls.get();
        }
    }
}
