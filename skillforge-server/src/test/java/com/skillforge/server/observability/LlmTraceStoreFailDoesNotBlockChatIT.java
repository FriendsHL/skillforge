package com.skillforge.server.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.observer.LlmCallContext;
import com.skillforge.core.llm.observer.RawHttpRequest;
import com.skillforge.core.llm.observer.RawStreamCapture;
import com.skillforge.observability.api.BlobStore;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceWriteRequest;
import com.skillforge.observability.observer.TraceLlmCallObserver;
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
 * BE-W2 (R3) — server-side mirror of {@code LlmTraceStoreFailDoesNotBlockChatTest}.
 *
 * <p>The observability module already verifies the {@link TraceLlmCallObserver}
 * swallows persistence failures. This test re-asserts the contract from
 * {@code skillforge-server}'s perspective so a regression that breaks the chat
 * path's resilience to OBS-1 store failures fails the server build, not just the
 * observability build.
 *
 * <p>A full {@code @SpringBootTest} would also load the chat path; we instead
 * exercise the same observer beans directly because the server module does not
 * yet have a Spring Boot test harness configured (see BE-W1 follow-up). This
 * still surfaces regressions where {@link TraceLlmCallObserver}'s exception
 * handling is removed or weakened.
 */
@DisplayName("Server: TraceLlmCallObserver — LlmTraceStore failures must not block chat")
class LlmTraceStoreFailDoesNotBlockChatIT {

    private LlmTraceStore traceStore;
    private TraceLlmCallObserver observer;

    @BeforeEach
    void setUp() throws IOException {
        BlobStore blobStore = mock(BlobStore.class);
        when(blobStore.write(any(), any())).thenReturn(
                "20260429/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/"
                        + "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff-response.json");
        traceStore = mock(LlmTraceStore.class);

        ThreadPoolTaskExecutor syncExecutor = new ThreadPoolTaskExecutor() {
            @Override public void execute(Runnable task) { task.run(); }
            @Override public java.util.concurrent.Future<?> submit(Runnable task) {
                task.run();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        observer = new TraceLlmCallObserver(blobStore, traceStore, syncExecutor,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("SQLException from traceStore.write must not propagate to chat handler (onStreamComplete)")
    void onStreamCompleteSwallowsSqlException() {
        doThrow(new RuntimeException(new SQLException("connection refused")))
                .when(traceStore).write(any(LlmTraceWriteRequest.class));

        LlmCallContext ctx = ctx();
        observer.beforeCall(ctx, request());
        LlmResponse parsed = response();
        RawStreamCapture cap = new RawStreamCapture(new byte[0], new byte[0], false, 0L);

        assertThatCode(() -> observer.onStreamComplete(ctx, cap, parsed))
                .doesNotThrowAnyException();
        assertThat(observer.inFlightCount()).isZero();
    }

    @Test
    @DisplayName("SQLException from traceStore.write must not propagate to chat handler (onError)")
    void onErrorSwallowsSqlException() {
        doThrow(new RuntimeException(new SQLException("deadlock")))
                .when(traceStore).write(any(LlmTraceWriteRequest.class));

        LlmCallContext ctx = ctx();
        observer.beforeCall(ctx, request());
        Throwable err = new RuntimeException("simulated upstream LLM error");
        RawStreamCapture cap = new RawStreamCapture(new byte[0], new byte[0], false, 0L);

        assertThatCode(() -> observer.onError(ctx, err, cap)).doesNotThrowAnyException();
        assertThat(observer.inFlightCount()).isZero();
    }

    private static LlmCallContext ctx() {
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

    private static RawHttpRequest request() {
        return new RawHttpRequest("POST", "https://api.anthropic.com/v1/messages",
                java.util.Map.of("Content-Type", "application/json"),
                "{\"model\":\"claude\"}".getBytes(),
                "application/json");
    }

    private static LlmResponse response() {
        LlmResponse r = new LlmResponse();
        r.setContent("hello");
        return r;
    }
}
