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
import java.util.List;
import java.util.Map;

public class ArkVideoGenerationClient implements VideoGenerationProvider {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ArkVideoProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    public ArkVideoGenerationClient(ArkVideoProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(false).build());
        requireConfigured();
    }

    ArkVideoGenerationClient(ArkVideoProperties properties, ObjectMapper objectMapper, OkHttpClient http) {
        this.properties = properties; this.objectMapper = objectMapper; this.http = http;
    }

    @Override public String name() { return "ark"; }

    @Override
    public SubmittedVideo submit(VideoRequest input) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("content", List.of(Map.of("type", "text", "text", input.prompt())));
        body.put("duration", input.durationSeconds());
        body.put("resolution", input.resolution());
        body.put("ratio", input.ratio());
        body.put("generate_audio", input.generateAudio());
        JsonNode json = execute(new Request.Builder().url(endpoint("/contents/generations/tasks"))
                .header("Authorization", bearer()).post(RequestBody.create(write(body), JSON)).build());
        String id = json.path("id").asText(null);
        if (id == null || id.isBlank()) throw new IllegalStateException("Ark video response omitted task id");
        return new SubmittedVideo(id, json.path("request_id").asText(null));
    }

    @Override
    public VideoStatus get(String providerJobId) {
        JsonNode json = execute(new Request.Builder().url(endpoint("/contents/generations/tasks/" + providerJobId))
                .header("Authorization", bearer()).get().build());
        JsonNode error = json.path("error");
        return new VideoStatus(json.path("status").asText("unknown"),
                json.path("content").path("video_url").asText(null),
                error.path("code").asText(null), error.path("message").asText(null));
    }

    @Override public void cancel(String id) {
        execute(new Request.Builder().url(endpoint("/contents/generations/tasks/" + id))
                .header("Authorization", bearer()).delete().build());
    }

    @Override
    public void download(String value, Path target) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || properties.getAllowedDownloadHosts().stream().noneMatch(h -> h.equalsIgnoreCase(uri.getHost()))) {
            throw new IllegalStateException("Ark video result host is not allowed");
        }
        Request request = new Request.Builder().url(value).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("Ark video download failed: HTTP " + response.code());
            ResponseBody body = requireBody(response);
            String mime = body.contentType() == null ? "" : body.contentType().toString();
            if (!mime.isBlank() && !mime.startsWith("video/")) throw new IllegalStateException("Ark result is not a video");
            Files.createDirectories(target.getParent());
            copyBounded(body.byteStream(), target, properties.getMaxDownloadBytes());
        } catch (IOException e) { throw new IllegalStateException("Ark video download failed", e); }
    }

    private JsonNode execute(Request request) {
        try (Response response = http.newCall(request).execute()) {
            JsonNode json = objectMapper.readTree(requireBody(response).string());
            if (!response.isSuccessful()) {
                String code = json.path("error").path("code").asText("UNKNOWN");
                throw new IllegalStateException("Ark video request failed: HTTP " + response.code() + " " + code);
            }
            return json;
        } catch (IOException e) { throw new IllegalStateException("Ark video request failed", e); }
    }

    private void requireConfigured() {
        URI uri = URI.create(normalize(properties.getBaseUrl()));
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank())
            throw new IllegalStateException("Ark video generation is not configured");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"ark.cn-beijing.volces.com".equalsIgnoreCase(uri.getHost())
                || !"/api/plan/v3".equals(uri.getPath()))
            throw new IllegalStateException("Ark video generation must use the subscription /api/plan/v3 endpoint");
    }
    private String endpoint(String path) { requireConfigured(); return normalize(properties.getBaseUrl()) + path; }
    private String bearer() { return "Bearer " + properties.getApiKey(); }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (IOException e) { throw new IllegalStateException(e); } }
    private static ResponseBody requireBody(Response response) { if (response.body() == null) throw new IllegalStateException("Ark response body is empty"); return response.body(); }
    private static String normalize(String value) { if (value == null || value.isBlank()) throw new IllegalStateException("Ark video base URL is blank"); return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private static void copyBounded(InputStream in, Path target, long max) throws IOException {
        long copied = 0; byte[] buffer = new byte[8192];
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            int read; while ((read = in.read(buffer)) >= 0) { copied += read; if (copied > max) throw new IllegalStateException("Ark video exceeds download limit"); out.write(buffer, 0, read); }
        } catch (IOException | RuntimeException e) { Files.deleteIfExists(target); throw e; }
    }
}
