package com.skillforge.server.controller.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.api.BlobStore;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.observability.domain.LlmTrace;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.observability.SessionSpansService;
import com.skillforge.server.service.observability.SubagentSessionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan §6.3 / §7.3 R3-W6 — ownership enforcement integration test for the four
 * observability endpoints. Covers (per controller):
 *
 * <ul>
 *   <li>200 — owner querying their own session/span/trace</li>
 *   <li>403 — non-owner querying someone else's resource</li>
 *   <li>400 — missing {@code userId} parameter</li>
 *   <li>404 — non-existent resource</li>
 * </ul>
 *
 * <p>Uses {@code MockMvcBuilders.standaloneSetup} so the test does not require
 * Spring context bootstrap (no {@code @SpringBootTest} infrastructure exists in
 * this module yet — see BE-W1 follow-up).
 */
@EnableWebMvc
@DisplayName("Observability controllers — userId ownership enforcement")
class SessionSpansAuthIT {

    private static final Long USER_1 = 1L;
    private static final Long USER_2 = 2L;
    private static final String SESSION_USER_1 = "session-user-1";
    private static final String SESSION_USER_2 = "session-user-2";

    private SessionService sessionService;
    private SessionSpansService spansService;
    private LlmTraceStore traceStore;
    private BlobStore blobStore;
    private SubagentSessionResolver subagentResolver;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        spansService = mock(SessionSpansService.class);
        traceStore = mock(LlmTraceStore.class);
        blobStore = mock(BlobStore.class);
        subagentResolver = mock(SubagentSessionResolver.class);

        // Seed two sessions with distinct owners.
        SessionEntity s1 = new SessionEntity();
        s1.setId(SESSION_USER_1);
        s1.setUserId(USER_1);
        SessionEntity s2 = new SessionEntity();
        s2.setId(SESSION_USER_2);
        s2.setUserId(USER_2);
        when(sessionService.getSession(eq(SESSION_USER_1))).thenReturn(s1);
        when(sessionService.getSession(eq(SESSION_USER_2))).thenReturn(s2);
        when(sessionService.getSession(eq("missing-session")))
                .thenThrow(new RuntimeException("Session not found: missing-session"));

        ObservabilityOwnershipGuard guard = new ObservabilityOwnershipGuard(sessionService);

        SessionSpansController spansCtrl = new SessionSpansController(spansService, guard);
        LlmSpanController llmSpanCtrl = new LlmSpanController(
                traceStore, blobStore, new Semaphore(20), guard);
        LlmTraceController traceCtrl = new LlmTraceController(traceStore, guard);
        // OBS-2 M3 — ToolSpanController now reads from t_llm_span via LlmTraceStore.
        ToolSpanController toolCtrl = new ToolSpanController(
                traceStore, subagentResolver, guard);

