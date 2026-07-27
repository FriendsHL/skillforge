package com.skillforge.server.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ArkImageGenerationClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_ERROR_CHARS = 512;

    private final ArkImageProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    public ArkImageGenerationClient(ArkImageProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(properties.getTimeoutSeconds(), TimeUnit.SECONDS)
                .followRedirects(false)
                .build());
        requireConfigured();
        requireSubscriptionEndpoint();
    }

    ArkImageGenerationClient(ArkImageProperties properties, ObjectMapper objectMapper, OkHttpClient http) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    public GeneratedImage generate(String prompt, String size, boolean watermark) {
        return generate(prompt, size, watermark, null, null);
    }

    public GeneratedImage generate(String prompt, String size, boolean watermark,
                                   byte[] sourceImage, String sourceMimeType) {
        requireConfigured();
        requireSubscriptionEndpoint();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("prompt", prompt);
        payload.put("size", size == null ? null : size.toLowerCase(java.util.Locale.ROOT));
        payload.put("response_format", "url");
        payload.put("watermark", watermark);
        if (sourceImage != null) {
            if (sourceImage.length == 0) throw new IllegalArgumentException("Source image is empty");
            if (sourceMimeType == null || !sourceMimeType.startsWith("image/")) {
                throw new IllegalArgumentException("Source attachment is not an image");
            }
            String dataUrl = "data:" + sourceMimeType + ";base64,"
                    + Base64.getEncoder().encodeToString(sourceImage);
            payload.put("image", List.of(dataUrl));
        }
        Request request = new Request.Builder()
                .url(normalizeBaseUrl(properties.getBaseUrl()) + "/images/generations")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .post(RequestBody.create(writeJson(payload), JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String body = requireBody(response).string();
            JsonNode json = objectMapper.readTree(body);
            if (!response.isSuccessful()) throw providerError(response.code(), json);
            JsonNode first = json.path("data").path(0);
            String url = first.path("url").asText(null);
            String resultSize = first.path("size").asText(null);
            if (url == null || url.isBlank()) throw new IllegalStateException("Ark image response omitted result URL");
            return new GeneratedImage(url, resultSize, json.path("usage").path("output_tokens").asLong(0));
        } catch (IOException e) {
            throw new IllegalStateException("Ark image request failed", e);
        }
    }

    public void download(GeneratedImage image, Path target) {
        URI uri = URI.create(image.url());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || properties.getAllowedDownloadHosts().stream()
                .noneMatch(host -> host.equalsIgnoreCase(uri.getHost()))) {
            throw new IllegalStateException("Ark image result host is not allowed");
        }
        Request request = new Request.Builder().url(image.url()).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("Ark image download failed: HTTP " + response.code());
            ResponseBody body = requireBody(response);
            String contentType = body.contentType() == null ? "" : body.contentType().toString();
            if (!contentType.isBlank() && !contentType.startsWith("image/")) {
                throw new IllegalStateException("Ark result is not an image");
            }
            long declared = body.contentLength();
            if (declared > properties.getMaxDownloadBytes()) throw new IllegalStateException("Ark image exceeds download limit");
            Files.createDirectories(target.getParent());
            copyBounded(body.byteStream(), target, properties.getMaxDownloadBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Ark image download failed", e);
        }
    }

    private void copyBounded(InputStream input, Path target, long maxBytes) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied += read;
                if (copied > maxBytes) throw new IllegalStateException("Ark image exceeds download limit");
                output.write(buffer, 0, read);
            }
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private void requireConfigured() {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("Ark image generation is not configured");
        }
    }

    private void requireSubscriptionEndpoint() {
        URI uri = URI.create(normalizeBaseUrl(properties.getBaseUrl()));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"ark.cn-beijing.volces.com".equalsIgnoreCase(uri.getHost())
                || !"/api/plan/v3".equals(uri.getPath())) {
            throw new IllegalStateException("Ark image generation must use the subscription /api/plan/v3 endpoint");
        }
    }

    private IllegalStateException providerError(int status, JsonNode json) {
        String code = json.path("error").path("code").asText("UNKNOWN");
        String message = json.path("error").path("message").asText("request failed");
        if (message.length() > MAX_ERROR_CHARS) message = message.substring(0, MAX_ERROR_CHARS);
        return new IllegalStateException("Ark image generation failed: HTTP " + status + " " + code + " - " + message);
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalStateException("Failed to serialize Ark image request", e); }
    }

    private static ResponseBody requireBody(Response response) {
        ResponseBody body = response.body();
        if (body == null) throw new IllegalStateException("Ark response body is empty");
        return body;
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Ark image base URL is blank");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record GeneratedImage(String url, String size, long outputTokens) { }
}
