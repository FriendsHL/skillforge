package com.skillforge.server.engine;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.LoopContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class MessageQueueTest {

    @Test
    void testEnqueueAndDrain() {
        LoopContext ctx = new LoopContext();
        ctx.enqueueUserMessage("hello");
        ctx.enqueueUserMessage("world");

        List<String> drained = ctx.drainPendingUserMessages();

        assertThat(drained).containsExactly("hello", "world");
        // Queue should be empty after drain
        assertThat(ctx.drainPendingUserMessages()).isEmpty();
    }

    @Test
    void testDrainEmptyReturnsEmptyList() {
        LoopContext ctx = new LoopContext();
        List<String> drained = ctx.drainPendingUserMessages();

        assertThat(drained).isNotNull().isEmpty();
    }

    @Test
    void testHasPendingUserMessages() {
        LoopContext ctx = new LoopContext();
        assertThat(ctx.hasPendingUserMessages()).isFalse();

        ctx.enqueueUserMessage("test");
        assertThat(ctx.hasPendingUserMessages()).isTrue();

        ctx.drainPendingUserMessages();
        assertThat(ctx.hasPendingUserMessages()).isFalse();
    }

    @Test
    void testEnqueueNullAndBlankIgnored() {
        LoopContext ctx = new LoopContext();
        ctx.enqueueUserMessage(null);
        ctx.enqueueUserMessage("");
        ctx.enqueueUserMessage("   ");

        assertThat(ctx.hasPendingUserMessages()).isFalse();
        assertThat(ctx.drainPendingUserMessages()).isEmpty();
    }

    @Test
    void testCancellationRegistryGetContext() {
        CancellationRegistry reg = new CancellationRegistry();

        // Not registered → null
        assertThat(reg.getContext("s1")).isNull();

        // Register → returns context
        LoopContext ctx = new LoopContext();
        reg.register("s1", ctx);
        assertThat(reg.getContext("s1")).isSameAs(ctx);

        // Unregister → null again
        reg.unregister("s1");
        assertThat(reg.getContext("s1")).isNull();

        // Null sessionId → null
        assertThat(reg.getContext(null)).isNull();
    }

    @Test
    void testConcurrentEnqueueAndDrain() throws InterruptedException {
        LoopContext ctx = new LoopContext();
        int numMessages = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        List<String> allDrained = Collections.synchronizedList(new ArrayList<>());

        // Producer thread: enqueue messages
        Thread producer = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < numMessages; i++) {
                    ctx.enqueueUserMessage("msg-" + i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        // Consumer thread: drain messages
        Thread consumer = new Thread(() -> {
            try {
                startLatch.await();
                // Keep draining until we have all messages
                while (allDrained.size() < numMessages) {
                    List<String> batch = ctx.drainPendingUserMessages();
                    allDrained.addAll(batch);
                    if (batch.isEmpty()) {
                        Thread.yield();
                    }
                }
            } catch (Exception e) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });

        producer.start();
        consumer.start();
        startLatch.countDown();
        doneLatch.await();

        // All messages should have been drained exactly once
        assertThat(allDrained).hasSize(numMessages);
        // No duplicates
        assertThat(allDrained).doesNotHaveDuplicates();
    }
}
