package com.skillforge.server.controller.observability;

import com.skillforge.observability.api.BlobStore;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.server.controller.observability.dto.BlobMetaDto;
import com.skillforge.server.controller.observability.dto.LlmSpanDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Plan §7.1 + §6.2 R3-WN3 + R3-W6 — LLM span detail + controlled blob streaming with
 * concurrency limit and per-call ownership enforcement.
 */
@Controller
@RequestMapping("/api/observability")
public class LlmSpanController {

    private static final Logger log = LoggerFactory.getLogger(LlmSpanController.class);

    /** Plan §6.2: blob path canonical pattern. */
    private static final Pattern BLOB_PATH_PATTERN = Pattern.compile(
            "^\\d{8}/[a-f0-9-]{36}/[a-f0-9-]{36}-(request|response|sse)\\.(json|txt)$");

    private final LlmTraceStore traceStore;
    private final BlobStore blobStore;
    private final Semaphore blobReadSemaphore;
    private final ObservabilityOwnershipGuard ownershipGuard;

    public LlmSpanController(LlmTraceStore traceStore,
                             BlobStore blobStore,
                             @Qualifier("blobReadSemaphore") Semaphore blobReadSemaphore,
                             ObservabilityOwnershipGuard ownershipGuard) {
        this.traceStore = traceStore;
        this.blobStore = blobStore;
        this.blobReadSemaphore = blobReadSemaphore;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/spans/{spanId}")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<LlmSpanDetailDto> getSpan(@PathVariable String spanId,
                                                    @RequestParam Long userId) {
        Optional<LlmSpan> opt = traceStore.readSpan(spanId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        LlmSpan s = opt.get();
        // R3-W6: enforce ownership against the LLM span's session before returning data.
        ownershipGuard.requireOwned(s.sessionId(), userId);
        BlobMetaDto blobs = new BlobMetaDto(
                s.inputBlobRef() != null,
                s.outputBlobRef() != null,
                s.rawSseBlobRef() != null,
                null, null, null);
        // PROMPT-CACHE-MVP Phase 4: surface hit-rate-friendly fields. Usage map carries
        // cache-aware token counts so the dashboard can compute hit rate without re-reading
        // the parsed response. Empty / missing attributes never produce false positives.
        Map<String, Object> usage = new java.util.LinkedHashMap<>();
        usage.put("inputTokens", s.inputTokens());
        usage.put("outputTokens", s.outputTokens());
        if (s.cacheReadTokens() != null) usage.put("cacheReadInputTokens", s.cacheReadTokens());
        if (s.cacheCreationTokens() != null) usage.put("cacheCreationInputTokens", s.cacheCreationTokens());

        // PROMPT-CACHE-MVP Phase 4 r2 (FE B2 wire-shape fix): expose a single
        // metadata map keyed by snake_case so FE's `metadata.cache_break` shape
        // matches BE attributes_json directly. The map is always non-null (empty when
        // no flags) so FE can chain `metadata?.cache_break` without accommodating null.
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        Map<String, Object> attrs = s.attributes();
        if (attrs != null) {
            Object cb = attrs.get("cache_break");
            // Coerce String("true") -> true to tolerate stale rows where attributes_json
            // contained a stringified boolean. Boolean instances pass through verbatim.
            boolean broken = (cb instanceof Boolean b && b)
                    || (cb != null && Boolean.parseBoolean(cb.toString()));
            if (broken) {
                metadata.put("cache_break", true);
                Object reason = attrs.get("cache_break_reason");
                if (reason != null) metadata.put("cache_break_reason", reason);
            }
        }

        LlmSpanDetailDto dto = new LlmSpanDetailDto(
                s.spanId(), s.traceId(), s.parentSpanId(), s.sessionId(),
                s.provider(), s.model(),
                s.iterationIndex(), s.stream(),
                s.inputSummary(), s.outputSummary(),
                s.cacheReadTokens(),
                s.cacheCreationTokens(),
                metadata,
                usage,
                s.costUsd(), s.latencyMs(),
                s.startedAt(), s.endedAt(),
                s.finishReason(), s.requestId(),
                s.reasoningContent(),
                s.error(), s.errorType(),
                s.source().wireValue(),
                s.blobStatus(),
                blobs);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/spans/{spanId}/blob")
    public ResponseEntity<StreamingResponseBody> getBlob(
            @PathVariable String spanId,
            @RequestParam Long userId,
            @RequestParam String part) {
        if (!("request".equals(part) || "response".equals(part) || "sse".equals(part))) {
            return ResponseEntity.badRequest().build();
        }
        if (!blobReadSemaphore.tryAcquire()) {
            return ResponseEntity.status(429)
                    .body(out -> {
                        try {
                            out.write("{\"error\":\"blob read concurrency limit reached, retry later\"}"
                                    .getBytes());
                        } catch (IOException ignored) {}
                    });
        }
        boolean streamOwned = false;
        try {
            Optional<LlmSpan> spanOpt = traceStore.readSpan(spanId);
            if (spanOpt.isEmpty()) return ResponseEntity.notFound().build();
            LlmSpan s = spanOpt.get();
            // R3-W6: enforce ownership before exposing any blob bytes. ResponseStatusException
            // propagates out (Spring maps to the right HTTP status); the outer finally still
            // releases the permit because streamOwned is still false at this point.
            ownershipGuard.requireOwned(s.sessionId(), userId);
            String ref = switch (part) {
                case "request" -> s.inputBlobRef();
                case "response" -> s.outputBlobRef();
                case "sse" -> s.rawSseBlobRef();
                default -> null;
            };
            // Plan §7.1 R2-W6: legacy span blob endpoint always 404.
            if (ref == null) return ResponseEntity.notFound().build();
            if (!BLOB_PATH_PATTERN.matcher(ref).matches()) {
                log.warn("blob ref does not match canonical path: {}", ref);
                return ResponseEntity.notFound().build();
            }
            Optional<InputStream> stream = blobStore.openStream(ref);
            if (stream.isEmpty()) return ResponseEntity.notFound().build();
            String contentType = "sse".equals(part)
                    ? MediaType.TEXT_PLAIN_VALUE : MediaType.APPLICATION_JSON_VALUE;
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            // Permit ownership transfers to StreamingResponseBody once we commit to streaming.
            streamOwned = true;
            StreamingResponseBody body = out -> {
                try (InputStream in = stream.get()) {
                    in.transferTo(out);
                } finally {
                    blobReadSemaphore.release();
                }
            };
            return ResponseEntity.ok().headers(headers).body(body);
        } catch (ResponseStatusException rse) {
            // R3-W6: ownership / 4xx errors keep their original status (do NOT
            // collapse to 500). The outer finally still releases the permit.
            throw rse;
        } catch (Exception e) {
            log.warn("blob read failed: spanId={} part={}", spanId, part, e);
            return ResponseEntity.internalServerError().build();
        } finally {
            // BE-B1 fix: release permit on every non-streaming exit (404 / bad ref / exception).
            // Streaming path sets streamOwned=true and the StreamingResponseBody finally releases.
            if (!streamOwned) blobReadSemaphore.release();
        }
    }
}
