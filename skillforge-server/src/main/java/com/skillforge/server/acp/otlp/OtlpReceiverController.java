package com.skillforge.server.acp.otlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OTLP/HTTP receiver for telemetry emitted by spawned Claude Code (cc) child
 * processes (ACP-EXTERNAL-AGENT P2-1).
 *
 * <p>Spike-verified (2026-06-19): cc emits OTLP metrics + logs(events), NO
 * traces/spans. We accept both endpoints; <b>logs are processed, metrics are
 * accepted-and-dropped</b> for P2-1.
 *
 * <p><b>Auth:</b> these endpoints live OUTSIDE {@code /api/**}, so the
 * {@code AuthInterceptor} (which only gates {@code /api/**}) does NOT require a
 * bearer token — that is intentional: the cc child is a localhost machine-to-
 * machine client that can not present the SkillForge token. The endpoint is
 * instead protected by the {@code sf.session_id} existence gate in
 * {@link OtlpIngestService} (only events for an existing session are persisted) +
 * a per-request body-size bound here, so it can not be spammed into unbounded DB
 * growth. (Binding the listener to localhost is a deployment-level follow-up; the
 * existence gate is the load-bearing control.)
 *
 * <p><b>Non-blocking:</b> the receiver parses the JSON quickly then hands ingest to
 * a bounded executor ({@link OtlpIngestService#ingestAsync}) and returns
 * {@code {}} 200 immediately, so DB work never stalls the cc export thread.
 */
@RestController
@RequestMapping("/v1")
public class OtlpReceiverController {

    private static final Logger log = LoggerFactory.getLogger(OtlpReceiverController.class);

    /** Reject oversized export bodies (DoS / memory guard). 8 MiB is far above a real cc batch. */
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    /** OTLP/HTTP success response is an (optionally empty) JSON object. */
    private static final String OTLP_OK = "{}";

    private final ObjectMapper objectMapper;
    private final OtlpIngestService ingestService;

    public OtlpReceiverController(ObjectMapper objectMapper, OtlpIngestService ingestService) {
        this.objectMapper = objectMapper;
        this.ingestService = ingestService;
    }

    /**
     * OTLP logs export. Parses the OTLP-JSON body, hands ingest to the bounded
     * executor, and returns {@code {}} 200 immediately. Malformed JSON is answered
     * 400 (so the exporter logs it) but never throws.
     */
    @PostMapping(path = "/logs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> logs(@RequestBody(required = false) byte[] body) {
        if (body == null || body.length == 0) {
            return ok();
        }
        if (body.length > MAX_BODY_BYTES) {
            log.warn("OTLP logs body too large ({} bytes); rejecting", body.length);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .contentType(MediaType.APPLICATION_JSON).body(OTLP_OK);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("OTLP logs body parse failed: {}", e.toString());
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(OTLP_OK);
        }
        ingestService.ingestAsync(root);
        return ok();
    }

    /**
     * OTLP metrics export — accepted and DROPPED for P2-1 (only logs are processed).
     * Returns {@code {}} 200 so the cc exporter does not retry/back off.
     */
    @PostMapping(path = "/metrics", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> metrics(@RequestBody(required = false) byte[] body) {
        // Intentionally not processed in P2-1.
        return ok();
    }

    private static ResponseEntity<String> ok() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(OTLP_OK);
    }
}
