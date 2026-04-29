package com.skillforge.server.config;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.observer.LlmCallObserverRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

/**
 * Plan §10.1 + §6.2 R3-WN3 — server-side wiring for OBS-1.
 *
 * <ul>
 *   <li>Inject {@link LlmCallObserverRegistry} into {@link LlmProviderFactory} so all
 *       providers (live + future) get observer hooks.</li>
 *   <li>Provide {@code blobReadSemaphore} for {@code LlmSpanController.getBlob}.</li>
 * </ul>
 *
 * <p>Flyway placeholder customization lives in {@link ObservabilityFlywayConfig} to
 * avoid a bean cycle through {@code EntityManagerFactory → Flyway}.
 */
@Configuration
public class LlmObservabilityConfig {

    public LlmObservabilityConfig(LlmProviderFactory llmProviderFactory,
                                  LlmCallObserverRegistry observerRegistry) {
        // Setter injection so providers cached at startup get registry on construction.
        llmProviderFactory.setObserverRegistry(observerRegistry);
    }

    @Bean(name = "blobReadSemaphore")
    public Semaphore blobReadSemaphore(
            @Value("${skillforge.observability.blob.read-concurrency:20}") int permits) {
        return new Semaphore(Math.max(1, permits));
    }
}
