package com.skillforge.server.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTaskResponseJsonTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void snapshotWireShapeIncludesTaskMetadata() {
        var task = new SessionTaskResponse(
                "brief-1", "Goal", "Review goal", "Reviewing", "completed", null,
                false, List.of(), List.of(), Map.of("kind", "goal_brief", "schemaVersion", 1),
                Instant.parse("2026-08-26T00:00:00Z"), Instant.parse("2026-08-26T00:00:01Z"), 0);
        var snapshot = new SessionTaskSnapshotResponse(
                "session-1", Map.of("total", 0L), List.of(task), Instant.parse("2026-08-26T00:00:02Z"));

        runner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(snapshot));

            assertThat(json.at("/tasks/0/metadata/kind").asText()).isEqualTo("goal_brief");
            assertThat(json.at("/tasks/0/metadata/schemaVersion").asInt()).isEqualTo(1);
            assertThat(json.at("/tasks/0/createdAt").asText()).isEqualTo("2026-08-26T00:00:00Z");
        });
    }
}
