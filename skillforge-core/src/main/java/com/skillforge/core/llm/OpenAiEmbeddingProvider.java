package com.skillforge.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible embedding provider.
 * Works with: OpenAI, DeepSeek, DashScope, Moonshot, and any API compatible with /v1/embeddings.
 */
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int dimension;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiEmbeddingProvider(String apiKey, String baseUrl, String model, int dimension) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.dimension = dimension;
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", List.of(text)
            ));
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new EmbeddingNotSupportedException(
                            "HTTP " + response.code() + " from embedding API");
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    throw new EmbeddingNotSupportedException("Empty response body from embedding API");
                }
                JsonNode root = objectMapper.readTree(responseBody.string());
                JsonNode vector = root.path("data").path(0).path("embedding");
                float[] result = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    result[i] = (float) vector.get(i).asDouble();
                }
                return result;
            }
        } catch (EmbeddingNotSupportedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Embedding request failed: {}", e.getMessage());
            throw new EmbeddingNotSupportedException("Embedding request failed");
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
