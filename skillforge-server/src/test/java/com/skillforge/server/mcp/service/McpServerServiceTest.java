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

    // -----------------------------------------------------------------------
    // http transport (V152)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("create stdio (default transport) still requires command")
    void create_stdioRequiresCommand() {
        McpServerRequest req = new McpServerRequest();
        req.setName("nocommand");
        // transport omitted → defaults to stdio; command missing → reject
        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command is required");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create http: url required, command optional, transport + headers persisted")
    void create_httpPersistsUrlAndHeaders() {
        McpServerRequest req = new McpServerRequest();
        req.setName("anysearch");
        req.setTransport("http");
        req.setUrl("https://api.anysearch.com/mcp");
        req.setHeaders(Map.of("Authorization", "Bearer ${ANY_SEARCH_API_KEY}"));
        when(repository.existsByName("anysearch")).thenReturn(false);
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> {
            McpServerEntity e = inv.getArgument(0);
            e.setId(11L);
            return e;
        });

        McpServerEntity created = service.create(1L, req);
        assertThat(created.getTransport()).isEqualTo("http");
        assertThat(created.getUrl()).isEqualTo("https://api.anysearch.com/mcp");
        assertThat(created.getCommand()).isNull(); // optional for http
        assertThat(created.getHeaders()).contains("Authorization").contains("${ANY_SEARCH_API_KEY}");
        verify(eventPublisher).publishEvent(any(McpServerUpsertedEvent.class));
    }

    @Test
    @DisplayName("create http without url is rejected")
    void create_httpRequiresUrl() {
        McpServerRequest req = new McpServerRequest();
        req.setName("nourl");
        req.setTransport("http");
        // url missing
        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url is required");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create rejects an unknown transport value")
    void create_rejectsUnknownTransport() {
        McpServerRequest req = new McpServerRequest();
        req.setName("weird");
        req.setTransport("ftp");
        req.setUrl("ftp://x");
        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transport must be one of");
    }

    @Test
    @DisplayName("update rejects a transport change (immutable post-create)")
    void update_transportImmutable() {
        McpServerEntity existing = row(7L, "time"); // stdio
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        McpServerRequest req = new McpServerRequest();
        req.setTransport("http");
        assertThatThrownBy(() -> service.update(7L, 1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transport is immutable");
    }

    @Test
    @DisplayName("update http: url editable; '***' header preserves persisted secret")
    void update_httpUrlAndHeaderMaskPreserve() {
        McpServerEntity existing = new McpServerEntity();
        existing.setId(11L);
        existing.setName("anysearch");
        existing.setTransport("http");
        existing.setUrl("https://old.example.com/mcp");
        existing.setHeaders("{\"Authorization\":\"Bearer real_secret\"}");
        when(repository.findById(11L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        McpServerRequest req = new McpServerRequest();
        req.setUrl("https://new.example.com/mcp");
        req.setHeaders(Map.of("Authorization", "***")); // round-tripped masked value
        McpServerEntity updated = service.update(11L, 1L, req);

        assertThat(updated.getUrl()).isEqualTo("https://new.example.com/mcp");
        assertThat(updated.getHeaders()).contains("real_secret"); // preserved!
        assertThat(updated.getHeaders()).doesNotContain("\"Authorization\":\"***\"");
    }

    @Test
    @DisplayName("update http: blank url for http transport is rejected")
    void update_httpBlankUrlRejected() {
        McpServerEntity existing = new McpServerEntity();
        existing.setId(11L);
        existing.setName("anysearch");
        existing.setTransport("http");
        existing.setUrl("https://x/mcp");
        when(repository.findById(11L)).thenReturn(Optional.of(existing));
        McpServerRequest req = new McpServerRequest();
        req.setUrl("   ");
        assertThatThrownBy(() -> service.update(11L, 1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url must not be blank");
    }

    @Test
    @DisplayName("parseHeaders round-trips the persisted headers JSON")
    void parseHeaders_roundTrip() {
        McpServerEntity e = new McpServerEntity();
        e.setName("anysearch");
        e.setHeaders("{\"Authorization\":\"Bearer ${K}\",\"X\":\"y\"}");
        assertThat(service.parseHeaders(e))
                .containsEntry("Authorization", "Bearer ${K}")
                .containsEntry("X", "y");
    }

    @Test
    @DisplayName("create http rejects plain http:// for a remote host (would leak bearer token)")
    void create_httpRejectsPlainHttpRemote() {
        McpServerRequest req = new McpServerRequest();
        req.setName("insecure");
        req.setTransport("http");
        req.setUrl("http://api.anysearch.com/mcp");
        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create http allows plain http:// for loopback hosts (local self-hosted MCP)")
    void create_httpAllowsLoopbackPlainHttp() {
        for (String localUrl : List.of(
                "http://localhost:8080/mcp",
                "http://127.0.0.1:3000/mcp",
                "http://[::1]:9000/mcp")) {
            McpServerRequest req = new McpServerRequest();
            req.setName("localmcp");
            req.setTransport("http");
            req.setUrl(localUrl);
            when(repository.existsByName("localmcp")).thenReturn(false);
            when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            McpServerEntity created = service.create(1L, req);
            assertThat(created.getUrl()).isEqualTo(localUrl);
        }
    }

    @Test
    @DisplayName("update http rejects switching url to plain http:// remote host")
    void update_httpRejectsPlainHttpRemote() {
        McpServerEntity existing = new McpServerEntity();
        existing.setId(11L);
        existing.setName("anysearch");
        existing.setTransport("http");
        existing.setUrl("https://api.anysearch.com/mcp");
        when(repository.findById(11L)).thenReturn(Optional.of(existing));
        McpServerRequest req = new McpServerRequest();
        req.setUrl("http://evil.example.com/mcp");
        assertThatThrownBy(() -> service.update(11L, 1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("create http (BUG-33 SSRF) rejects https urls resolving to internal/private/metadata IPs")
    void create_httpRejectsInternalPrivateHosts() {
        // Literal IPs — InetAddress parses these without a DNS lookup, so the test is deterministic.
        for (String badUrl : List.of(
                "https://169.254.169.254/mcp",   // link-local: AWS/GCP metadata endpoint
                "https://10.0.0.5/mcp",          // site-local 10/8
                "https://192.168.1.1/mcp",       // site-local 192.168/16
                "https://172.16.0.1/mcp",        // site-local 172.16/12
                "https://[fd00::1]/mcp",         // IPv6 unique-local fc00::/7
                "https://[::ffff:169.254.169.254]/mcp", // IPv4-mapped IPv6 → embedded metadata IP
                "https://100.64.0.1/mcp")) {     // carrier-grade NAT 100.64/10
            McpServerRequest req = new McpServerRequest();
            req.setName("ssrf");
            req.setTransport("http");
            req.setUrl(badUrl);
            assertThatThrownBy(() -> service.create(1L, req))
                    .as("url should be rejected: %s", badUrl)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("disallowed internal/private");
        }
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create http (BUG-33 SSRF) allows https to a public host")
    void create_httpAllowsPublicHost() {
        McpServerRequest req = new McpServerRequest();
        req.setName("publicmcp");
        req.setTransport("http");
        req.setUrl("https://8.8.8.8/mcp"); // public literal IP — parsed, not blocked
        when(repository.existsByName("publicmcp")).thenReturn(false);
        when(repository.save(any(McpServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        McpServerEntity created = service.create(1L, req);
        assertThat(created.getUrl()).isEqualTo("https://8.8.8.8/mcp");
    }
}
