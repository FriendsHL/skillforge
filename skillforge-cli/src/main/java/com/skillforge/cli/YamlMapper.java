package com.skillforge.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/**
 * Shared Jackson YAML/JSON mapper instances.
 *
 * Both mappers register JavaTime via {@code findAndRegisterModules()} and
 * disable {@code WRITE_DATES_AS_TIMESTAMPS} so that {@code LocalDateTime} /
 * {@code Instant} fields serialize as ISO-8601 strings rather than numeric
 * arrays like {@code [2026,4,9,18,...]}. This is the same bug that bit the
 * server's {@code UserWebSocketHandler} earlier — fix it here once so every
 * CLI command that prints session metadata renders timestamps correctly.
 */
public final class YamlMapper {

    private static final ObjectMapper YAML;
    private static final ObjectMapper JSON;

    static {
        YAMLFactory f = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        YAML = new ObjectMapper(f)
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        JSON = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    private YamlMapper() {}

    public static ObjectMapper yaml() {
        return YAML;
    }

    public static ObjectMapper json() {
        return JSON;
    }
}
