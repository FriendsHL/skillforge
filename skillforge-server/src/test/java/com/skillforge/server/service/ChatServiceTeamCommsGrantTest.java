package com.skillforge.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChatService#resolveAllowedToolNames(List, String)} — the
 * tool-allowlist resolver that auto-grants TeamSend + TeamList to collab-run
 * members so subagents can always message + discover each other regardless of the
 * agent's {@code tool_ids} allowlist.
 */
class ChatServiceTeamCommsGrantTest {

    @Test
    @DisplayName("no allowlist (empty tool_ids) → null, all tools allowed (unchanged)")
    void emptyToolIds_returnsNull() {
        assertThat(ChatService.resolveAllowedToolNames(List.of(), "collab-1")).isNull();
        assertThat(ChatService.resolveAllowedToolNames(null, "collab-1")).isNull();
    }

    @Test
    @DisplayName("allowlist + collab run → TeamSend + TeamList auto-granted on top")
    void allowlistInCollab_grantsTeamComms() {
        Set<String> allowed = ChatService.resolveAllowedToolNames(List.of("Bash", "FileRead"), "collab-1");
        assertThat(allowed).containsExactlyInAnyOrder("Bash", "FileRead", "TeamSend", "TeamList");
    }

    @Test
    @DisplayName("allowlist but NOT in a collab run → no team-comms grant (kept as-is)")
    void allowlistOutsideCollab_noGrant() {
        Set<String> allowed = ChatService.resolveAllowedToolNames(List.of("Bash", "FileRead"), null);
        assertThat(allowed).containsExactlyInAnyOrder("Bash", "FileRead");
        assertThat(allowed).doesNotContain("TeamSend", "TeamList");
    }

    @Test
    @DisplayName("allowlist already containing the team tools → idempotent (no duplicates)")
    void allowlistAlreadyHasTeamTools_idempotent() {
        Set<String> allowed = ChatService.resolveAllowedToolNames(
                List.of("Bash", "TeamSend"), "collab-1");
        assertThat(allowed).containsExactlyInAnyOrder("Bash", "TeamSend", "TeamList");
    }
}
