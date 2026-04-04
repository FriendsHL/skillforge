package com.skillforge.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 API 的 LlmProvider 实现。
 * 支持 OpenAI、DeepSeek、通义千问、月之暗面等所有兼容 OpenAI Chat Completions 格式的 API。
 */
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiProvider(String apiKey, String baseUrl, String defaultModel) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.openai.com";
        this.defaultModel = defaultModel != null ? defaultModel : "gpt-4o";
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        try {
            String requestBody = buildRequestBody(request, model, false);
            log.debug("OpenAI chat request: model={}, messages={}", model, request.getMessages().size());

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "no body";
                    throw new RuntimeException("OpenAI API error: HTTP " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                return parseResponse(responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
    }

    @Override
    public void chatStream(LlmRequest request, LlmStreamHandler handler) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        try {
            String requestBody = buildRequestBody(request, model, true);
            log.debug("OpenAI stream request: model={}", model);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "no body";
                    handler.onError(new RuntimeException(
                            "OpenAI API error: HTTP " + response.code() + " - " + errorBody));
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

        // messages - OpenAI puts system prompt as first message
        ArrayNode messagesNode = root.putArray("messages");

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.getSystemPrompt());
            messagesNode.add(systemMsg);
        }

        convertMessages(request.getMessages(), messagesNode);

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
     */
    @SuppressWarnings("unchecked")
    private void convertMessages(List<Message> messages, ArrayNode messagesNode) {
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
                    for (Object obj : blocks) {
                        ObjectNode toolMsg = objectMapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        if (obj instanceof ContentBlock block) {
                            toolMsg.put("tool_call_id", block.getToolUseId());
                            toolMsg.put("content", block.getContent() != null ? block.getContent() : "");
                        } else if (obj instanceof Map<?, ?> map) {
                            toolMsg.put("tool_call_id", String.valueOf(map.get("tool_use_id")));
                            toolMsg.put("content", map.get("content") != null ? String.valueOf(map.get("content")) : "");
                        }
                        messagesNode.add(toolMsg);
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

                String textContent = msg.getTextContent();
                if (textContent != null && !textContent.isEmpty()) {
                    assistantMsg.put("content", textContent);
                }

                List<ToolUseBlock> toolUseBlocks = msg.getToolUseBlocks();
                if (!toolUseBlocks.isEmpty()) {
                    ArrayNode toolCallsNode = assistantMsg.putArray("tool_calls");
                    for (ToolUseBlock toolUse : toolUseBlocks) {
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
                simpleMsg.put("content", content instanceof String ? (String) content : msg.getTextContent());
                messagesNode.add(simpleMsg);
            }
        }
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

        log.debug("OpenAI response: stopReason={}, toolUseBlocks={}, textLength={}",
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
        List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
        String stopReason = "end_turn";

        // Track tool_calls being assembled incrementally (keyed by index)
        Map<Integer, String> toolCallIds = new HashMap<>();
        Map<Integer, String> toolCallNames = new HashMap<>();
        Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
                    fullResponse.setToolUseBlocks(toolUseBlocks);
                    fullResponse.setStopReason(stopReason);
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
                        if (!funcDelta.isMissingNode()) {
                            if (funcDelta.has("name") && funcDelta.path("name").isTextual()) {
                                toolCallNames.put(index, funcDelta.path("name").asText());
                            }
                            if (funcDelta.has("arguments") && funcDelta.path("arguments").isTextual()) {
                                toolCallArgs.computeIfAbsent(index, k -> new StringBuilder())
                                        .append(funcDelta.path("arguments").asText());
                            }
                        }
                    }
                }
            }
        }

        // If stream ended without [DONE], still finalize
        finalizeToolCalls(toolCallIds, toolCallNames, toolCallArgs, toolUseBlocks, handler);

        LlmResponse fullResponse = new LlmResponse();
        fullResponse.setContent(fullText.toString());
        fullResponse.setToolUseBlocks(toolUseBlocks);
        fullResponse.setStopReason(stopReason);
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
                log.warn("Failed to parse streamed tool arguments: {}", argsJson, e);
                input = Map.of();
            }

            ToolUseBlock block = new ToolUseBlock(id, name, input);
            toolUseBlocks.add(block);
            handler.onToolUse(block);
        }

        ids.clear();
        names.clear();
        args.clear();
    }
}
