package com.skillforge.observability.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.observer.LlmCallContext;
import com.skillforge.core.llm.observer.RawHttpRequest;
import com.skillforge.core.llm.observer.RawStreamCapture;
import com.skillforge.observability.api.BlobStore;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceWriteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan §9.1 / Judge R3 W-R3-N1 follow-up — verifies the chat path is not blocked
 * when {@link LlmTraceStore#write(LlmTraceWriteRequest)} throws (e.g. DB SQLException).
 *
 * <p>{@link TraceLlmCallObserver#onStreamComplete} delegates the persistence write to
 * an executor and wraps {@code traceStore.write} in {@code try/catch + log.warn}. The
 * contract: terminal hooks NEVER propagate persistence failures back to the caller
 * (would break the agent loop / chat handler downstream).
 *
 * <p>Test uses a synchronous executor so we can assert behavior in-line.
 */
@DisplayName("TraceLlmCallObserver — store failures must not break the chat handler")
class LlmTraceStoreFailDoesNotBlockChatTest {

    private BlobStore blobStore;
    private LlmTraceStore traceStore;
    private ThreadPoolTaskExecutor syncExecutor;
    private ObjectMapper objectMapper;
    private TraceLlmCallObserver observer;

    @BeforeEach
    void setUp() throws IOException {
        blobStore = mock(BlobStore.class);
        // Avoid actual blob writes interfering with the test — return a canonical-looking ref.
        when(blobStore.write(any(), any())).thenReturn(
                "20260429/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/"
                        + "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff-response.json");
        traceStore = mock(LlmTraceStore.class);

        // Synchronous executor: tasks run on the calling thread immediately.
        syncExecutor = new ThreadPoolTaskExecutor() {
            @Override public void execute(Runnable task) { task.run(); }
            @Override public java.util.concurrent.Future<?> submit(Runnable task) {
                task.run();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        objectMapper = new ObjectMapper().findAndRegisterModules();
        observer = new TraceLlmCallObserver(blobStore, traceStore, syncExecutor, objectMapper);
    }

    @Test
    @DisplayName("onStreamComplete: traceStore.write throws SQLException → observer swallows, does not propagate")
    void onStreamCompleteSwallowsSqlException() {
        // Arrange: store write blows up with SQLException (simulates upsertTrace failure).
        doThrow(new RuntimeException(new SQLException("connection refused")))
                .when(traceStore).write(any(LlmTraceWriteRequest.class));

        LlmCallContext ctx = baseCtx();
        // Mark in-flight via beforeCall so onStreamComplete has CallState.
        observer.beforeCall(ctx, sampleRequest());

        LlmResponse parsed = sampleResponse();
        RawStreamCapture cap = new RawStreamCapture(new byte[0], new byte[0], false, 0L);

        // Act + Assert: must not throw.
        assertThatCode(() -> observer.onStreamComplete(ctx, cap, parsed))
                .as("store failure must not propagate to caller")
                .doesNotThrowAnyException();

        // In-flight state must still be cleaned up (R2-W3 — no leak).
        assertThat(observer.inFlightCount())
                .as("CallState must be removed even when store.write throws")
                .isZero();
    }

    @Test
    @DisplayName("onError: traceStore.write throws SQLException → observer swallows, does not propagate")
    void onErrorSwallowsSqlException() {
        doThrow(new RuntimeException(new SQLException("deadlock")))
                .when(traceStore).write(any(LlmTraceWriteRequest.class));

        LlmCallContext ctx = baseCtx();
        observer.beforeCall(ctx, sampleRequest());

        Throwable err = new RuntimeException("simulated upstream LLM error");
        RawStreamCapture cap = new RawStreamCapture(new byte[0], new byte[0], false, 0L);

        assertThatCode(() -> observer.onError(ctx, err, cap))
                .doesNotThrowAnyException();

        assertThat(observer.inFlightCount()).isZero();
    }

    @Test
    @DisplayName("afterCall: traceStore.write throws SQLException → observer swallows")
    void afterCallSwallowsSqlException() {
        doThrow(new RuntimeException(new SQLException("connection lost")))
                .when(traceStore).write(any(LlmTraceWriteRequest.class));

        LlmCallContext ctx = baseCtx();
        observer.beforeCall(ctx, sampleRequest());

        assertThatCode(() -> observer.afterCall(ctx, null, sampleResponse()))
                .doesNotThrowAnyException();

        assertThat(observer.inFlightCount()).isZero();
    }

    // ---------- helpers ----------

    private static LlmCallContext baseCtx() {
        return LlmCallContext.builder()
                .traceId(UUID.randomUUID().toString())
                .spanId(UUID.randomUUID().toString())
                .sessionId(UUID.randomUUID().toString())
                .agentId(1L)
                .userId(42L)
                .providerName("claude")
                .modelId("claude-sonnet-4-20250514")
                .iterationIndex(0)
                .stream(true)
                .startedAt(Instant.now())
                .build();
    }

    private static RawHttpRequest sampleRequest() {
        return new RawHttpRequest(
                "POST",
                "https://api.anthropic.com/v1/messages",
                java.util.Map.of("Content-Type", "application/json"),
                "{\"model\":\"claude\"}".getBytes(),
                "application/json");
    }

    private static LlmResponse sampleResponse() {
        LlmResponse r = new LlmResponse();
        r.setContent("hello");
        return r;
    }
}
