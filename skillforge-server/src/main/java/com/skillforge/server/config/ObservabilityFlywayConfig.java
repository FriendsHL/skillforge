package com.skillforge.server.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Plan §3.4 R3-WN1 — Flyway placeholder customizer for OBS-1 ETL mode.
 *
 * <p>Split from {@link LlmObservabilityConfig} to avoid a bean cycle:
 * Flyway depends on the {@link FlywayConfigurationCustomizer} bean, which forces Spring
 * to instantiate the declaring {@code @Configuration}. {@code LlmObservabilityConfig}
 * has a constructor that pulls in {@code LlmCallObserverRegistry}, which transitively
 * needs {@code EntityManagerFactory}, which needs Flyway — a cycle.
 *
 * <p>This class has no constructor dependencies, so Flyway can resolve the customizer
 * eagerly without instantiating any observer / JPA infrastructure.
 */
@Configuration
public class ObservabilityFlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer obsFlywayCustomizer(
            @Value("${skillforge.observability.etl.legacy.mode:off}") String etlMode,
            @Value("${skillforge.observability.etl.trace_span.mode:off}") String etlTraceSpanMode) {
        return (FluentConfiguration config) -> {
            // Plan §3.4 R3-WN1 / OBS-2 §M2: placeholders control hash diff so mode flips
            // re-run the corresponding R__ migration.
            //   * etl_mode            → R__migrate_legacy_llm_call  (OBS-1)
            //   * etl_trace_span_mode → R__migrate_legacy_trace_span (OBS-2 §M2)
            Map<String, String> placeholders = new HashMap<>(config.getPlaceholders());
            placeholders.put("etl_mode", etlMode);
            placeholders.put("etl_trace_span_mode", etlTraceSpanMode);
            config.placeholders(placeholders);
        };
    }
}
