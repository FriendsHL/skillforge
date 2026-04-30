package com.skillforge.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.llm.observer.LlmCallContext;
import com.skillforge.core.llm.observer.LlmCallObserverRegistry;
import com.skillforge.core.llm.observer.RawHttpRequest;
import com.skillforge.core.llm.observer.RawHttpResponse;
import com.skillforge.core.llm.observer.RawStreamCapture;
import com.skillforge.core.llm.observer.SafeObservers;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Claude (Anthropic Messages API) 的 LlmProvider 实现。
 */
public class ClaudeProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProvider.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** BUG-E-bis: chatStream handshake-phase retry count. */
    private static final int STREAM_MAX_HANDSHAKE_RETRIES = 2;
    /** Backoff base (ms); jitter ±20% applied on top. */
    private static final long[] STREAM_HANDSHAKE_BACKOFF_MS = {2000L, 5000L};

    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    private final int maxRetries;
    /** Non-stream path (chat): readTimeout bounds total response time. */
    private final OkHttpClient httpClient;
    /**
     * BUG-E: Stream path (chatStream). See {@link OpenAiProvider} for rationale.
     */
    private final OkHttpClient streamHttpClient;
    private final ObjectMapper objectMapper;
    /** OBS-1: optional observer registry; defaults to NO_OP — providers can be created
     *  without server-side wiring (e.g. tests) with zero overhead. */
    private LlmCallObserverRegistry observerRegistry = LlmCallObserverRegistry.NO_OP;
    /** CTX-1: per-provider compact thresholds; defaults to historical 0.60/0.80/0.85. */
    private CompactThresholds compactThresholds = CompactThresholds.DEFAULTS;

    public ClaudeProvider(String apiKey, String baseUrl, String defaultModel) {
        this(apiKey, baseUrl, defaultModel,
                ModelConfig.DEFAULT_READ_TIMEOUT_SECONDS,
                ModelConfig.DEFAULT_MAX_RETRIES);
    }

    public ClaudeProvider(String apiKey, String baseUrl, String defaultModel,
                          int readTimeoutSeconds, int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "LLM provider 'claude' is missing its API key — "
                            + "set the ANTHROPIC_API_KEY environment variable and restart.");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.anthropic.com";
        this.defaultModel = defaultModel != null ? defaultModel : "claude-sonnet-4-20250514";
        this.maxRetries = Math.max(0, maxRetries);
        this.objectMapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.streamHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "claude";
    }

    /** OBS-1: setter injection for observer registry; null → NO_OP. */
    public void setObserverRegistry(LlmCallObserverRegistry registry) {
        this.observerRegistry = registry == null ? LlmCallObserverRegistry.NO_OP : registry;
    }

    /** CTX-1: setter for compact thresholds; null → fall back to {@link CompactThresholds#DEFAULTS}. */
    public void setCompactThresholds(CompactThresholds thresholds) {
        this.compactThresholds = thresholds == null ? CompactThresholds.DEFAULTS : thresholds;
    }

    @Override
    public CompactThresholds getCompactThresholds() {
        return compactThresholds;
    }

    /**
     * Detect Anthropic's "prompt is too long" error inside an HTTP error body.
     * Claude flags it as {@code error.type == "invalid_request_error"} with the
     * message containing "prompt is too long" (or, occasionally,
     * "input is too long" on some compatibility wrappers).
     *
     * <p>Package-private for unit-test access.
     *
     * @return parsed {@link LlmContextLengthExceededException} if recognised; {@code null} otherwise
     */
    static LlmContextLengthExceededException detectContextOverflow(ObjectMapper mapper,
                                                                     int httpStatus,
                                                                     String errorBody) {
        if (errorBody == null || errorBody.isEmpty()) return null;
        // 400 invalid_request_error is the documented status; some proxies use 413 — accept both.
        try {
            JsonNode root = mapper.readTree(errorBody);
            JsonNode err = root.path("error");
            if (err.isMissingNode() || err.isNull()) {
                err = root; // some proxies return the error object at top level
            }
            String type = err.path("type").asText("");
            String message = err.path("message").asText("");
            String lowerMsg = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
            if ("invalid_request_error".equals(type)
                    && (lowerMsg.contains("prompt is too long") || lowerMsg.contains("input is too long"))) {
                return new LlmContextLengthExceededException(
                        "Claude context overflow: HTTP " + httpStatus + " - " + message);
            }
        } catch (Exception parseFail) {
            // Body wasn't JSON — fall through; will not auto-retry.
            log.debug("Claude error body not parseable as JSON, skip overflow detection: {}",
                    parseFail.getMessage());
        }
        return null;
    }

    @Override
    public LlmResponse chat(LlmRequest request, LlmCallContext ctx) {
        // OBS-1 §4.3 non-stream lifecycle: beforeCall fires once OUTSIDE the
        // SocketTimeoutException retry loop; afterCall on terminal success; onError on
        // terminal failure (retry exhausted / non-timeout IOException).
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        byte[] reqBody;
        try {
            reqBody = buildRequestBody(request, model, false).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            SafeObservers.notifyError(observerRegistry, ctx, e, null);
            throw new RuntimeException("Failed to build Claude request body", e);
        }
        RawHttpRequest snap = new RawHttpRequest(
                "POST", baseUrl + "/v1/messages",
                Map.of("anthropic-version", ANTHROPIC_VERSION, "content-type", "application/json"),
                reqBody, "application/json");
        SafeObservers.notifyBefore(observerRegistry, ctx, snap);

        int attempt = 0;
        while (true) {
            try {
                LlmResponse parsed = doChatBody(reqBody, model);
                RawHttpResponse respSnap = new RawHttpResponse(200, Map.of(),
                        new byte[0], "application/json");
                SafeObservers.notifyAfter(observerRegistry, ctx, respSnap, parsed);
                return parsed;
            } catch (SocketTimeoutException ste) {
                if (attempt >= maxRetries) {
                    RuntimeException re = new RuntimeException(
                            "Claude API read timeout after " + (attempt + 1) + " attempt(s)", ste);
                    SafeObservers.notifyError(observerRegistry, ctx, re, null);
                    throw re;
                }
                attempt++;
                log.warn("Claude read timeout, retrying {}/{}", attempt, maxRetries);
            } catch (IOException e) {
                RuntimeException re = new RuntimeException("Failed to call Claude API", e);
                SafeObservers.notifyError(observerRegistry, ctx, re, null);
                throw re;
            }
        }
    }

    /** Internal: execute a single non-stream attempt with a pre-built body. */
    private LlmResponse doChatBody(byte[] requestBody, String model) throws IOException {
        log.debug("Claude chat request: model={}", model);
        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("content-type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                LlmContextLengthExceededException overflow =
                        detectContextOverflow(objectMapper, response.code(), errorBody);
                if (overflow != null) {
                    throw overflow;
                }
                throw new RuntimeException("Claude API error: HTTP " + response.code() + " - " + errorBody);
            }
            String responseBody = response.body().string();
            return parseResponse(responseBody);
        }
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        // Retry ONLY on SocketTimeoutException. Non-timeout IO / HTTP errors fail fast.
        // Streaming path (chatStream) is single-attempt on purpose — retrying mid-stream
        // would duplicate already-delivered deltas to the handler.
        int attempt = 0;
        while (true) {
            try {
                return doChat(request);
            } catch (SocketTimeoutException ste) {
                if (attempt >= maxRetries) {
                    throw new RuntimeException(
                            "Claude API read timeout after " + (attempt + 1) + " attempt(s)", ste);
                }
                attempt++;
                log.warn("Claude read timeout, retrying {}/{}", attempt, maxRetries);
            } catch (IOException e) {
                throw new RuntimeException("Failed to call Claude API", e);
            }
        }
    }

    private LlmResponse doChat(LlmRequest request) throws IOException {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        String requestBody = buildRequestBody(request, model, false);
        log.debug("Claude chat request: model={}, messages={}", model, request.getMessages().size());

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("content-type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                LlmContextLengthExceededException overflow =
                        detectContextOverflow(objectMapper, response.code(), errorBody);
                if (overflow != null) {
                    throw overflow;
                }
                throw new RuntimeException("Claude API error: HTTP " + response.code() + " - " + errorBody);
            }
            String responseBody = response.body().string();
            return parseResponse(responseBody);
        }
    }

    @Override
    public void chatStream(LlmRequest request, LlmStreamHandler handler) {
        chatStream(request, LlmCallContext.empty(), handler);
    }

    @Override
    public void chatStream(LlmRequest request, LlmCallContext ctx, LlmStreamHandler handler) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        String requestBody;
        try {
            requestBody = buildRequestBody(request, model, true);
        } catch (Exception e) {
            SafeObservers.notifyError(observerRegistry, ctx, e, null);
            handler.onError(e);
            return;
        }
        log.debug("Claude stream request: model={}", model);

        // OBS-1 §4.2: beforeCall fires once OUTSIDE the handshake retry loop.
        byte[] requestBodyBytes = requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RawHttpRequest reqSnap = new RawHttpRequest(
                "POST", baseUrl + "/v1/messages",
                Map.of("anthropic-version", ANTHROPIC_VERSION, "content-type", "application/json"),
                requestBodyBytes, "application/json");
        SafeObservers.notifyBefore(observerRegistry, ctx, reqSnap);

        // BUG-E / BUG-E-bis: handshake-phase retry loop. See OpenAiProvider.chatStream for details.
        // OBS-1 §4.2 invariant: observer hooks NOT called during handshake retry; only at terminal.
        int handshakeAttempt = 0;
        while (true) {
            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .addHeader("content-type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                    .build();
            Call call = streamHttpClient.newCall(httpRequest);
            handler.onStreamStart(call::cancel);
            if (handler.isCancelled()) {
                handler.onStreamStart(null);
                IOException cancelEx = new IOException("stream cancelled before handshake");
                SafeObservers.notifyError(observerRegistry, ctx, cancelEx, null);
                handler.onError(cancelEx);
                return;
            }

            Response response;
            try {
                response = call.execute();  // handshake barrier
            } catch (ConnectException | SSLHandshakeException preHandshake) {
                handler.onStreamStart(null);
                if (handshakeAttempt >= STREAM_MAX_HANDSHAKE_RETRIES || handler.isCancelled()) {
                    SafeObservers.notifyError(observerRegistry, ctx, preHandshake, null);
                    handler.onError(preHandshake);
                    return;
                }
                long base = STREAM_HANDSHAKE_BACKOFF_MS[handshakeAttempt];
                double jitterFactor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 0.4 - 0.2);
                long sleepMs = Math.max(100L, (long) (base * jitterFactor));
                log.warn("Claude chatStream pre-handshake {} (attempt {}/{}), retrying in {}ms: {}",
                        preHandshake.getClass().getSimpleName(), handshakeAttempt + 1,
                        STREAM_MAX_HANDSHAKE_RETRIES, sleepMs, preHandshake.getMessage());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    SafeObservers.notifyError(observerRegistry, ctx, ie, null);
                    handler.onError(ie);
                    return;
                }
                handshakeAttempt++;
                continue;
            } catch (Exception other) {
                handler.onStreamStart(null);
                if (handler.isCancelled()) {
                    log.debug("Claude stream cancelled during handshake");
                }
                SafeObservers.notifyError(observerRegistry, ctx, other, null);
                handler.onError(other);
                return;
            }

            // Handshake succeeded — set up SSE buf for observer capture (5 MB cap).
            ObservedStreamHandler observed = new ObservedStreamHandler(handler, ctx, observerRegistry, objectMapper);
            try (Response r = response) {
                if (!r.isSuccessful()) {
                    String errorBody = r.body() != null ? r.body().string() : "no body";
                    LlmContextLengthExceededException overflow =
                            detectContextOverflow(objectMapper, r.code(), errorBody);
                    RuntimeException ex = overflow != null
                            ? overflow
                            : new RuntimeException(
                                    "Claude API error: HTTP " + r.code() + " - " + errorBody);
                    SafeObservers.notifyError(observerRegistry, ctx, ex, null);
                    handler.onError(ex);
                    return;
                }
                processSSEStream(r, observed);
            } catch (Exception postHandshake) {
                if (handler.isCancelled()) {
                    log.debug("Claude stream cancelled after handshake");
                }
                SafeObservers.notifyError(observerRegistry, ctx,
                        postHandshake, observed.snapshot());
                handler.onError(postHandshake);
            } finally {
                handler.onStreamStart(null);
            }
            return;
        }
    }

    // ----- Request building -----

    private String buildRequestBody(LlmRequest request, String model, boolean stream)
            throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.getMaxTokens());
        root.put("temperature", request.getTemperature());

        if (stream) {
            root.put("stream", true);
        }

        // system prompt
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            root.put("system", request.getSystemPrompt());
        }

        // messages
        ArrayNode messagesNode = root.putArray("messages");
        for (Message msg : request.getMessages()) {
            ObjectNode msgNode = objectMapper.valueToTree(msg);
            messagesNode.add(msgNode);
        }

        // tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (ToolSchema tool : request.getTools()) {
                ObjectNode toolNode = objectMapper.createObjectNode();
                toolNode.put("name", tool.getName());
                toolNode.put("description", tool.getDescription());
                toolNode.set("input_schema", objectMapper.valueToTree(tool.getInputSchema()));
                toolsNode.add(toolNode);
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    // ----- Response parsing -----

    private LlmResponse parseResponse(String responseBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);

        LlmResponse llmResponse = new LlmResponse();

        // stop_reason
        llmResponse.setStopReason(root.path("stop_reason").asText("end_turn"));

        // usage
        JsonNode usageNode = root.path("usage");
        if (!usageNode.isMissingNode()) {
            LlmResponse.Usage usage = new LlmResponse.Usage(
                    usageNode.path("input_tokens").asInt(0),
                    usageNode.path("output_tokens").asInt(0)
            );
            llmResponse.setUsage(usage);
        }

        // content blocks
        StringBuilder textBuilder = new StringBuilder();
        List<ToolUseBlock> toolUseBlocks = new ArrayList<>();

        JsonNode contentArray = root.path("content");
        if (contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    textBuilder.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    String id = block.path("id").asText();
                    String name = block.path("name").asText();
                    Map<String, Object> input = objectMapper.convertValue(
                            block.path("input"), Map.class);
                    toolUseBlocks.add(new ToolUseBlock(id, name, input));
                }
            }
        }

        llmResponse.setContent(textBuilder.toString());
        llmResponse.setToolUseBlocks(toolUseBlocks);

        log.debug("Claude response: stopReason={}, toolUseBlocks={}, textLength={}",
                llmResponse.getStopReason(), toolUseBlocks.size(), llmResponse.getContent().length());

        return llmResponse;
    }

    // ----- SSE stream processing -----

    private void processSSEStream(Response response, LlmStreamHandler handler) throws IOException {
        StringBuilder fullText = new StringBuilder();
        List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
        String stopReason = "end_turn";
        LlmResponse.Usage usage = null;

        // Track current tool_use block being assembled
        String currentToolId = null;
        String currentToolName = null;
        StringBuilder currentToolInputJson = new StringBuilder();

        ObservedStreamHandler observed = handler instanceof ObservedStreamHandler osh ? osh : null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (handler.isCancelled()) {
                    log.debug("Claude SSE loop cancelled between lines");
                    break;
                }
                if (observed != null) observed.appendSseLine(line);
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }

                    JsonNode event;
                    try {
                        event = objectMapper.readTree(data);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse SSE data: {}", data, e);
                        continue;
                    }

                    String eventType = event.path("type").asText();

                    switch (eventType) {
                        case "content_block_start" -> {
                            JsonNode contentBlock = event.path("content_block");
                            String blockType = contentBlock.path("type").asText();
                            if ("tool_use".equals(blockType)) {
                                currentToolId = contentBlock.path("id").asText();
                                currentToolName = contentBlock.path("name").asText();
                                currentToolInputJson.setLength(0);
                                handler.onToolUseStart(currentToolId, currentToolName);
                            }
                        }

                        case "content_block_delta" -> {
                            JsonNode delta = event.path("delta");
                            String deltaType = delta.path("type").asText();
                            if ("text_delta".equals(deltaType)) {
                                String text = delta.path("text").asText();
                                fullText.append(text);
                                handler.onText(text);
                            } else if ("input_json_delta".equals(deltaType)) {
                                String partialJson = delta.path("partial_json").asText();
                                currentToolInputJson.append(partialJson);
                                if (currentToolId != null) {
                                    handler.onToolUseInputDelta(currentToolId, partialJson);
                                }
                            }
                        }

                        case "content_block_stop" -> {
                            if (currentToolId != null) {
                                Map<String, Object> input = Map.of();
                                String jsonStr = currentToolInputJson.toString().trim();
                                if (!jsonStr.isEmpty()) {
                                    try {
                                        input = objectMapper.readValue(jsonStr, Map.class);
                                    } catch (JsonProcessingException e) {
                                        log.warn("Failed to parse tool input JSON: {}", jsonStr, e);
                                    }
                                }
                                ToolUseBlock block = new ToolUseBlock(currentToolId, currentToolName, input);
                                toolUseBlocks.add(block);
                                handler.onToolUseEnd(currentToolId, input);
                                handler.onToolUse(block);

                                currentToolId = null;
                                currentToolName = null;
                                currentToolInputJson.setLength(0);
                            }
                        }

                        case "message_delta" -> {
                            JsonNode delta = event.path("delta");
                            if (delta.has("stop_reason")) {
                                stopReason = delta.path("stop_reason").asText("end_turn");
                            }
                            JsonNode usageNode = event.path("usage");
                            if (!usageNode.isMissingNode()) {
                                usage = new LlmResponse.Usage(
                                        usageNode.path("input_tokens").asInt(0),
                                        usageNode.path("output_tokens").asInt(0)
                                );
                            }
                        }

                        case "message_start" -> {
                            JsonNode message = event.path("message");
                            JsonNode usageNode = message.path("usage");
                            if (!usageNode.isMissingNode()) {
                                usage = new LlmResponse.Usage(
                                        usageNode.path("input_tokens").asInt(0),
                                        usageNode.path("output_tokens").asInt(0)
                                );
                            }
                        }

                        case "message_stop" -> {
                            // stream complete, will build full response below
                        }

                        default -> log.trace("Ignoring SSE event type: {}", eventType);
                    }
                }
                // ignore lines that don't start with "data: " (e.g. "event:" lines, empty lines)
            }
        }

        // Cancel 时不能把 partial response 当完整结果交出去(会导致 orphan tool_use)
        if (handler.isCancelled()) {
            handler.onError(new java.io.IOException("stream cancelled"));
            return;
        }

        // Build and deliver the full response
        LlmResponse fullResponse = new LlmResponse();
        fullResponse.setContent(fullText.toString());
        fullResponse.setToolUseBlocks(toolUseBlocks);
        fullResponse.setStopReason(stopReason);
        fullResponse.setUsage(usage);

        handler.onComplete(fullResponse);
    }

    /**
     * OBS-1: decorator that captures SSE bytes (5 MB cap) and fires terminal observer
     * hooks ({@code onStreamComplete} or {@code onError}) once. Per plan §4.2, observer
     * is exposed only at handshake terminal — handshake retries do not call hooks.
     */
    public static final class ObservedStreamHandler implements LlmStreamHandler {
        private static final long SSE_BUF_CAP_BYTES = 5L * 1024L * 1024L;

        private final LlmStreamHandler inner;
        private final LlmCallContext ctx;
        private final LlmCallObserverRegistry registry;
        private final ObjectMapper objectMapper;
        private final java.io.ByteArrayOutputStream sseBuf = new java.io.ByteArrayOutputStream();
        private boolean sseTruncated;
        private long sseByteCount;
        private boolean terminalFired;

        /**
         * Preferred constructor — accepts the provider's pre-configured ObjectMapper so we
         * avoid {@code new ObjectMapper()} on every onComplete (footgun #1: missing JavaTimeModule)
         * and the per-call CPU/memory cost of repeated module discovery.
         */
        public ObservedStreamHandler(LlmStreamHandler inner, LlmCallContext ctx,
                                     LlmCallObserverRegistry registry, ObjectMapper objectMapper) {
            this.inner = inner;
            this.ctx = ctx;
            this.registry = registry == null ? LlmCallObserverRegistry.NO_OP : registry;
            this.objectMapper = objectMapper != null ? objectMapper
                    : new ObjectMapper().findAndRegisterModules()
                            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        /** Legacy constructor retained for backwards-compat tests; prefer the 4-arg form. */
        public ObservedStreamHandler(LlmStreamHandler inner, LlmCallContext ctx,
                                     LlmCallObserverRegistry registry) {
            this(inner, ctx, registry, null);
        }

        public void appendSseLine(String line) {
            if (line == null) return;
            byte[] bytes = (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sseByteCount += bytes.length;
            if (sseTruncated) return;
            if (sseBuf.size() + bytes.length > SSE_BUF_CAP_BYTES) {
                sseTruncated = true;
                int remaining = (int) Math.max(0, SSE_BUF_CAP_BYTES - sseBuf.size());
                if (remaining > 0) sseBuf.write(bytes, 0, Math.min(remaining, bytes.length));
                return;
            }
            sseBuf.write(bytes, 0, bytes.length);
            // OBS-1 §4.2: onStreamChunk fires only for "data: ..." lines (the SSE payload),
            // not for "event:" / blank lines (kept in raw SSE buf for replay fidelity).
            if (line.startsWith("data: ")) {
                SafeObservers.notifyStreamChunk(registry, ctx, line.substring(6));
            }
        }

        public RawStreamCapture snapshot() {
            return new RawStreamCapture(sseBuf.toByteArray(), new byte[0],
                    sseTruncated, sseByteCount);
        }

        @Override public void onStreamStart(Runnable cancelAction) { inner.onStreamStart(cancelAction); }
        @Override public boolean isCancelled() { return inner.isCancelled(); }
        @Override public void onText(String text) { inner.onText(text); }
        @Override public void onToolUseStart(String toolUseId, String name) { inner.onToolUseStart(toolUseId, name); }
        @Override public void onToolUseInputDelta(String toolUseId, String jsonFragment) {
            inner.onToolUseInputDelta(toolUseId, jsonFragment);
        }
        @Override public void onToolUseEnd(String toolUseId, Map<String, Object> parsedInput) {
            inner.onToolUseEnd(toolUseId, parsedInput);
        }
        @Override public void onToolUse(ToolUseBlock block) { inner.onToolUse(block); }
        @Override public void onWarning(String key, Object value) { inner.onWarning(key, value); }

        @Override
        public void onComplete(LlmResponse fullResponse) {
            if (!terminalFired) {
                terminalFired = true;
                byte[] accumulatedJson = new byte[0];
                try {
                    // Best-effort: serialize the parsed response so blob can be replayed.
                    // BE-W1: reuse the provider's ObjectMapper (footgun #1 + CPU cost) instead
                    // of constructing a fresh one per onComplete.
                    accumulatedJson = objectMapper.writeValueAsBytes(fullResponse);
                } catch (Exception ignore) { /* leave empty */ }
                RawStreamCapture cap = new RawStreamCapture(sseBuf.toByteArray(),
                        accumulatedJson, sseTruncated, sseByteCount);
                SafeObservers.notifyStreamComplete(registry, ctx, cap, fullResponse);
            }
            inner.onComplete(fullResponse);
        }

        @Override
        public void onError(Throwable error) {
            if (!terminalFired) {
                terminalFired = true;
                SafeObservers.notifyError(registry, ctx, error, snapshot());
            }
            inner.onError(error);
        }
    }
}
