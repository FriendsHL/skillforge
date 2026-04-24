package com.skillforge.core.engine.confirm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PendingConfirmationRegistryTest {

    private PendingConfirmation pc(String id, String sid) {
        return new PendingConfirmation(id, sid, "toolUse-" + id,
                "clawhub", "obsidian", "clawhub install obsidian", null, 30);
    }

    @Test
    @DisplayName("register/await/complete happy path (APPROVED)")
    void happy() throws Exception {
        PendingConfirmationRegistry reg = new PendingConfirmationRegistry();
        reg.register(pc("c1", "s1"));

        CountDownLatch awaited = new CountDownLatch(1);
        AtomicReference<Decision> got = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                got.set(reg.await("c1", 5));
            } catch (InterruptedException ignored) {
            } finally {
                awaited.countDown();
            }
        });
        t.start();
        Thread.sleep(50);
        assertThat(reg.complete("c1", Decision.APPROVED, "u1")).isTrue();
        assertThat(awaited.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(got.get()).isEqualTo(Decision.APPROVED);
    }

    @Test
    @DisplayName("await timeout returns TIMEOUT, not null")
    void timeoutReturnsTimeout() throws Exception {
        PendingConfirmationRegistry reg = new PendingConfirmationRegistry();
        reg.register(pc("c2", "s1"));
        Decision d = reg.await("c2", 1);
        assertThat(d).isEqualTo(Decision.TIMEOUT);
    }

    @Test
    @DisplayName("completeAllForSession wakes all latches for that session with DENIED")
    void cancelCascade() throws Exception {
        PendingConfirmationRegistry reg = new PendingConfirmationRegistry();
        reg.register(pc("c1", "sA"));
        reg.register(pc("c2", "sA"));
        reg.register(pc("c3", "sB"));

        CountDownLatch a1 = new CountDownLatch(1);
        CountDownLatch a2 = new CountDownLatch(1);
        var ex = Executors.newFixedThreadPool(2);
        AtomicReference<Decision> r1 = new AtomicReference<>();
        AtomicReference<Decision> r2 = new AtomicReference<>();
        ex.submit(() -> {
            try { r1.set(reg.await("c1", 5)); } catch (InterruptedException ignored) {}
            a1.countDown();
        });
        ex.submit(() -> {
            try { r2.set(reg.await("c2", 5)); } catch (InterruptedException ignored) {}
            a2.countDown();
        });
        Thread.sleep(80);
        int n = reg.completeAllForSession("sA", Decision.DENIED);
        assertThat(n).isEqualTo(2);
        assertThat(a1.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(a2.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(r1.get()).isEqualTo(Decision.DENIED);
        assertThat(r2.get()).isEqualTo(Decision.DENIED);
        // c3 not woken
        assertThat(reg.peek("c3")).isNotNull();
        ex.shutdownNow();
    }

    @Test
    @DisplayName("complete twice: second call returns false")
    void completeTwice() {
        PendingConfirmationRegistry reg = new PendingConfirmationRegistry();
        reg.register(pc("c1", "sA"));
        assertThat(reg.complete("c1", Decision.APPROVED, null)).isTrue();
        assertThat(reg.complete("c1", Decision.DENIED, null)).isFalse();
    }

    @Test
    @DisplayName("unknown confirmationId → await returns null (not throw)")
    void unknownId() throws Exception {
        PendingConfirmationRegistry reg = new PendingConfirmationRegistry();
        assertThat(reg.await("nope", 1)).isNull();
        assertThat(reg.complete("nope", Decision.APPROVED, null)).isFalse();
    }
}
