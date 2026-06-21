package com.skillforge.server.acp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ProcessAcpTransport}'s launch-critical, deterministic
 * bits — env sanitization + command resolution. NO subprocess spawned (the real
 * spawn is exercised only by the gated {@link AcpClientLiveIT}).
 */
class ProcessAcpTransportEnvTest {

    @Test
    @DisplayName("sanitizeEnv strips CLAUDECODE + nesting signals (spike-verified launch guard)")
    void sanitizeEnv_stripsNestingSignals() {
        Map<String, String> env = new HashMap<>();
        env.put("CLAUDECODE", "1");
        env.put("CLAUDE_CODE_ENTRYPOINT", "cli");
        env.put("CLAUDE_CODE_SSE_PORT", "1234");
        env.put("PATH", "/usr/bin");

        ProcessAcpTransport.sanitizeEnv(env, Map.of());

        assertThat(env).doesNotContainKeys(
                "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SSE_PORT");
        assertThat(env).containsEntry("PATH", "/usr/bin");
    }

    @Test
    @DisplayName("sanitizeEnv applies extra env vars on top")
    void sanitizeEnv_appliesExtraEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("CLAUDECODE", "1");

        ProcessAcpTransport.sanitizeEnv(env, Map.of("OTEL_RESOURCE_ATTRIBUTES", "sf.session_id=s1"));

        assertThat(env).doesNotContainKey("CLAUDECODE");
        assertThat(env).containsEntry("OTEL_RESOURCE_ATTRIBUTES", "sf.session_id=s1");
    }

    @Test
    @DisplayName("default command uses npx --yes with the renamed adapter package")
    void defaultCommand_usesRenamedPackage() {
        ProcessAcpTransport t = new ProcessAcpTransport("/tmp", Map.of());
        assertThat(t.getCommand())
                .containsExactly("npx", "--yes", "@agentclientprotocol/claude-agent-acp");
        assertThat(ProcessAcpTransport.DEFAULT_ADAPTER_PACKAGE)
                .isEqualTo("@agentclientprotocol/claude-agent-acp");
    }

    @Test
    @DisplayName("forAdapterPackage allows overriding the adapter package")
    void forAdapterPackage_overrides() {
        ProcessAcpTransport t = ProcessAcpTransport.forAdapterPackage(
                "@zed-industries/claude-code-acp", "/tmp", Map.of());
        assertThat(t.getCommand())
                .containsExactly("npx", "--yes", "@zed-industries/claude-code-acp");
    }

    @Test
    @DisplayName("empty command is rejected")
    void emptyCommand_rejected() {
        assertThatThrownBy(() -> new ProcessAcpTransport(List.of(), "/tmp", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("send before start throws (transport not open)")
    void sendBeforeStart_throws() {
        ProcessAcpTransport t = new ProcessAcpTransport("/tmp", Map.of());
        assertThatThrownBy(() -> t.send("{}"))
                .isInstanceOf(AcpException.class)
                .hasMessageContaining("not open");
    }
}