        mvc = MockMvcBuilders.standaloneSetup(spansCtrl, llmSpanCtrl, traceCtrl, toolCtrl)
                .setMessageConverters(
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                                new ObjectMapper()
                                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())))
                .build();
    }

    // -----------------------------------------------------------------------
    // SessionSpansController — GET /api/observability/sessions/{sid}/spans
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("session spans: owner → 200")
    void sessionSpans_owner_200() throws Exception {
        when(spansService.listMergedSpans(
                eq(SESSION_USER_1), eq(USER_1), any(), any(), anyInt(), any()))
                .thenReturn(List.of());
        mvc.perform(get("/api/observability/sessions/{id}/spans", SESSION_USER_1)
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("session spans: non-owner → 403")
    void sessionSpans_nonOwner_403() throws Exception {
        mvc.perform(get("/api/observability/sessions/{id}/spans", SESSION_USER_2)
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("session spans: missing userId → 400")
    void sessionSpans_missingUserId_400() throws Exception {
        mvc.perform(get("/api/observability/sessions/{id}/spans", SESSION_USER_1))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("session spans: missing session → 404")
    void sessionSpans_missingSession_404() throws Exception {
        mvc.perform(get("/api/observability/sessions/{id}/spans", "missing-session")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // LlmSpanController — GET /api/observability/spans/{spanId}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("llm span detail: owner of span's session → 200")
    void llmSpanDetail_owner_200() throws Exception {
        LlmSpan span = makeLlmSpan("llm-span-1", SESSION_USER_1);
        when(traceStore.readSpan(eq("llm-span-1"))).thenReturn(Optional.of(span));
        mvc.perform(get("/api/observability/spans/{id}", "llm-span-1")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("llm span detail: non-owner → 403")
    void llmSpanDetail_nonOwner_403() throws Exception {
        LlmSpan span = makeLlmSpan("llm-span-2", SESSION_USER_2);
        when(traceStore.readSpan(eq("llm-span-2"))).thenReturn(Optional.of(span));
        mvc.perform(get("/api/observability/spans/{id}", "llm-span-2")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("llm span detail: missing userId → 400")
    void llmSpanDetail_missingUserId_400() throws Exception {
        mvc.perform(get("/api/observability/spans/{id}", "llm-span-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("llm span detail: span not found → 404")
    void llmSpanDetail_notFound_404() throws Exception {
        when(traceStore.readSpan(eq("missing-span"))).thenReturn(Optional.empty());
        mvc.perform(get("/api/observability/spans/{id}", "missing-span")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("llm span blob: non-owner → 403 (semaphore released)")
    void llmSpanBlob_nonOwner_403() throws Exception {
        LlmSpan span = makeLlmSpan("llm-span-blob", SESSION_USER_2);
        when(traceStore.readSpan(eq("llm-span-blob"))).thenReturn(Optional.of(span));
        mvc.perform(get("/api/observability/spans/{id}/blob", "llm-span-blob")
                        .param("userId", String.valueOf(USER_1))
                        .param("part", "request"))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // LlmTraceController — GET /api/observability/traces/{traceId}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("trace: owner → 200")
    void trace_owner_200() throws Exception {
        when(traceStore.readByTraceId(eq("trace-user-1"))).thenReturn(
                Optional.of(new LlmTraceStore.TraceWithSpans(
                        new LlmTrace("trace-user-1", SESSION_USER_1, null, USER_1, "AGENT_LOOP",
                                Instant.parse("2026-04-29T10:00:00Z"),
                                Instant.parse("2026-04-29T10:00:01Z"),
                                10, 5, BigDecimal.ZERO, LlmSpanSource.LIVE),
                        List.of())));
        mvc.perform(get("/api/observability/traces/{id}", "trace-user-1")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("trace: non-owner → 403")
    void trace_nonOwner_403() throws Exception {
        when(traceStore.readByTraceId(eq("trace-user-2"))).thenReturn(
                Optional.of(new LlmTraceStore.TraceWithSpans(
                        new LlmTrace("trace-user-2", SESSION_USER_2, null, USER_2, "AGENT_LOOP",
                                Instant.parse("2026-04-29T10:00:00Z"),
                                Instant.parse("2026-04-29T10:00:01Z"),
                                10, 5, BigDecimal.ZERO, LlmSpanSource.LIVE),
                        List.of())));
        mvc.perform(get("/api/observability/traces/{id}", "trace-user-2")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("trace: missing userId → 400")
    void trace_missingUserId_400() throws Exception {
        mvc.perform(get("/api/observability/traces/{id}", "trace-user-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("trace: not found → 404")
    void trace_notFound_404() throws Exception {
        when(traceStore.readByTraceId(eq("missing-trace"))).thenReturn(Optional.empty());
        mvc.perform(get("/api/observability/traces/{id}", "missing-trace")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // ToolSpanController — GET /api/observability/tool-spans/{spanId}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tool span: owner → 200")
    void toolSpan_owner_200() throws Exception {
        LlmSpan tool = makeToolSpan("tool-span-1", SESSION_USER_1);
        when(traceStore.readSpan(eq("tool-span-1"))).thenReturn(Optional.of(tool));
        when(subagentResolver.resolve(any(LlmSpan.class))).thenReturn(null);
        mvc.perform(get("/api/observability/tool-spans/{id}", "tool-span-1")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("tool span: non-owner → 403")
    void toolSpan_nonOwner_403() throws Exception {
        LlmSpan tool = makeToolSpan("tool-span-2", SESSION_USER_2);
        when(traceStore.readSpan(eq("tool-span-2"))).thenReturn(Optional.of(tool));
        mvc.perform(get("/api/observability/tool-spans/{id}", "tool-span-2")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("tool span: missing userId → 400")
    void toolSpan_missingUserId_400() throws Exception {
        mvc.perform(get("/api/observability/tool-spans/{id}", "tool-span-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("tool span: not found → 404")
    void toolSpan_notFound_404() throws Exception {
        when(traceStore.readSpan(eq("missing-tool"))).thenReturn(Optional.empty());
        mvc.perform(get("/api/observability/tool-spans/{id}", "missing-tool")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("tool span: returns 404 for kind=event row (kind mismatch)")
    void toolSpan_kindMismatch_404() throws Exception {
        LlmSpan event = makeEventSpan("evt-span-x", SESSION_USER_1);
        when(traceStore.readSpan(eq("evt-span-x"))).thenReturn(Optional.of(event));
        mvc.perform(get("/api/observability/tool-spans/{id}", "evt-span-x")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // ToolSpanController — GET /api/observability/event-spans/{spanId}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("event span: owner → 200")
    void eventSpan_owner_200() throws Exception {
        LlmSpan event = makeEventSpan("evt-span-1", SESSION_USER_1);
        when(traceStore.readSpan(eq("evt-span-1"))).thenReturn(Optional.of(event));
        mvc.perform(get("/api/observability/event-spans/{id}", "evt-span-1")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("event span: non-owner → 403")
    void eventSpan_nonOwner_403() throws Exception {
        LlmSpan event = makeEventSpan("evt-span-2", SESSION_USER_2);
        when(traceStore.readSpan(eq("evt-span-2"))).thenReturn(Optional.of(event));
        mvc.perform(get("/api/observability/event-spans/{id}", "evt-span-2")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("event span: returns 404 for kind=tool row (kind mismatch)")
    void eventSpan_kindMismatch_404() throws Exception {
        LlmSpan tool = makeToolSpan("tool-span-yes", SESSION_USER_1);
        when(traceStore.readSpan(eq("tool-span-yes"))).thenReturn(Optional.of(tool));
        mvc.perform(get("/api/observability/event-spans/{id}", "tool-span-yes")
                        .param("userId", String.valueOf(USER_1)))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static LlmSpan makeLlmSpan(String spanId, String sessionId) {
        return new LlmSpan(
                spanId, "trace-x", null, sessionId,
                1L, "claude", "claude-sonnet-4-20250514",
                0, true,
                null, null, null, null, null,
                "ok",
                10, 5, null, null,
                BigDecimal.ZERO, 100L,
                Instant.parse("2026-04-29T10:00:00Z"),
                Instant.parse("2026-04-29T10:00:01Z"),
                "stop", null, null, null, null, null,
                Collections.emptyMap(),
                LlmSpanSource.LIVE);
    }

    private static LlmSpan makeToolSpan(String spanId, String sessionId) {
        return new LlmSpan(
                spanId, "trace-x", "trace-x", sessionId,
                1L, null, null,
                0, false,
                "in", "out", null, null, null,
                "ok",
                0, 0, null, null,
                null, 100L,
                Instant.parse("2026-04-29T10:00:00Z"),
                Instant.parse("2026-04-29T10:00:01Z"),
                null, null, null, null, null, "tu_x",
                Collections.emptyMap(),
                LlmSpanSource.LIVE,
                "tool", null, "Bash");
    }

    private static LlmSpan makeEventSpan(String spanId, String sessionId) {
        return new LlmSpan(
                spanId, "trace-x", "trace-x", sessionId,
                1L, null, null,
                0, false,
                "in", "out", null, null, null,
                "ok",
                0, 0, null, null,
                null, 100L,
                Instant.parse("2026-04-29T10:00:00Z"),
                Instant.parse("2026-04-29T10:00:01Z"),
                null, null, null, null, null, null,
                Collections.emptyMap(),
                LlmSpanSource.LIVE,
                "event", "ask_user", "ask_user");
    }
}
