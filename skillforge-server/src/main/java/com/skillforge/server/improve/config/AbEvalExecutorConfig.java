package com.skillforge.server.improve.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AbEvalExecutorConfig {

    @Bean(name = "abEvalCoordinatorExecutor", destroyMethod = "shutdown")
    public ExecutorService abEvalCoordinatorExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("ab-eval-coord-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "abEvalLoopExecutor", destroyMethod = "shutdown")
    public ExecutorService abEvalLoopExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("ab-eval-loop-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(20),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
