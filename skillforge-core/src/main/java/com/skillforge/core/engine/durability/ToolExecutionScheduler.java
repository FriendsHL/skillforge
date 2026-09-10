package com.skillforge.core.engine.durability;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Injectable scheduling seam proving that durable barriers precede future creation. */
public interface ToolExecutionScheduler {

    <T> CompletableFuture<T> submit(Supplier<T> task);

    static ToolExecutionScheduler commonPool() {
        return CommonPoolToolExecutionScheduler.INSTANCE;
    }

    enum CommonPoolToolExecutionScheduler implements ToolExecutionScheduler {
        INSTANCE;

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            return CompletableFuture.supplyAsync(Objects.requireNonNull(task, "task"));
        }
    }
}
