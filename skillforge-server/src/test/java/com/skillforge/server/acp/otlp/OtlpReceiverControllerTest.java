package com.skillforge.server.acp.otlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link OtlpReceiverController} (ACP-EXTERNAL-AGENT P2-1) — logs routed
 * to ingest, metrics accepted+dropped, size bound, malformed-body handling. Direct
 * controller unit tests (no MockMvc), mirroring {@code AcpRunControllerTest}.
 */
class OtlpReceiverControllerTest {

    private OtlpIngestService ingestService;
    private OtlpReceiverController controller;

    @BeforeEach
    void setUp() {
        ingestService = mock(OtlpIngestService.class);
        controller = new OtlpReceiverController(new ObjectMapper(), ingestService);
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("logs: valid JSON → 200 {} and ingest invoked")
    void logs_validJson_routesToIngest() {
        ResponseEntity<String> resp = controller.logs(bytes("{\"resourceLogs\":[]}"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo("{}");
        verify(ingestService).ingestAsync(any());
    }

    @Test
    @DisplayName("logs: empty body → 200 {} without ingest")
    void logs_emptyBody_ok() {
        ResponseEntity<String> resp = controller.logs(new byte[0]);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ingestService, never()).ingestAsync(any());
    }

    @Test
    @DisplayName("logs: malformed JSON → 400, no ingest, never throws")
    void logs_malformedJson_badRequest() {
        ResponseEntity<String> resp = controller.logs(bytes("not json {"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(ingestService, never()).ingestAsync(any());
    }

    @Test
    @DisplayName("logs: oversized body → 413, no ingest")
    void logs_oversized_payloadTooLarge() {
        byte[] big = new byte[8 * 1024 * 1024 + 1];
        ResponseEntity<String> resp = controller.logs(big);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(ingestService, never()).ingestAsync(any());
    }

    @Test
    @DisplayName("metrics: accepted (200 {}) and NOT processed (dropped) in P2-1")
    void metrics_acceptedAndDropped() {
        ResponseEntity<String> resp = controller.metrics(bytes("{\"resourceMetrics\":[]}"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo("{}");
        verify(ingestService, never()).ingestAsync(any());
    }
}
