package com.skillforge.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Claude (Anthropic Messages API) 的 LlmProvider 实现。
 */
public class ClaudeProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProvider.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    private final int maxRetries;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClaudeProvider(String apiKey, String baseUrl, String defaultModel) {
        this(apiKey, baseUrl, defaultModel,
                ModelConfig.DEFAULT_READ_TIMEOUT_SECONDS,
                ModelConfig.DEFAULT_MAX_RETRIES);
    }

    public ClaudeProvider(String apiKey, String baseUrl, String defaultModel,
                          int readTimeoutSeconds, int maxRetries) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.anthropic.com";
        this.defaultModel = defaultModel != null ? defaultModel : "claude-sonnet-4-20250514";
        this.maxRetries = Math.max(0, maxRetries);
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "claude";
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
                throw new RuntimeException("Claude API error: HTTP " + response.code() + " - " + errorBody);
            }
            String responseBody = response.body().string();
            return parseResponse(responseBody);
        }
    }

    @Override
    public void chatStream(LlmRequest request, LlmStreamHandler handler) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        try {
            String requestBody = buildRequestBody(request, model, true);
            log.debug("Claude stream request: model={}", model);

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
                    handler.onError(new RuntimeException(
                            "Claude API error: HTTP " + response.code() + " - " + errorBody));
                    return;
                }

                processSSEStream(response, handler);
            }
        } catch (Exception e) {
            handler.onError(e);
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

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
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

        // Build and deliver the full response
        LlmResponse fullResponse = new LlmResponse();
        fullResponse.setContent(fullText.toString());
        fullResponse.setToolUseBlocks(toolUseBlocks);
        fullResponse.setStopReason(stopReason);
        fullResponse.setUsage(usage);

        handler.onComplete(fullResponse);
    }
}
