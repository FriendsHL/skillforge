package com.skillforge.server.hook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated executor for lifecycle hook execution. Separate from {@code chatLoopExecutor}
 * so a slow/blocked hook never starves chat loops.
 *
 * <p>Pool sizing: core 4 / max 8 / queue 100. On saturation we use {@link
 * java.util.concurrent.ThreadPoolExecutor.AbortPolicy} so the rejected hook surfaces as a
 * {@link java.util.concurrent.RejectedExecutionException} that the dispatcher folds into
 * its failurePolicy branch — never falling back to {@code CallerRunsPolicy}, which would
 * execute the hook on the chat-loop thread and break pool isolation.
 */
@Configuration
public class LifecycleHooksExecutorConfig {

    @Bean(name = "hookExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor hookExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "lifecycle-hook-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }
}
