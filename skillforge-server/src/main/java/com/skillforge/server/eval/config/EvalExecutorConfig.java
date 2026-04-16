package com.skillforge.server.eval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class EvalExecutorConfig {

    @Bean(name = "evalLoopExecutor", destroyMethod = "shutdown")
    public ExecutorService evalLoopExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("eval-loop-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                2, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
