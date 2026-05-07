package com.skillforge.server.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.mcp.dto.McpServerRequest;
import com.skillforge.server.mcp.entity.McpServerEntity;
import com.skillforge.server.mcp.event.McpServerDeletedEvent;
import com.skillforge.server.mcp.event.McpServerUpsertedEvent;
import com.skillforge.server.mcp.exception.McpServerInUseException;
import com.skillforge.server.mcp.exception.McpServerNotFoundException;
import com.skillforge.server.mcp.repository.McpServerRepository;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerServiceTest {

    @Mock
    McpServerRepository repository;

    @Mock
    AgentRepository agentRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    private McpServerService service;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        service = new McpServerService(repository, agentRepository, eventPublisher, objectMapper);
    }

    private McpServerEntity row(Long id, String name) {
        McpServerEntity e = new McpServerEntity();
        e.setId(id);
        e.setName(name);
        e.setCommand("npx");
        e.setArgs("[\"-y\",\"server-time\"]");
        e.setEnv("{}");
        e.setEnabled(true);
        return e;
    }

    @Test
    @DisplayName("create rejects names that don't match [a-z0-9_]+ length<=32 (INV-3)")
    void create_rejectsBadName() {
        for (String bad : List.of("UPPER", "with-dash", "has space", "has.dot", "")) {
            McpServerRequest req = new McpServerRequest();
            req.setName(bad);
            req.setCommand("npx");
            assertThatThrownBy(() -> service.create(1L, req))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("create persists, publishes McpServerUpsertedEvent")
    void create_publishesUpsertedEvent() {
        McpServerRequest req = new McpServerRequest();
        req.setName("time");
        req.setCommand("npx");
        req.setArgs(List.of("-y", "server-time"));
        req.setEnv(Map.of("TOKEN", "${TOKEN_VAR}"));
        when(repository.existsByName("time")).thenReturn(false);
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> {
            McpServerEntity e = inv.getArgument(0);
            e.setId(7L);
            return e;
        });

        McpServerEntity created = service.create(1L, req);
        assertThat(created.getId()).isEqualTo(7L);
        assertThat(created.getArgs()).contains("-y").contains("server-time");
        ArgumentCaptor<Object> evtCap = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(evtCap.capture());
        assertThat(evtCap.getValue()).isInstanceOf(McpServerUpsertedEvent.class);
        assertThat(((McpServerUpsertedEvent) evtCap.getValue()).serverName()).isEqualTo("time");
    }

    @Test
    @DisplayName("create rejects duplicate name with 400-style IllegalArgument")
    void create_duplicateName() {
        McpServerRequest req = new McpServerRequest();
        req.setName("time");
        req.setCommand("npx");
        when(repository.existsByName("time")).thenReturn(true);
        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("update rejects rename attempts (name immutable post-create)")
    void update_rejectsRename() {
        McpServerEntity existing = row(7L, "time");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        McpServerRequest req = new McpServerRequest();
        req.setName("time-2");
        assertThatThrownBy(() -> service.update(7L, 1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("update accepts PUT with name omitted (FE r2-W1 round-trip safe)")
    void update_omitNameAccepted() {
        // r2-W1 follow-up: FE omits name on PUT to avoid the rename-risk surface.
        // Service must NOT reject when req.name == null (no rename intent).
        McpServerEntity existing = row(7L, "time");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        McpServerRequest req = new McpServerRequest();
        req.setEnabled(false); // operator just toggled enabled
        // req.name stays null
        McpServerEntity updated = service.update(7L, 1L, req);
        assertThat(updated.getName()).isEqualTo("time"); // unchanged
        assertThat(updated.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("update accepts PUT with explicit same name (no-op rename)")
    void update_explicitSameNameAccepted() {
        McpServerEntity existing = row(7L, "time");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        McpServerRequest req = new McpServerRequest();
        req.setName("time"); // identity — must be accepted
        req.setDescription("touched");
        McpServerEntity updated = service.update(7L, 1L, req);
        assertThat(updated.getName()).isEqualTo("time");
        assertThat(updated.getDescription()).isEqualTo("touched");
    }

    @Test
    @DisplayName("update env: '***' for existing key preserves the persisted secret")
    void update_envMaskedPreservesExisting() {
        McpServerEntity existing = row(7L, "github");
        existing.setEnv("{\"TOKEN\":\"ghp_real_secret\",\"BASE_URL\":\"${MY_URL}\"}");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        McpServerRequest req = new McpServerRequest();
        // Operator opened the edit form; FE round-trips the masked TOKEN as "***".
        req.setEnv(Map.of(
                "TOKEN", "***",            // should preserve existing
                "BASE_URL", "${MY_URL}"    // unchanged placeholder
        ));
        McpServerEntity updated = service.update(7L, 1L, req);
        assertThat(updated.getEnv()).contains("ghp_real_secret"); // preserved!
        assertThat(updated.getEnv()).doesNotContain("\"TOKEN\":\"***\"");
        assertThat(updated.getEnv()).contains("${MY_URL}");
    }

    @Test
    @DisplayName("update env: real new value overwrites existing (rotation)")
    void update_envRealValueRotates() {
        McpServerEntity existing = row(7L, "github");
        existing.setEnv("{\"TOKEN\":\"ghp_old_secret\"}");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        McpServerRequest req = new McpServerRequest();
        req.setEnv(Map.of("TOKEN", "ghp_NEW_secret"));
        McpServerEntity updated = service.update(7L, 1L, req);
        assertThat(updated.getEnv()).contains("ghp_NEW_secret");
        assertThat(updated.getEnv()).doesNotContain("ghp_old_secret");
    }

    @Test
    @DisplayName("update env: '***' for NEW key (no existing) is stored verbatim (operator error)")
    void update_envMaskedForNewKeyStoredVerbatim() {
        McpServerEntity existing = row(7L, "x");
        existing.setEnv("{}"); // no existing keys
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        McpServerRequest req = new McpServerRequest();
        req.setEnv(Map.of("NEW_KEY", "***"));
        McpServerEntity updated = service.update(7L, 1L, req);
        // Edge case: operator typed "***" for a new key — preserve nothing to substitute.
        // Store the literal so the mistake is visible in DB / logs (don't silently swallow).
        assertThat(updated.getEnv()).contains("\"NEW_KEY\":\"***\"");
    }

    @Test
    @DisplayName("update env: omitted keys in PUT are dropped (PUT-replaces shape preserved)")
    void update_envOmittedKeysDropped() {
        McpServerEntity existing = row(7L, "x");
        existing.setEnv("{\"OLD\":\"oldval\",\"KEEP\":\"k\"}");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        McpServerRequest req = new McpServerRequest();
        // Only sends KEEP; OLD is gone from request → should be removed (full replace).
        req.setEnv(Map.of("KEEP", "***")); // *** preserves "k"
        McpServerEntity updated = service.update(7L, 1L, req);
        assertThat(updated.getEnv()).contains("\"KEEP\":\"k\"");
        assertThat(updated.getEnv()).doesNotContain("OLD");
    }

    @Test
    @DisplayName("update partially patches and publishes upserted event")
    void update_partial() {
        McpServerEntity existing = row(7L, "time");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        McpServerRequest req = new McpServerRequest();
        req.setEnabled(false);
        req.setDescription("now disabled");
        McpServerEntity updated = service.update(7L, 1L, req);
        assertThat(updated.isEnabled()).isFalse();
        assertThat(updated.getDescription()).isEqualTo("now disabled");
        // command/args/env untouched
        assertThat(updated.getCommand()).isEqualTo("npx");
        verify(eventPublisher).publishEvent(any(McpServerUpsertedEvent.class));
    }

    @Test
    @DisplayName("delete throws McpServerInUseException when an agent references the server (INV-12)")
    void delete_inUse() {
        McpServerEntity entity = row(7L, "time");
        when(repository.findById(7L)).thenReturn(Optional.of(entity));
        AgentEntity referencing = new AgentEntity();
        referencing.setId(99L);
        referencing.setName("scheduler-agent");
        referencing.setMcpServerIds("time,foo");
        AgentEntity unrelated = new AgentEntity();
        unrelated.setId(100L);
        unrelated.setName("other");
        unrelated.setMcpServerIds("foo");
        when(agentRepository.findAll()).thenReturn(List.of(referencing, unrelated));

        assertThatThrownBy(() -> service.delete(7L, 1L))
                .isInstanceOf(McpServerInUseException.class)
                .hasMessageContaining("scheduler-agent")
                .hasMessageContaining("time");
        verify(repository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any(McpServerDeletedEvent.class));
    }

    @Test
    @DisplayName("delete proceeds when no agent references and publishes McpServerDeletedEvent")
    void delete_happyPath() {
        McpServerEntity entity = row(7L, "time");
        when(repository.findById(7L)).thenReturn(Optional.of(entity));
        AgentEntity unrelated = new AgentEntity();
        unrelated.setMcpServerIds("foo,bar");
        when(agentRepository.findAll()).thenReturn(List.of(unrelated));
        service.delete(7L, 1L);
        verify(repository).delete(entity);
        verify(eventPublisher).publishEvent(any(McpServerDeletedEvent.class));
    }

    @Test
    @DisplayName("findAgentsReferencing splits comma-list exactly — 'time' must NOT match 'time2'")
    void delete_substringNoFalseMatch() {
        // Critical bug shield: a naive LIKE '%time%' would match 'time2'. We split by comma.
        McpServerEntity entity = row(7L, "time");
        when(repository.findById(7L)).thenReturn(Optional.of(entity));
        AgentEntity a = new AgentEntity();
        a.setName("agent-with-time2");
        a.setMcpServerIds("time2");          // NOT a match
        AgentEntity b = new AgentEntity();
        b.setName("agent-with-otime");
        b.setMcpServerIds("otime");          // NOT a match
        AgentEntity c = new AgentEntity();
        c.setName("agent-with-spacing");
        c.setMcpServerIds(" time , other "); // trim → match
        when(agentRepository.findAll()).thenReturn(List.of(a, b, c));

        assertThatThrownBy(() -> service.delete(7L, 1L))
                .isInstanceOf(McpServerInUseException.class)
                .hasMessageContaining("agent-with-spacing")
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).doesNotContain("agent-with-time2");
                    assertThat(msg).doesNotContain("agent-with-otime");
                });
    }

    @Test
    @DisplayName("get throws McpServerNotFoundException for unknown id")
    void get_notFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(McpServerNotFoundException.class);
    }

    @Test
    @DisplayName("parseServerIds trims, dedups, drops empty tokens")
    void parseServerIds_normalization() {
        assertThat(McpServerService.parseServerIds(null)).isEmpty();
        assertThat(McpServerService.parseServerIds("")).isEmpty();
        assertThat(McpServerService.parseServerIds("time")).containsExactly("time");
        assertThat(McpServerService.parseServerIds(" time , foo , time , ,bar"))
                .containsExactly("time", "foo", "bar");
    }

    @Test
    @DisplayName("mergeEnvPreservingMasked: pure unit semantics for the merge helper")
    void mergeEnvPreservingMasked_pureSemantics() {
        Map<String, String> existing = Map.of(
                "TOKEN", "real_secret",
                "URL", "https://api.example.com");
        // 1. masked + existing → preserve
        // 2. masked + no-existing → store literal
        // 3. real value → use as-is
        // 4. placeholder → use as-is (passes through)
        Map<String, String> incoming = new java.util.LinkedHashMap<>();
        incoming.put("TOKEN", "***");        // preserve real_secret
        incoming.put("NEW", "***");          // no existing → store ***
        incoming.put("URL", "https://new.example.com"); // real change
        incoming.put("PLH", "${SOMETHING}"); // placeholder pass-through
        Map<String, String> merged = McpServerService.mergeEnvPreservingMasked(existing, incoming);
        assertThat(merged)
                .containsEntry("TOKEN", "real_secret")
                .containsEntry("NEW", "***")
                .containsEntry("URL", "https://new.example.com")
                .containsEntry("PLH", "${SOMETHING}");
        // Order preserved (LinkedHashMap)
        assertThat(merged.keySet()).containsExactly("TOKEN", "NEW", "URL", "PLH");
    }

    @Test
    @DisplayName("mergeEnvPreservingMasked: null/empty inputs handled defensively")
    void mergeEnvPreservingMasked_nullSafety() {
        assertThat(McpServerService.mergeEnvPreservingMasked(null, null)).isEmpty();
        assertThat(McpServerService.mergeEnvPreservingMasked(Map.of(), null)).isEmpty();
        assertThat(McpServerService.mergeEnvPreservingMasked(null, Map.of("X", "***")))
                .containsEntry("X", "***"); // no existing → literal
    }
}
