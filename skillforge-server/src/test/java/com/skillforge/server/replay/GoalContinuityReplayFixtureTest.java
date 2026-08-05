package com.skillforge.server.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GoalContinuityReplayFixtureTest {

    private static final String FIXTURE =
            "replay/session-7f623dc6-goal-drift-sanitized.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesTheObservedGoalDriftSequenceWithoutSensitivePayloads() throws Exception {
        String raw;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(input).as("fixture %s", FIXTURE).isNotNull();
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(raw)
                .doesNotContain("/Users/", "http://", "https://", "<html", "base64", "api_key");

        JsonNode root = objectMapper.readTree(raw);
        assertThat(root.path("sourceSessionId").asText())
                .isEqualTo("7f623dc6-3610-46c2-9b95-6f3e0e1f4764");
        assertThat(root.path("observedToolTotals").path("PublishInteractiveArtifact").path("calls").asInt())
                .isEqualTo(24);
        assertThat(root.path("observedToolTotals").path("PublishInteractiveArtifact").path("failed").asInt())
                .isEqualTo(16);

        Set<String> eventTypes = new HashSet<>();
        Set<String> failureCodes = new HashSet<>();
        for (JsonNode event : root.path("events")) {
            eventTypes.add(event.path("type").asText());
            if (event.hasNonNull("errorCode")) failureCodes.add(event.path("errorCode").asText());
        }
        assertThat(eventTypes).contains(
                "user_scope_change",
                "user_acceptance_correction",
                "user_rejection",
                "user_priority",
                "user_continue",
                "tool_failure",
                "tool_success",
                "compact_reload",
                "service_restart_reload",
                "historical_tool_message");
        assertThat(failureCodes).contains(
                "ARTIFACT_WORKSPACE_MISMATCH",
                "ARTIFACT_FORBIDDEN_CAPABILITY");

        Set<String> requiredAssertions = new HashSet<>();
        root.path("requiredAssertions").forEach(node -> requiredAssertions.add(node.asText()));
        assertThat(requiredAssertions).containsExactlyInAnyOrder(
                "ordinary_followup_does_not_mutate_tasks",
                "acceptance_correction_updates_existing_task",
                "independent_scope_creates_pending_task",
                "explicit_priority_switches_in_progress_task",
                "user_rejection_reopens_completed_task",
                "tool_failure_never_becomes_business_goal",
                "custom_artifact_can_recover_without_goal_drift",
                "compact_and_restart_reload_open_tasks",
                "historical_todowrite_remains_renderable",
                "final_response_follows_latest_user_goal");
    }
}
