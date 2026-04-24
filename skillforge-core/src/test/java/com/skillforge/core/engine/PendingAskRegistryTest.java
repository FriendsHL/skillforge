package com.skillforge.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PendingAskRegistryTest {

    @Test
    @DisplayName("register(askId, sessionId) stores sessionId, hasPendingForSession returns true")
    void hasPendingForSession() {
        PendingAskRegistry reg = new PendingAskRegistry();
        reg.register("ask1", "sidA");
        assertThat(reg.hasPendingForSession("sidA")).isTrue();
        assertThat(reg.hasPendingForSession("sidB")).isFalse();
    }

    @Test
    @DisplayName("hasPendingForSession returns false after await completes")
    void clearedAfterAwait() throws Exception {
        PendingAskRegistry reg = new PendingAskRegistry();
        reg.register("ask1", "sidA");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                answer.set(reg.await("ask1", 5));
            } catch (InterruptedException ignored) {
            } finally {
                done.countDown();
            }
        });
        t.start();
        Thread.sleep(50);
        reg.complete("ask1", "hi");
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(answer.get()).isEqualTo("hi");
        assertThat(reg.hasPendingForSession("sidA")).isFalse();
    }

    @Test
    @DisplayName("legacy register(askId) still works — sessionId null")
    void legacyRegister() {
        PendingAskRegistry reg = new PendingAskRegistry();
        @SuppressWarnings("deprecation")
        var ask = reg.register("ask2");
        assertThat(ask.getSessionId()).isNull();
        assertThat(reg.hasPendingForSession(null)).isFalse(); // null sessionId never matches
    }

    @Test
    @DisplayName("timeout returns null and does not leak a pending record")
    void timeout() throws Exception {
        PendingAskRegistry reg = new PendingAskRegistry();
        reg.register("ask3", "sid");
        assertThat(reg.await("ask3", 1)).isNull();
        assertThat(reg.hasPendingForSession("sid")).isFalse();
    }

    @Test
    @DisplayName("hasPendingForSession ignores null sessionId input")
    void nullInput() {
        PendingAskRegistry reg = new PendingAskRegistry();
        reg.register("ask1", "sidA");
        assertThat(reg.hasPendingForSession(null)).isFalse();
    }
}
