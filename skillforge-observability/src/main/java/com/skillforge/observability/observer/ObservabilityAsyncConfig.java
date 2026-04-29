package com.skillforge.observability.observer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plan §4.5 — observability 异步执行配置。
 *
 * <p>core/maxPool/queue 全部 bounded；reject 时 drop + counter（不抛 / 不阻塞 provider 主链路）。
 */
@Configuration
public class ObservabilityAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAsyncConfig.class);

    private final AtomicLong droppedCounter = new AtomicLong(0L);

    @Bean(name = "llmObservabilityExecutor")
    public ThreadPoolTaskExecutor llmObservabilityExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("obs-llm-");
        // Drop policy: when queue is full, log warn + increment counter, do NOT throw.
        exec.setRejectedExecutionHandler((Runnable r, ThreadPoolExecutor e) -> {
            long n = droppedCounter.incrementAndGet();
            log.warn("Observability executor dropped task (queueFull, totalDropped={})", n);
        });
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setAwaitTerminationSeconds(2);
        exec.initialize();
        return exec;
    }

    /** Observability metrics counter: total dropped tasks since startup (for /actuator). */
    public long droppedCount() { return droppedCounter.get(); }
}
