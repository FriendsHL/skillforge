package com.skillforge.server.service;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.CollabRunEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChatService#resolveAllowedToolNames(List, String)} — the
 * tool-allowlist resolver that auto-grants Team communication and shared Task
 * tools to collab-run members regardless of the agent's static allowlist.
 */
class ChatServiceTeamCommsGrantTest {

    @Test
    @DisplayName("no allowlist (empty tool_ids) → null, all tools allowed (unchanged)")
    void emptyToolIds_returnsNull() {
        assertThat(ChatService.resolveAllowedToolNames(List.of(), "collab-1")).isNull();
        assertThat(ChatService.resolveAllowedToolNames(null, "collab-1")).isNull();
    }

    @Test
    @DisplayName("allowlist + collab run → Team communication and Task tools auto-granted")
    void allowlistInCollab_grantsTeamComms() {
        Set<String> allowed = ChatService.resolveAllowedToolNames(List.of("Bash", "FileRead"), "collab-1");
        assertThat(allowed).containsExactlyInAnyOrder("Bash", "FileRead", "TeamSend", "TeamList",
                "TaskCreate", "TaskUpdate", "TaskGet", "TaskList");
    }

    @Test
    @DisplayName("allowlist but NOT in a collab run → no team-comms grant (kept as-is)")
    void allowlistOutsideCollab_noGrant() {
        Set<String> allowed = ChatService.resolveAllowedToolNames(List.of("Bash", "FileRead"), null);
        assertThat(allowed).containsExactlyInAnyOrder("Bash", "FileRead");
        assertThat(allowed).doesNotContain("TeamSend", "TeamList", "TaskCreate", "TaskUpdate", "TaskGet", "TaskList");
    }

    @Test
    @DisplayName("allowlist already containing the team tools → idempotent (no duplicates)")
    void allowlistAlreadyHasTeamTools_idempotent() {
        Set<String> allowed = ChatService.resolveAllowedToolNames(
                List.of("Bash", "TeamSend"), "collab-1");
        assertThat(allowed).containsExactlyInAnyOrder("Bash", "TeamSend", "TeamList",
                "TaskCreate", "TaskUpdate", "TaskGet", "TaskList");
    }

    @Test
    void workerGetsTeamTaskProtocolWithoutLeaderProtocol() {
        AgentDefinition definition = new AgentDefinition();
        definition.setSystemPrompt("base");
        CollabRunEntity run = new CollabRunEntity();
        run.setLeaderSessionId("leader");

        ChatService.appendTeamTaskPrompt(definition, run, "worker");

        assertThat(definition.getSystemPrompt())
                .startsWith("base")
                .contains("## Team Task Protocol", "Only the active owner completes a Task")
                .doesNotContain("## Team Leader Protocol");
    }

    @Test
    void leaderGetsTaskAndCoordinationProtocols() {
        AgentDefinition definition = new AgentDefinition();
        CollabRunEntity run = new CollabRunEntity();
        run.setLeaderSessionId("leader");

        ChatService.appendTeamTaskPrompt(definition, run, "leader");

        assertThat(definition.getSystemPrompt())
                .contains("## Team Task Protocol", "owner or Team leader may release")
                .contains("## Team Leader Protocol", "create a persistent Task");
    }
}
