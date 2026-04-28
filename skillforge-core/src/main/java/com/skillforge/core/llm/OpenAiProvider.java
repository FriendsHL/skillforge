package com.skillforge.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ReasoningEffort;
import com.skillforge.core.model.ThinkingMode;
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
 * OpenAI 兼容 API 的 LlmProvider 实现。
 * 支持 OpenAI、DeepSeek、通义千问、月之暗面等所有兼容 OpenAI Chat Completions 格式的 API。
 */
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    /** BUG-E-bis: chatStream handshake-phase retry count (ConnectException / SSLHandshakeException only). */
    private static final int STREAM_MAX_HANDSHAKE_RETRIES = 2;
    /** Backoff base (ms) for attempt 1 and attempt 2; jitter ±20% applied on top. */
    private static final long[] STREAM_HANDSHAKE_BACKOFF_MS = {2000L, 5000L};

    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    /** User-visible provider name ("bailian", "deepseek", …) for logs and error strings. */
    private final String providerDisplayName;
    private final int maxRetries;
    /** Non-stream path (chat): readTimeout bounds total response time. */
    private final OkHttpClient httpClient;
    /**
     * BUG-E: Stream path (chatStream): dedicated client so readTimeout is scoped to
     * inter-chunk idle rather than shared with non-stream total-response semantics.
     * Currently configured identically to {@link #httpClient}; kept separate so either
     * timeout can be tuned independently in future.
     */
    private final OkHttpClient streamHttpClient;
    private final ObjectMapper objectMapper;

    /** Legacy 3-arg ctor — kept as a deprecated shim; prefer the 7-arg ctor below. */
    @Deprecated
    public OpenAiProvider(String apiKey, String baseUrl, String defaultModel) {
        this(apiKey, baseUrl, defaultModel, "openai", null,
                ModelConfig.DEFAULT_READ_TIMEOUT_SECONDS,
                ModelConfig.DEFAULT_MAX_RETRIES);
    }

    /** Legacy 5-arg ctor — kept as a deprecated shim; prefer the 7-arg ctor below. */
    @Deprecated
    public OpenAiProvider(String apiKey, String baseUrl, String defaultModel,
                          int readTimeoutSeconds, int maxRetries) {
        this(apiKey, baseUrl, defaultModel, "openai", null, readTimeoutSeconds, maxRetries);
    }

    /**
     * Canonical constructor.
     *
     * @param apiKey              upstream API key; blank / null fails fast with a diagnostic
     * @param baseUrl             provider base URL (e.g. https://api.deepseek.com)
     * @param defaultModel        fallback model when {@link LlmRequest#getModel()} is null
     * @param providerDisplayName user-visible provider name used in logs / error messages
     *                            ("bailian", "deepseek", "openai", …)
     * @param envVarName          hint for the env var that carries the key (for error messages);
     *                            may be null, then a generic hint is emitted
     * @param readTimeoutSeconds  OkHttp read timeout
     * @param maxRetries          non-stream SocketTimeout retries
     */
    public OpenAiProvider(String apiKey, String baseUrl, String defaultModel,
                          String providerDisplayName, String envVarName,
                          int readTimeoutSeconds, int maxRetries) {
        if (providerDisplayName == null || providerDisplayName.isBlank()) {
            throw new IllegalStateException("providerDisplayName must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            String envHint = (envVarName != null && !envVarName.isBlank())
                    ? "the " + envVarName
                    : "the provider-specific API key";
            throw new IllegalStateException(
                    "LLM provider '" + providerDisplayName + "' is missing its API key — "
                            + "set " + envHint + " environment variable and restart.");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.openai.com";
        this.defaultModel = defaultModel != null ? defaultModel : "gpt-4o";
        this.providerDisplayName = providerDisplayName;
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
        return providerDisplayName;
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
                            providerDisplayName + " API read timeout after " + (attempt + 1) + " attempt(s)", ste);
                }
                attempt++;
                log.warn("{} read timeout, retrying {}/{}", providerDisplayName, attempt, maxRetries);
            } catch (IOException e) {
                throw new RuntimeException("Failed to call " + providerDisplayName + " API", e);
            }
        }
    }

    private LlmResponse doChat(LlmRequest request) throws IOException {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        String requestBody = buildRequestBody(request, model, false);
        log.debug("{} chat request: model={}, messages={}", providerDisplayName, model, request.getMessages().size());

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                throw new RuntimeException(providerDisplayName + " API error: HTTP " + response.code() + " - " + errorBody);
            }
            String responseBody = response.body().string();
            return parseResponse(responseBody);
        }
    }

    @Override
    public void chatStream(LlmRequest request, LlmStreamHandler handler) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        String requestBody;
        try {
            requestBody = buildRequestBody(request, model, true);
        } catch (Exception e) {
            handler.onError(e);
            return;
        }
        log.debug("{} stream request: model={}", providerDisplayName, model);

        // BUG-E / BUG-E-bis: handshake-phase retry loop.
        // Only Call.execute() synchronous failures on ConnectException /
        // SSLHandshakeException trigger a retry. Once execute() returns a Response, any
        // body-read error is delivered once to onError — retrying post-handshake would
        // duplicate already-delivered deltas (project footgun #3).
        int handshakeAttempt = 0;
        while (true) {
            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                    .build();
            Call call = streamHttpClient.newCall(httpRequest);
            handler.onStreamStart(call::cancel);
            if (handler.isCancelled()) {
                handler.onStreamStart(null);
                handler.onError(new IOException("stream cancelled before handshake"));
                return;
            }

            Response response;
            try {
                response = call.execute();  // handshake barrier
            } catch (ConnectException | SSLHandshakeException preHandshake) {
                handler.onStreamStart(null);
                if (handshakeAttempt >= STREAM_MAX_HANDSHAKE_RETRIES || handler.isCancelled()) {
                    handler.onError(preHandshake);
                    return;
                }
                long base = STREAM_HANDSHAKE_BACKOFF_MS[handshakeAttempt];
                double jitterFactor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 0.4 - 0.2);
                long sleepMs = Math.max(100L, (long) (base * jitterFactor));
                log.warn("{} chatStream pre-handshake {} (attempt {}/{}), retrying in {}ms: {}",
                        providerDisplayName,
                        preHandshake.getClass().getSimpleName(), handshakeAttempt + 1,
                        STREAM_MAX_HANDSHAKE_RETRIES, sleepMs, preHandshake.getMessage());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    handler.onError(ie);
                    return;
                }
                handshakeAttempt++;
                continue;
            } catch (Exception other) {
                // SocketTimeoutException / UnknownHostException / any other IOException → no retry
                handler.onStreamStart(null);
                if (handler.isCancelled()) {
                    log.debug("OpenAI stream cancelled during handshake");
                }
                handler.onError(other);
                return;
            }

            // Handshake succeeded: from here on, no retry under any circumstance.
            try (Response r = response) {
                if (!r.isSuccessful()) {
                    String errorBody = r.body() != null ? r.body().string() : "no body";
                    handler.onError(new RuntimeException(
                            providerDisplayName + " API error: HTTP " + r.code() + " - " + errorBody));
                    return;
                }
                processSSEStream(r, handler);
            } catch (Exception postHandshake) {
                if (handler.isCancelled()) {
                    log.debug("OpenAI stream cancelled after handshake");
                }
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
            // 必须显式开启 include_usage,OpenAI 兼容流式协议才会在最后一个 chunk 里带 usage
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            root.set("stream_options", streamOptions);
        }

        // Resolve the protocol family from the request's actual model — a single provider
        // instance can serve multiple model families (e.g. bailian hosts qwen + glm-5).
        ProviderProtocolFamily family = ProviderProtocolFamilyResolver.resolve(model);

        // --- Thinking-mode toggle (top-level; extra_body is silently dropped by qwen / deepseek). ---
        ThinkingMode mode = request.getThinkingMode();
        if (mode != null && mode != ThinkingMode.AUTO) {
            if (family.supportsThinkingToggle) {
                switch (family.thinkingFieldDialect) {
                    case QWEN_ENABLE_THINKING ->
                            root.put("enable_thinking", mode == ThinkingMode.ENABLED);
                    case DEEPSEEK_V4_THINKING -> {
                        ObjectNode thinking = objectMapper.createObjectNode();
                        thinking.put("type", mode == ThinkingMode.ENABLED ? "enabled" : "disabled");
                        root.set("thinking", thinking);
                    }
                    case NONE -> { /* unreachable when supportsThinkingToggle=true */ }
                }
            } else {
                // Operators asked for a toggle on a family that ignores it — log once so it's
                // visible without being noisy (debug).
                log.debug("thinkingMode={} requested for model '{}' (family {}); ignored (family does not support toggle)",
                        mode, model, family);
            }
        } else if (family.thinkingFieldDialect == ProviderProtocolFamily.ThinkingFieldDialect.QWEN_ENABLE_THINKING) {
            // Qwen on DashScope defaults to thinking ON when the field is omitted, which causes the
            // agent loop to receive only reasoning_content with empty content (and SessionTitleService
            // to render thinking text as the title). Explicitly default to off; users that want thinking
            // must set ThinkingMode.ENABLED on the agent.
            root.put("enable_thinking", false);
        }

        // --- reasoning_effort (top-level OpenAI standard; accepted by deepseek-v4 + o1/o3). ---
        ReasoningEffort effort = request.getReasoningEffort();
        if (effort != null && family.supportsReasoningEffort) {
            root.put("reasoning_effort", effort.wireValue());
        }

        // messages - OpenAI puts system prompt as first message
        ArrayNode messagesNode = root.putArray("messages");

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.getSystemPrompt());
            messagesNode.add(systemMsg);
        }

        convertMessages(request.getMessages(), messagesNode, family);

        // tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (ToolSchema tool : request.getTools()) {
                ObjectNode toolWrapper = objectMapper.createObjectNode();
                toolWrapper.put("type", "function");
                ObjectNode funcNode = objectMapper.createObjectNode();
                funcNode.put("name", tool.getName());
                funcNode.put("description", tool.getDescription());
                funcNode.set("parameters", objectMapper.valueToTree(tool.getInputSchema()));
                toolWrapper.set("function", funcNode);
                toolsNode.add(toolWrapper);
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 将 SkillForge 内部 Message 列表转换为 OpenAI messages 格式。
     * {@code family} drives per-family {@code reasoning_content} replay rules (see
     * {@link #resolveReplayReasoningContent} and plan §4.3).
     */
    @SuppressWarnings("unchecked")
    private void convertMessages(List<Message> messages, ArrayNode messagesNode,
                                 ProviderProtocolFamily family) {
        for (Message msg : messages) {
            Object content = msg.getContent();
            Message.Role role = msg.getRole();

            if (role == Message.Role.USER && content instanceof List<?> blocks) {
                // 可能包含 tool_result 块，需拆分为多条 role=tool 消息
                boolean hasToolResult = false;
                for (Object obj : blocks) {
                    if (obj instanceof ContentBlock block && "tool_result".equals(block.getType())) {
                        hasToolResult = true;
                        break;
                    } else if (obj instanceof Map<?, ?> map && "tool_result".equals(map.get("type"))) {
                        hasToolResult = true;
                        break;
                    }
                }

                if (hasToolResult) {
                    // BUG-F-3 defensive type filter: only emit role:tool for actual
                    // tool_result blocks. text / image / tool_use / etc. blocks that
                    // got mixed into a tool_result-form user message (e.g. acbced3f
                    // legacy data produced by the deleted mergeSummaryIntoUser path)
                    // are silently dropped — without this filter they would be emitted
                    // as role:tool with tool_call_id="null", which DeepSeek rejects
                    // with HTTP 400 "Duplicate value for 'tool_call_id'".
                    List<String> invalidToolResults = new ArrayList<>();
                    Set<String> emittedToolResultIds = new HashSet<>();
                    for (Object obj : blocks) {
                        boolean isToolResult;
                        if (obj instanceof ContentBlock cb) {
                            isToolResult = "tool_result".equals(cb.getType());
                        } else if (obj instanceof Map<?, ?> map) {
                            isToolResult = "tool_result".equals(map.get("type"));
                        } else {
                            isToolResult = false;
                        }
                        if (!isToolResult) {
                            continue;
                        }
                        String toolUseId = toolResultId(obj);
                        if (!isUsableToolCallId(toolUseId) || !emittedToolResultIds.add(toolUseId)) {
                            invalidToolResults.add(toolResultAsText(obj));
                            continue;
                        }
                        ObjectNode toolMsg = objectMapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        if (obj instanceof ContentBlock block) {
                            toolMsg.put("tool_call_id", toolUseId);
                            toolMsg.put("content", block.getContent() != null ? block.getContent() : "");
                        } else if (obj instanceof Map<?, ?> map) {
                            toolMsg.put("tool_call_id", toolUseId);
                            toolMsg.put("content", map.get("content") != null ? String.valueOf(map.get("content")) : "");
                        }
                        messagesNode.add(toolMsg);
                    }
                    if (!invalidToolResults.isEmpty()) {
                        ObjectNode userMsg = objectMapper.createObjectNode();
                        userMsg.put("role", "user");
                        userMsg.put("content", "[Tool result replayed as text because its tool_call_id was missing or duplicated]\n"
                                + String.join("\n\n", invalidToolResults));
                        messagesNode.add(userMsg);
                    }
                } else {
                    // Regular user message with content blocks - extract text
                    ObjectNode userMsg = objectMapper.createObjectNode();
                    userMsg.put("role", "user");
                    userMsg.put("content", msg.getTextContent());
                    messagesNode.add(userMsg);
                }

            } else if (role == Message.Role.ASSISTANT && content instanceof List<?> blocks) {
                // Assistant message with possible tool_use blocks
                ObjectNode assistantMsg = objectMapper.createObjectNode();
                assistantMsg.put("role", "assistant");

                // Must compute tool-call presence BEFORE the reasoning_content emit decision,
                // because the helper needs hasToolCalls (plan reviewer W3).
                List<ToolUseBlock> toolUseBlocks = msg.getToolUseBlocks();
                List<ToolUseBlock> validToolUseBlocks = validToolUseBlocks(toolUseBlocks);
                boolean hasToolCalls = !validToolUseBlocks.isEmpty();

                // Emit reasoning_content per family-specific rules (plan D5 / §4.3).
                String replayReasoning = resolveReplayReasoningContent(msg, role, hasToolCalls, family);
                if (replayReasoning != null) {
                    assistantMsg.put("reasoning_content", replayReasoning);
                }

                String textContent = msg.getTextContent();
                if (textContent != null && !textContent.isEmpty()) {
                    assistantMsg.put("content", textContent);
                } else if (!hasToolCalls) {
                    assistantMsg.put("content", "");
                }

                if (hasToolCalls) {
                    ArrayNode toolCallsNode = assistantMsg.putArray("tool_calls");
                    for (ToolUseBlock toolUse : validToolUseBlocks) {
                        ObjectNode callNode = objectMapper.createObjectNode();
                        callNode.put("id", toolUse.getId());
                        callNode.put("type", "function");
                        ObjectNode funcNode = objectMapper.createObjectNode();
                        funcNode.put("name", toolUse.getName());
                        try {
                            funcNode.put("arguments", objectMapper.writeValueAsString(toolUse.getInput()));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            throw new RuntimeException("Failed to serialize tool input", e);
                        }
                        callNode.set("function", funcNode);
                        toolCallsNode.add(callNode);
                    }
                }

                messagesNode.add(assistantMsg);

            } else {
                // Simple text message
                ObjectNode simpleMsg = objectMapper.createObjectNode();
                String roleStr = role == Message.Role.ASSISTANT ? "assistant" : "user";
                simpleMsg.put("role", roleStr);
                // Simple-text branch has no tool_calls (hasToolCalls=false). Helper already
                // role-gates emission (only ASSISTANT).
                String replayReasoning = resolveReplayReasoningContent(msg, role, false, family);
                if (replayReasoning != null) {
                    simpleMsg.put("reasoning_content", replayReasoning);
                }
                simpleMsg.put("content", content instanceof String ? (String) content : msg.getTextContent());
                messagesNode.add(simpleMsg);
            }
        }
    }

    private List<ToolUseBlock> validToolUseBlocks(List<ToolUseBlock> toolUseBlocks) {
        if (toolUseBlocks == null || toolUseBlocks.isEmpty()) {
            return List.of();
        }
        List<ToolUseBlock> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ToolUseBlock block : toolUseBlocks) {
            if (block == null || !isUsableToolCallId(block.getId()) || !seen.add(block.getId())) {
                continue;
            }
            out.add(block);
        }
        return out;
    }

    private static boolean isUsableToolCallId(String id) {
        return id != null && !id.isBlank() && !"null".equalsIgnoreCase(id);
    }

    private static String toolResultId(Object obj) {
        if (obj instanceof ContentBlock block) {
            return block.getToolUseId();
        }
        if (obj instanceof Map<?, ?> map) {
            Object snake = map.get("tool_use_id");
            if (snake != null) {
                return snake.toString();
            }
            Object camel = map.get("toolUseId");
            return camel != null ? camel.toString() : null;
        }
        return null;
    }

    private static String toolResultAsText(Object obj) {
        String id = toolResultId(obj);
        String content = "";
        if (obj instanceof ContentBlock block) {
            content = block.getContent() != null ? block.getContent() : "";
        } else if (obj instanceof Map<?, ?> map) {
            Object raw = map.get("content");
            content = raw != null ? raw.toString() : "";
        }
        String label = isUsableToolCallId(id) ? id : "<missing>";
        return "tool_call_id=" + label + "\n" + content;
    }

    /**
     * Decide whether to emit {@code reasoning_content} on an assistant message being
     * replayed. Returns the exact string to write (possibly {@code ""}), or {@code null}
     * when the field should be omitted.
     *
     * <p>Design goal: preserve V22 shipped behavior (emit stored reasoning_content when
     * non-empty for any assistant message) on every family EXCEPT
     * {@link ProviderProtocolFamily#DEEPSEEK_REASONER_LEGACY}, where per DeepSeek docs the
     * field must be dropped. {@code DEEPSEEK_REASONER_LEGACY} is not present in
     * {@code FALLBACK_MODEL_OPTIONS}; the drop is doc-based, not live-tested (plan reviewer
     * W2 acknowledged).</p>
     *
     * <p>New rule (plan D5 / Step 0 re-verification): when a tool-call assistant is being
     * replayed to a family that requires replay ({@code DEEPSEEK_V4} or
     * {@code QWEN_DASHSCOPE}) and stored {@code reasoning_content} is null/empty, emit
     * {@code ""} as fallback — deepseek-v4 returns HTTP 400 "must be passed back" otherwise.</p>
     *
     * @param msg           the message being serialised
     * @param role          explicit role to gate emission (only ASSISTANT emits; user messages
     *                      that happen to carry reasoning_content are never forwarded)
     * @param hasToolCalls  whether the assistant message contains tool_calls
     * @param family        protocol family of the target model
     */
    private String resolveReplayReasoningContent(Message msg, Message.Role role,
                                                 boolean hasToolCalls,
                                                 ProviderProtocolFamily family) {
        // Role guard (reviewer W4): only ASSISTANT messages may ever emit reasoning_content.
        if (role != Message.Role.ASSISTANT) {
            return null;
        }
        // (a) Legacy R1-style: reasoning_content must be dropped on replay, always.
        if (family.dropsReasoningContentOnReplay) {
            return null;
        }

        String stored = msg.getReasoningContent();
        boolean storedPresent = stored != null && !stored.isEmpty();

        if (storedPresent) {
            // V22 path preserved: emit stored content whenever present.
            return stored;
        }

        // (b) Empty/null stored. Only emit "" fallback when the assistant message has
        //     tool_calls AND the family rejects omission on replay (DEEPSEEK_V4 proven 400;
        //     QWEN_DASHSCOPE is tolerant but we emit "" for symmetry and future-proofing).
        if (hasToolCalls && family.requiresReasoningContentReplay) {
            return "";
        }

        // Otherwise omit the field (identical to V22 null/empty branch).
        return null;
    }

    // ----- Response parsing -----

    private LlmResponse parseResponse(String responseBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        LlmResponse llmResponse = new LlmResponse();

        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.path("message");

            // text content
            String content = message.path("content").asText(null);
            llmResponse.setContent(content != null ? content : "");

            // reasoning_content (DeepSeek / Qwen thinking mode)
            String reasoningContent = message.path("reasoning_content").asText(null);
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                llmResponse.setReasoningContent(reasoningContent);
            }

            // finish_reason -> stopReason mapping
            String finishReason = firstChoice.path("finish_reason").asText("stop");
            llmResponse.setStopReason(mapFinishReason(finishReason));

            // tool_calls
            List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    String id = call.path("id").asText();
                    JsonNode function = call.path("function");
                    String name = function.path("name").asText();
                    String argumentsJson = function.path("arguments").asText("{}");
                    Map<String, Object> input;
                    try {
                        input = objectMapper.readValue(argumentsJson, Map.class);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse tool arguments: {}", argumentsJson, e);
                        input = Map.of();
                    }
                    toolUseBlocks.add(new ToolUseBlock(id, name, input));
                }
            }
            llmResponse.setToolUseBlocks(toolUseBlocks);
        }

        // usage
        JsonNode usageNode = root.path("usage");
        if (!usageNode.isMissingNode()) {
            LlmResponse.Usage usage = new LlmResponse.Usage(
                    usageNode.path("prompt_tokens").asInt(0),
                    usageNode.path("completion_tokens").asInt(0)
            );
            llmResponse.setUsage(usage);
        }

        log.debug("{} response: stopReason={}, toolUseBlocks={}, textLength={}",
                providerDisplayName,
                llmResponse.getStopReason(), llmResponse.getToolUseBlocks().size(),
                llmResponse.getContent().length());

        return llmResponse;
    }

    /**
     * 将 OpenAI finish_reason 映射为 SkillForge 内部的 stopReason。
     */
    private String mapFinishReason(String finishReason) {
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            default -> finishReason;
        };
    }

    // ----- SSE stream processing -----

    @SuppressWarnings("unchecked")
    private void processSSEStream(Response response, LlmStreamHandler handler) throws IOException {
        StringBuilder fullText = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();
        List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
        String stopReason = "end_turn";
        LlmResponse.Usage capturedUsage = null;

        // Track tool_calls being assembled incrementally (keyed by index)
        Map<Integer, String> toolCallIds = new HashMap<>();
        Map<Integer, String> toolCallNames = new HashMap<>();
        Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();
        // 记录每个 index 是否已经对 handler 发过 onToolUseStart(id + name 都已知)
        Set<Integer> startedIndices = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (handler.isCancelled()) {
                    log.debug("OpenAI SSE loop cancelled between lines");
                    break;
                }
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring(6).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    // Finalize any pending tool calls
                    finalizeToolCalls(toolCallIds, toolCallNames, toolCallArgs, toolUseBlocks, handler);

                    LlmResponse fullResponse = new LlmResponse();
                    fullResponse.setContent(fullText.toString());
                    if (!fullReasoning.isEmpty()) {
                        fullResponse.setReasoningContent(fullReasoning.toString());
                    }
                    fullResponse.setToolUseBlocks(toolUseBlocks);
                    fullResponse.setStopReason(stopReason);
                    fullResponse.setUsage(capturedUsage);
                    handler.onComplete(fullResponse);
                    return;
                }

                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse SSE data: {}", data, e);
                    continue;
                }

                // 开启 stream_options.include_usage 后,最后一个 chunk 会带 usage 字段且 choices 为空。
                // 必须在 choices 为空判断之前提取,否则会被下面的 continue 跳掉。
                JsonNode usageNode = event.path("usage");
                if (usageNode.isObject()) {
                    capturedUsage = new LlmResponse.Usage(
                            usageNode.path("prompt_tokens").asInt(0),
                            usageNode.path("completion_tokens").asInt(0)
                    );
                }

                JsonNode choices = event.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }

                JsonNode firstChoice = choices.get(0);
                JsonNode delta = firstChoice.path("delta");

                // finish_reason
                JsonNode finishReasonNode = firstChoice.path("finish_reason");
                if (!finishReasonNode.isNull() && finishReasonNode.isTextual()) {
                    stopReason = mapFinishReason(finishReasonNode.asText());
                }

                // text content delta
                if (delta.has("content") && !delta.path("content").isNull()) {
                    String text = delta.path("content").asText();
                    fullText.append(text);
                    handler.onText(text);
                }
                // reasoning_content delta (Qwen 3.5+ / DeepSeek thinking mode)
                // Stream to frontend and accumulate for round-trip back to the API.
                if (delta.has("reasoning_content") && !delta.path("reasoning_content").isNull()) {
                    String reasoning = delta.path("reasoning_content").asText();
                    if (reasoning != null && !reasoning.isEmpty()) {
                        fullReasoning.append(reasoning);
                        handler.onText(reasoning);
                    }
                }

                // tool_calls delta (incremental)
                JsonNode toolCallsDelta = delta.path("tool_calls");
                if (toolCallsDelta.isArray()) {
                    for (JsonNode callDelta : toolCallsDelta) {
                        int index = callDelta.path("index").asInt(0);

                        // id is present only in the first chunk for this index
                        if (callDelta.has("id") && callDelta.path("id").isTextual()) {
                            toolCallIds.put(index, callDelta.path("id").asText());
                        }

                        JsonNode funcDelta = callDelta.path("function");
                        String argsFragment = null;
                        if (!funcDelta.isMissingNode()) {
                            if (funcDelta.has("name") && funcDelta.path("name").isTextual()) {
                                toolCallNames.put(index, funcDelta.path("name").asText());
                            }
                            if (funcDelta.has("arguments") && funcDelta.path("arguments").isTextual()) {
                                argsFragment = funcDelta.path("arguments").asText();
                                toolCallArgs.computeIfAbsent(index, k -> new StringBuilder())
                                        .append(argsFragment);
                            }
                        }

                        // 一旦该 index 的 id + name 同时就绪, 触发 onToolUseStart(只触发一次)
                        if (!startedIndices.contains(index)
                                && toolCallIds.containsKey(index)
                                && toolCallNames.containsKey(index)) {
                            handler.onToolUseStart(toolCallIds.get(index), toolCallNames.get(index));
                            startedIndices.add(index);
                        }
                        // 向 handler 透传 JSON 片段(必须已经发过 onToolUseStart)
                        if (argsFragment != null && !argsFragment.isEmpty()
                                && startedIndices.contains(index)) {
                            handler.onToolUseInputDelta(toolCallIds.get(index), argsFragment);
                        }
                    }
                }
            }
        }

        // Cancel 时不能把 partial response 当完整结果交出去(会导致 orphan tool_use)
        if (handler.isCancelled()) {
            handler.onError(new java.io.IOException("stream cancelled"));
            return;
        }

        // If stream ended without [DONE], still finalize
        finalizeToolCalls(toolCallIds, toolCallNames, toolCallArgs, toolUseBlocks, handler);

        LlmResponse fullResponse = new LlmResponse();
        fullResponse.setContent(fullText.toString());
        if (!fullReasoning.isEmpty()) {
            fullResponse.setReasoningContent(fullReasoning.toString());
        }
        fullResponse.setToolUseBlocks(toolUseBlocks);
        fullResponse.setStopReason(stopReason);
        fullResponse.setUsage(capturedUsage);
        handler.onComplete(fullResponse);
    }

    /**
     * 将增量累积的 tool_calls 数据组装为 ToolUseBlock 并通知 handler。
     */
    @SuppressWarnings("unchecked")
    private void finalizeToolCalls(
            Map<Integer, String> ids,
            Map<Integer, String> names,
            Map<Integer, StringBuilder> args,
            List<ToolUseBlock> toolUseBlocks,
            LlmStreamHandler handler) {
        if (ids.isEmpty()) {
            return;
        }

        List<Integer> sortedIndices = new ArrayList<>(ids.keySet());
        Collections.sort(sortedIndices);

        for (int index : sortedIndices) {
            String id = ids.get(index);
            String name = names.getOrDefault(index, "");
            String argsJson = args.containsKey(index) ? args.get(index).toString().trim() : "{}";

            Map<String, Object> input;
            try {
                input = objectMapper.readValue(argsJson.isEmpty() ? "{}" : argsJson, Map.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse streamed tool arguments (truncated / malformed): toolUseId={}, rawLen={}",
                        id, argsJson.length(), e);
                input = Map.of();
                // BUG-D: surface the truncation event via handler.onWarning so the engine
                // can attach it to the LLM_CALL trace span. Without this the Map.of()
                // fallback is silent and downstream sees a valid-but-empty tool_use.
                handler.onWarning("warning.tool_input_truncated", Boolean.TRUE);
                handler.onWarning("warning.tool_use_id", id != null ? id : "");
                handler.onWarning("warning.tool_name", name != null ? name : "");
                String preview = argsJson.length() > 200
                        ? argsJson.substring(0, 200) + "..." : argsJson;
                handler.onWarning("warning.raw_args_preview", preview);
            }

            ToolUseBlock block = new ToolUseBlock(id, name, input);
            toolUseBlocks.add(block);
            handler.onToolUseEnd(id, input);
            handler.onToolUse(block);
        }

        ids.clear();
        names.clear();
        args.clear();
    }
}
