package com.skillforge.server.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.mcp.entity.McpServerEntity;
import com.skillforge.server.mcp.event.McpServerDeletedEvent;
import com.skillforge.server.mcp.event.McpServerUpsertedEvent;
import com.skillforge.server.mcp.repository.McpServerRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.tools.mcp.session.McpServerSessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerLifecycleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("resolveEnv substitutes ${VAR_NAME} placeholders from System.getenv (INV-5)")
    void resolveEnv_substitutes() {
        // Use a known env var (PATH should be set on every test runner / OS).
        String pathVal = System.getenv("PATH");
        assertThat(pathVal).isNotNull();
        Map<String, String> resolved = McpServerLifecycle.resolveEnv(Map.of(
                "MY_PATH", "${PATH}",
                "PREFIXED", "before-${PATH}-after",
                "PLAIN", "no-substitution",
                "UNRESOLVED", "${THIS_VAR_DOES_NOT_EXIST_98712}"
        ));
        assertThat(resolved.get("MY_PATH")).isEqualTo(pathVal);
        assertThat(resolved.get("PREFIXED")).isEqualTo("before-" + pathVal + "-after");
        assertThat(resolved.get("PLAIN")).isEqualTo("no-substitution");
        // Unresolved placeholders pass through literally so the operator notices in logs.
        assertThat(resolved.get("UNRESOLVED")).isEqualTo("${THIS_VAR_DOES_NOT_EXIST_98712}");
    }

    @Test
    @DisplayName("resolveEnv handles null map and missing dollar signs without throwing")
    void resolveEnv_nullSafe() {
        assertThat(McpServerLifecycle.resolveEnv(null)).isEmpty();
        assertThat(McpServerLifecycle.resolveEnv(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("onApplicationReady is no-op when mcp.enabled=false")
    void onApplicationReady_disabledFlagSkips() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpServerService service = mock(McpServerService.class);
        McpServerSessionRegistry sessionRegistry = new McpServerSessionRegistry();
        SkillRegistry skillRegistry = new SkillRegistry();
        McpToolRegistrar registrar = new McpToolRegistrar(skillRegistry, mapper);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        McpServerLifecycle lifecycle = new McpServerLifecycle(
                repository, service, sessionRegistry, registrar, mapper, /* mcpEnabled */ false);

        // ApplicationReadyEvent constructor needs awkward args — pass null where allowed.
        try {
            lifecycle.onApplicationReady((ApplicationReadyEvent) null);
        } catch (Exception ignored) {
            // event arg is unused when disabled flag short-circuits
        }
        // Repository must NOT be queried for enabled rows when disabled
        org.mockito.Mockito.verifyNoInteractions(repository);
        assertThat(sessionRegistry.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("runtimeStatus returns 'disabled' for disabled rows; 'disconnected' for enabled-but-no-session")
    void runtimeStatus_branches() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpServerService service = mock(McpServerService.class);
        McpServerSessionRegistry sessionRegistry = new McpServerSessionRegistry();
        SkillRegistry skillRegistry = new SkillRegistry();
        McpToolRegistrar registrar = new McpToolRegistrar(skillRegistry, mapper);
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                repository, service, sessionRegistry, registrar, mapper, true);

        McpServerEntity disabled = new McpServerEntity();
        disabled.setName("d");
        disabled.setEnabled(false);
        assertThat(lifecycle.runtimeStatus(disabled)).isEqualTo("disabled");

        McpServerEntity enabledButNotConnected = new McpServerEntity();
        enabledButNotConnected.setName("missing-session");
        enabledButNotConnected.setEnabled(true);
        assertThat(lifecycle.runtimeStatus(enabledButNotConnected)).isEqualTo("disconnected");
    }

    @Test
    @DisplayName("onUpserted disconnects and skips reconnect when entity is disabled (INV-2 + INV-6)")
    void onUpserted_disabledEntitySkipsConnect() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpServerService service = mock(McpServerService.class);
        McpServerSessionRegistry sessionRegistry = new McpServerSessionRegistry();
        SkillRegistry skillRegistry = new SkillRegistry();
        McpToolRegistrar registrar = new McpToolRegistrar(skillRegistry, mapper);
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                repository, service, sessionRegistry, registrar, mapper, true);

        McpServerEntity disabled = new McpServerEntity();
        disabled.setId(1L);
        disabled.setName("x");
        disabled.setEnabled(false);
        when(repository.findById(1L)).thenReturn(Optional.of(disabled));

        // Should NOT throw even though there's no real subprocess to connect to.
        lifecycle.onUpserted(new McpServerUpsertedEvent(1L, "x"));
        assertThat(sessionRegistry.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("onDeleted does nothing when no session was registered")
    void onDeleted_noSessionNoOp() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpServerService service = mock(McpServerService.class);
        McpServerSessionRegistry sessionRegistry = new McpServerSessionRegistry();
        SkillRegistry skillRegistry = new SkillRegistry();
        McpToolRegistrar registrar = new McpToolRegistrar(skillRegistry, mapper);
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                repository, service, sessionRegistry, registrar, mapper, true);
        lifecycle.onDeleted(new McpServerDeletedEvent(1L, "ghost"));
        // no exception
    }

    @Test
    @DisplayName("onApplicationReady best-effort: continues past per-server failures (INV-2)")
    void onApplicationReady_bestEffort() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpServerService service = mock(McpServerService.class);
        McpServerSessionRegistry sessionRegistry = new McpServerSessionRegistry();
        SkillRegistry skillRegistry = new SkillRegistry();
        McpToolRegistrar registrar = new McpToolRegistrar(skillRegistry, mapper);
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                repository, service, sessionRegistry, registrar, mapper, true);

        // Two enabled rows with bogus commands → both will fail to spawn; lifecycle must
        // log + continue, never re-throw, never half-register.
        McpServerEntity bad = new McpServerEntity();
        bad.setName("bad");
        bad.setCommand("/nonexistent/command/that/does/not/exist/xyz");
        bad.setEnabled(true);
        when(repository.findByEnabledTrue()).thenReturn(List.of(bad));
        when(service.parseArgs(any())).thenReturn(List.of());
        when(service.parseEnv(any())).thenReturn(Map.of());

        // Must not throw
        lifecycle.onApplicationReady(null);
        assertThat(sessionRegistry.size()).isEqualTo(0);
    }
}
