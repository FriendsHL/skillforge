package com.skillforge.server.controller.observability;

import com.skillforge.observability.api.BlobStore;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-B1 regression — verify {@link LlmSpanController#getBlob} releases the
 * blobReadSemaphore permit on every non-streaming exit path (404 / bad ref / exception).
 *
 * <p>Repro: pre-fix, calling {@code GET /spans/{id}/blob} 100 times against a span that
 * doesn't exist (or returns no input stream) leaks all 20 permits → permanent 429.
 */
@DisplayName("LlmSpanController — blobReadSemaphore must not leak on early-return paths")
class LlmSpanControllerSemaphoreLeakTest {

    private static final int PERMITS = 20;
    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-id-1";

    /**
     * Builds an ownership guard that always passes — these tests focus on permit
     * accounting, not authorisation. Auth coverage lives in {@code SessionSpansAuthIT}.
     */
    private static ObservabilityOwnershipGuard permissiveGuard() {
        SessionService sessionService = mock(SessionService.class);
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        when(sessionService.getSession(anyString())).thenReturn(session);
        return new ObservabilityOwnershipGuard(sessionService);
    }

    @Test
    @DisplayName("100 × 404 (span not found) does not leak permits")
    void spanNotFoundDoesNotLeakPermits() {
        Semaphore sem = new Semaphore(PERMITS);
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.readSpan(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        BlobStore blobStore = mock(BlobStore.class);
        LlmSpanController controller = new LlmSpanController(traceStore, blobStore, sem, permissiveGuard());

        for (int i = 0; i < 100; i++) {
            controller.getBlob("missing-span-" + i, USER_ID, "request");
        }

        assertThat(sem.availablePermits())
                .as("All permits must be released after 404 early returns")
                .isEqualTo(PERMITS);
    }

    @Test
    @DisplayName("100 × 404 (blob ref missing) does not leak permits")
    void nullBlobRefDoesNotLeakPermits() {
        Semaphore sem = new Semaphore(PERMITS);
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.readSpan(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(spanWithRef(null, null, null)));
        BlobStore blobStore = mock(BlobStore.class);
        LlmSpanController controller = new LlmSpanController(traceStore, blobStore, sem, permissiveGuard());

        for (int i = 0; i < 100; i++) {
            controller.getBlob("span-" + i, USER_ID, "request");
        }

        assertThat(sem.availablePermits()).isEqualTo(PERMITS);
    }

    @Test
    @DisplayName("100 × 404 (blob ref not canonical) does not leak permits")
    void nonCanonicalRefDoesNotLeakPermits() {
        Semaphore sem = new Semaphore(PERMITS);
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.readSpan(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(spanWithRef("../../etc/passwd", null, null)));
        BlobStore blobStore = mock(BlobStore.class);
        LlmSpanController controller = new LlmSpanController(traceStore, blobStore, sem, permissiveGuard());

        for (int i = 0; i < 100; i++) {
            controller.getBlob("span-" + i, USER_ID, "request");
        }

        assertThat(sem.availablePermits()).isEqualTo(PERMITS);
    }

    @Test
    @DisplayName("100 × 404 (openStream returns empty) does not leak permits")
    void openStreamEmptyDoesNotLeakPermits() throws Exception {
        Semaphore sem = new Semaphore(PERMITS);
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        // Use a canonical-looking ref so we get past the BLOB_PATH_PATTERN gate.
        String ref = "20260101/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/aaaaaaaa-bbbb-cccc-dddd-ffffffffffff-request.json";
        when(traceStore.readSpan(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(spanWithRef(ref, null, null)));
        BlobStore blobStore = mock(BlobStore.class);
        when(blobStore.openStream(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        LlmSpanController controller = new LlmSpanController(traceStore, blobStore, sem, permissiveGuard());

        for (int i = 0; i < 100; i++) {
            controller.getBlob("span-" + i, USER_ID, "request");
        }

        assertThat(sem.availablePermits()).isEqualTo(PERMITS);
    }

    @Test
    @DisplayName("Exception in traceStore.readSpan does not leak permits")
    void exceptionDuringReadDoesNotLeakPermits() {
        Semaphore sem = new Semaphore(PERMITS);
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        when(traceStore.readSpan(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("simulated failure"));
        BlobStore blobStore = mock(BlobStore.class);
        LlmSpanController controller = new LlmSpanController(traceStore, blobStore, sem, permissiveGuard());

        for (int i = 0; i < 100; i++) {
            controller.getBlob("span-" + i, USER_ID, "request");
        }

        assertThat(sem.availablePermits()).isEqualTo(PERMITS);
    }

    @Test
    @DisplayName("Bad part name (non-tryAcquire path) does not consume permits")
    void invalidPartReturnsBadRequestWithoutAcquiring() {
        Semaphore sem = new Semaphore(PERMITS);
        LlmTraceStore traceStore = mock(LlmTraceStore.class);
        BlobStore blobStore = mock(BlobStore.class);
        LlmSpanController controller = new LlmSpanController(traceStore, blobStore, sem, permissiveGuard());

        for (int i = 0; i < 50; i++) {
            controller.getBlob("span-" + i, USER_ID, "INVALID");
        }

        assertThat(sem.availablePermits()).isEqualTo(PERMITS);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static com.skillforge.observability.domain.LlmSpan spanWithRef(
            String inputRef, String outputRef, String sseRef) {
        return new com.skillforge.observability.domain.LlmSpan(
                "span-id-" + System.nanoTime(),
                "trace-id-1",
                null,
                SESSION_ID,
                1L,
                "claude",
                "claude-sonnet-4-20250514",
                0,
                true,
                null, null,
                inputRef, outputRef, sseRef,
                "ok",
                0, 0, null,
                null, null, 0L,
                java.time.Instant.now(),
                java.time.Instant.now(),
                null, null, null, null, null, null,
                Collections.emptyMap(),
                com.skillforge.observability.domain.LlmSpanSource.LIVE);
    }

    @SuppressWarnings("unused")
    private static List<String> dummyList() {
        return List.of();
    }
}
