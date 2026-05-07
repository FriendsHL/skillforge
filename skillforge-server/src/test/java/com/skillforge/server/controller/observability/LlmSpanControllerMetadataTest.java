package com.skillforge.server.controller.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.api.BlobStore;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.server.controller.observability.dto.LlmSpanDetailDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PROMPT-CACHE-MVP r2 (FE B2 wire-shape fix) — verifies the {@link LlmSpanDetailDto}
 * metadata bag is populated with snake_case keys matching the FE contract:
 * {@code metadata.cache_break === true} when CacheBreakDetector flagged a break, and
 * the JSON wire shape is exactly {@code "metadata": {"cache_break": true, ...}}.
 *
 * <p>The previous shape ({@code Boolean cacheBreak} top-level camelCase) silently
 * failed because FE typed it as {@code metadata.cache_break} nested snake_case;
 * the badge never rendered. This test asserts the new contract end-to-end.
 */
@DisplayName("LlmSpanController — metadata.cache_break wire shape (r2 / FE B2 fix)")
class LlmSpanControllerMetadataTest {

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-cache-1";

    private static ObservabilityOwnershipGuard permissiveGuard() {
        SessionService sessionService = mock(SessionService.class);
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        when(sessionService.getSession(anyString())).thenReturn(session);
        return new ObservabilityOwnershipGuard(sessionService);
    }

    @Test
    @DisplayName("getSpan: cache_break=true attribute lands as metadata.cache_break:true in JSON")
    void cacheBreakTrueLandsInMetadataMap() throws Exception {
        // Arrange: span with cache_break flag in attributes
        LlmSpan span = brokenSpan("span-broken-1", true, "drop>2K_and_<95%_of_prev");
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.readSpan(anyString())).thenReturn(Optional.of(span));
        BlobStore blobStore = mock(BlobStore.class);
        LlmSpanController controller = new LlmSpanController(
                traceStore, blobStore, new Semaphore(20), permissiveGuard());

        // Act
        ResponseEntity<LlmSpanDetailDto> resp = controller.getSpan(span.spanId(), USER_ID);

        // Assert: dto carries metadata map with snake_case keys
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        LlmSpanDetailDto dto = resp.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.metadata()).isNotNull();
        assertThat(dto.metadata())
                .as("metadata.cache_break must be true (snake_case key matches FE typing)")
                .containsEntry("cache_break", true);
        assertThat(dto.metadata())
                .containsEntry("cache_break_reason", "drop>2K_and_<95%_of_prev");

        // Wire shape: serialize to JSON and confirm the literal "metadata": {"cache_break": true}
        ObjectMapper m = new ObjectMapper().findAndRegisterModules();
        JsonNode json = m.readTree(m.writeValueAsString(dto));
        assertThat(json.path("metadata").path("cache_break").asBoolean())
                .as("JSON wire shape: metadata.cache_break must be present + true")
                .isTrue();
        assertThat(json.path("metadata").path("cache_break_reason").asText())
                .isEqualTo("drop>2K_and_<95%_of_prev");
    }

    @Test
    @DisplayName("getSpan: no cache_break attribute → metadata is empty (not null) — FE can chain safely")
    void noBreakProducesEmptyMetadata() {
        LlmSpan span = brokenSpan("span-quiet-1", false, null);
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.readSpan(anyString())).thenReturn(Optional.of(span));
        BlobStore blobStore = mock(BlobStore.class);
        LlmSpanController controller = new LlmSpanController(
                traceStore, blobStore, new Semaphore(20), permissiveGuard());

        ResponseEntity<LlmSpanDetailDto> resp = controller.getSpan(span.spanId(), USER_ID);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().metadata())
                .as("metadata must be non-null even when no flags — keeps FE chaining clean")
                .isNotNull()
                .doesNotContainKey("cache_break");
    }

    @Test
    @DisplayName("getSpan: stale stringified \"true\" coerced to real boolean (defensive)")
    void stringifiedTrueIsCoerced() throws Exception {
        LlmSpan span = brokenSpanWithRawAttrs("span-str-1",
                Map.of("cache_break", "true", "cache_break_reason", "legacy_path"));
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.readSpan(anyString())).thenReturn(Optional.of(span));
        BlobStore blobStore = mock(BlobStore.class);
        LlmSpanController controller = new LlmSpanController(
                traceStore, blobStore, new Semaphore(20), permissiveGuard());

        ResponseEntity<LlmSpanDetailDto> resp = controller.getSpan(span.spanId(), USER_ID);

        assertThat(resp.getBody().metadata().get("cache_break")).isEqualTo(true);
    }

    // --- helpers ---

    private static LlmSpan brokenSpan(String spanId, boolean cacheBreak, String reason) {
        Map<String, Object> attrs = cacheBreak
                ? Map.of("cache_break", true, "cache_break_reason", reason)
                : Map.of();
        return brokenSpanWithRawAttrs(spanId, attrs);
    }

    private static LlmSpan brokenSpanWithRawAttrs(String spanId, Map<String, Object> attrs) {
        return new LlmSpan(
                spanId, "trace-x", null, SESSION_ID,
                1L, "claude", "claude-sonnet-4-20250514",
                0, true,
                null, null, null, null, null,
                "ok",
                10, 5, 100, null, null,
                BigDecimal.ZERO, 100L,
                Instant.parse("2026-05-07T10:00:00Z"),
                Instant.parse("2026-05-07T10:00:01Z"),
                "stop", null, null, null, null, null,
                attrs,
                LlmSpanSource.LIVE,
                "llm", null, null);
    }
}
