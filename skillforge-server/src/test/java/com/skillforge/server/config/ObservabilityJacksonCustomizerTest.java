package com.skillforge.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.config.ObservabilityJacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-1 Plan §10.1 R3-WN6 — verifies the OBS-1 module participates in Spring Boot Jackson
 * auto-config via {@link com.fasterxml.jackson.databind.cfg.MapperBuilder} customizer (rather
 * than publishing its own {@code @Bean ObjectMapper}), so that:
 *
 * <ol>
 *   <li>only the Spring Boot default ObjectMapper is registered (1 bean)</li>
 *   <li>the {@code spring.jackson.deserialization.fail-on-unknown-properties=false}
 *       yaml config is honored end-to-end</li>
 *   <li>{@code Instant} serializes to an ISO-8601 string (footgun #1: WRITE_DATES_AS_TIMESTAMPS=false
 *       + JavaTimeModule registered)</li>
 * </ol>
 *
 * <p>Runs against {@link JacksonAutoConfiguration} (not the full Spring Boot app) — narrow,
 * fast, and avoids spinning up a DataSource.
 */
@DisplayName("ObservabilityJacksonConfig — customizer participates in Jackson auto-config")
class ObservabilityJacksonCustomizerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(ObservabilityJacksonConfig.class);

    @Test
    @DisplayName("only one ObjectMapper bean exists in the context (no shadowing of Spring Boot default)")
    void onlyOneObjectMapperBean() {
        runner.run(ctx -> {
            Map<String, ObjectMapper> beans = ctx.getBeansOfType(ObjectMapper.class);
            assertThat(beans)
                    .as("publishing @Bean ObjectMapper would suppress Spring Boot's default — must be 1")
                    .hasSize(1);
        });
    }

    @Test
    @DisplayName("yaml: spring.jackson.deserialization.fail-on-unknown-properties=false flows through")
    void yamlDisablesFailOnUnknown() {
        runner
                .withPropertyValues("spring.jackson.deserialization.fail-on-unknown-properties=false")
                .run(ctx -> {
                    ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
                    // Should NOT throw — unknown field 'extra' must be ignored.
                    Sample s = mapper.readValue(
                            "{\"name\":\"x\",\"extra\":\"unknown\"}", Sample.class);
                    assertThat(s.getName()).isEqualTo("x");
                });
    }

    @Test
    @DisplayName("Instant serializes to ISO-8601 string (JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS=false)")
    void instantSerializesAsIsoLiteral() {
        runner.run(ctx -> {
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            String json = mapper.writeValueAsString(Instant.parse("2026-04-29T10:00:00Z"));
            assertThat(json)
                    .as("must be quoted ISO literal, not numeric timestamp")
                    .contains("T")
                    .contains("Z")
                    .startsWith("\"")
                    .endsWith("\"");
        });
    }

    /** Simple POJO to test deserialization tolerance. */
    public static final class Sample {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
