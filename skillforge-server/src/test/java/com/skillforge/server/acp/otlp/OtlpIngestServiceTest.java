package com.skillforge.server.acp.otlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AcpCcEventEntity;
import com.skillforge.server.repository.AcpCcEventRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link OtlpIngestService} — binding to the cc sub-session, the
 * unknown-{@code sf.session_id} drop gate, and PII filtering (ACP-EXTERNAL-AGENT
 * P2-1). Repos are mocked; ingest runs synchronously via {@link #ingest}.
 */
class OtlpIngestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AcpCcEventRepository eventRepository;
    private SessionRepository sessionRepository;
    private OtlpIngestService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(AcpCcEventRepository.class);
        sessionRepository = mock(SessionRepository.class);
        when(eventRepository.countBySessionId(anyString())).thenReturn(0L);
        service = new OtlpIngestService(
                new OtlpLogsParser(), eventRepository, sessionRepository, mapper, Runnable::run);
    }

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    private String payloadForSession(String sfSessionId) {
        return """
            {
              "resourceLogs": [{
                "resource": {"attributes": [
                  {"key": "sf.session_id", "value": {"stringValue": "%s"}},
                  {"key": "session.id", "value": {"stringValue": "cc-1"}},
                  {"key": "user.email", "value": {"stringValue": "pii@example.com"}},
                  {"key": "user.account_uuid", "value": {"stringValue": "acct-uuid"}},
                  {"key": "user.account_id", "value": {"stringValue": "acct-id"}},
                  {"key": "user.id", "value": {"stringValue": "uid"}},
                  {"key": "organization.id", "value": {"stringValue": "org-1"}}
                ]},
                "scopeLogs": [{"logRecords": [
                  {
                    "body": {"stringValue": "claude_code.api_request"},
                    "attributes": [
                      {"key": "model", "value": {"stringValue": "claude-opus"}},
                      {"key": "input_tokens", "value": {"intValue": "100"}},
                      {"key": "cost_usd", "value": {"doubleValue": 0.01}},
                      {"key": "agent.name", "value": {"stringValue": "code-reviewer"}}
                    ]
                  },
                  {
                    "body": {"stringValue": "claude_code.tool_result"},
                    "attributes": [
                      {"key": "tool_name", "value": {"stringValue": "Bash"}},
                      {"key": "tool_use_id", "value": {"stringValue": "tu-1"}},
                      {"key": "success", "value": {"boolValue": true}}
                    ]
                  },
                  {
                    "body": {"stringValue": "claude_code.user_prompt"},
                    "attributes": [
                      {"key": "prompt_length", "value": {"intValue": "37"}},
                      {"key": "prompt", "value": {"stringValue": "secret user prompt body"}}
                    ]
                  }
                ]}]
              }]
            }
            """.formatted(sfSessionId);
    }

    @Test
    @DisplayName("known sf.session_id → events persisted, bound to that session, structural cols set")
    void ingest_knownSession_persistsAndBinds() throws Exception {
        when(sessionRepository.existsById("sub-1")).thenReturn(true);

        int n = service.ingest(tree(payloadForSession("sub-1")));

        assertThat(n).isEqualTo(3);
        ArgumentCaptor<AcpCcEventEntity> rows = ArgumentCaptor.forClass(AcpCcEventEntity.class);
        verify(eventRepository, org.mockito.Mockito.times(3)).save(rows.capture());
        List<AcpCcEventEntity> saved = rows.getAllValues();
        assertThat(saved).allSatisfy(r -> assertThat(r.getSessionId()).isEqualTo("sub-1"));
        assertThat(saved).extracting(AcpCcEventEntity::getEventName).containsExactly(
                "claude_code.api_request", "claude_code.tool_result", "claude_code.user_prompt");

        AcpCcEventEntity api = saved.get(0);
        assertThat(api.getCcSessionId()).isEqualTo("cc-1");
        assertThat(api.getAgentName()).isEqualTo("code-reviewer");
        AcpCcEventEntity tool = saved.get(1);
        assertThat(tool.getToolName()).isEqualTo("Bash");
        assertThat(tool.getToolUseId()).isEqualTo("tu-1");
    }

    @Test
    @DisplayName("PII (email/account uuids/org/prompt text) is NOT persisted; prompt_length + structural attrs ARE")
    void ingest_filtersPii() throws Exception {
        when(sessionRepository.existsById("sub-1")).thenReturn(true);

        service.ingest(tree(payloadForSession("sub-1")));

        ArgumentCaptor<AcpCcEventEntity> rows = ArgumentCaptor.forClass(AcpCcEventEntity.class);
        verify(eventRepository, org.mockito.Mockito.times(3)).save(rows.capture());

        // Inspect the user_prompt row's attrs_json — prompt_length kept, prompt text gone.
        AcpCcEventEntity prompt = rows.getAllValues().get(2);
        assertThat(prompt.getAttrsJson()).contains("prompt_length");
        assertThat(prompt.getAttrsJson()).doesNotContain("secret user prompt body");
        assertThat(prompt.getAttrsJson()).doesNotContain("\"prompt\":");

        // No row's attrs_json may carry any PII key/value.
        for (AcpCcEventEntity r : rows.getAllValues()) {
            assertThat(r.getAttrsJson()).doesNotContain("pii@example.com");
            assertThat(r.getAttrsJson()).doesNotContain("acct-uuid");
            assertThat(r.getAttrsJson()).doesNotContain("acct-id");
            assertThat(r.getAttrsJson()).doesNotContain("organization.id");
            assertThat(r.getAttrsJson()).doesNotContain("user.email");
            assertThat(r.getAttrsJson()).doesNotContain("user.id");
        }
        // structural survives.
        assertThat(rows.getAllValues().get(0).getAttrsJson()).contains("model").contains("claude-opus");
    }

    @Test
    @DisplayName("unknown sf.session_id (no matching t_session) → ALL events dropped, nothing persisted")
    void ingest_unknownSession_dropped() throws Exception {
        when(sessionRepository.existsById("ghost")).thenReturn(false);

        int n = service.ingest(tree(payloadForSession("ghost")));

        assertThat(n).isZero();
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("absent sf.session_id → dropped without even hitting the session existence check")
    void ingest_absentSfSessionId_dropped() throws Exception {
        String json = """
            {"resourceLogs":[{"resource":{"attributes":[]},"scopeLogs":[{"logRecords":[
              {"body":{"stringValue":"claude_code.api_request"},"attributes":[]}
            ]}]}]}
            """;
        int n = service.ingest(tree(json));
        assertThat(n).isZero();
        verify(eventRepository, never()).save(any());
        verify(sessionRepository, never()).existsById(anyString());
    }

    @Test
    @DisplayName("per-session cap: once at MAX, further events for that session are shed")
    void ingest_perSessionCap_sheds() throws Exception {
        when(sessionRepository.existsById("sub-1")).thenReturn(true);
        when(eventRepository.countBySessionId("sub-1")).thenReturn(10_000L);

        int n = service.ingest(tree(payloadForSession("sub-1")));

        assertThat(n).isZero();
        verify(eventRepository, never()).save(any());
    }
}
