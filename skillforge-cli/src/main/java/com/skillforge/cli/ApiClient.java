package com.skillforge.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin OkHttp wrapper around the SkillForge server REST API.
 *
 * Holds base URL + userId and returns parsed JsonNode trees so commands can
 * decide how to render. Throws {@link ApiException} for any non-2xx response.
 */
public class ApiClient {

    public static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    public static final MediaType YAML_TYPE = MediaType.parse("application/yaml; charset=utf-8");

    private final String baseUrl;
    private final long userId;
    private final OkHttpClient http;
    private final ObjectMapper json;
    private final boolean verbose;

    public ApiClient(String baseUrl, long userId, boolean verbose) {
        this.baseUrl = baseUrl;
        this.userId = userId;
        this.verbose = verbose;
        this.json = YamlMapper.json();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public String baseUrl() { return baseUrl; }
    public long userId() { return userId; }

    // ---------- Agents ----------

    public JsonNode listAgents() throws IOException {
        return getJson(url("/api/agents").build());
    }

    public JsonNode getAgent(long id) throws IOException {
        return getJson(url("/api/agents/" + id).build());
    }

    public JsonNode importAgentYaml(String yamlBody) throws IOException {
        Request req = new Request.Builder()
                .url(url("/api/agents/import").build())
                .post(RequestBody.create(yamlBody, YAML_TYPE))
                .build();
        return execJson(req);
    }

    public String exportAgentYaml(long id) throws IOException {
        Request req = new Request.Builder()
                .url(url("/api/agents/" + id + "/export").build())
                .get()
                .build();
        return execText(req);
    }

    public void deleteAgent(long id) throws IOException {
        Request req = new Request.Builder()
                .url(url("/api/agents/" + id).build())
                .delete()
                .build();
        execText(req);
    }

    // ---------- Sessions ----------

    public JsonNode listSessions(long uid) throws IOException {
        return getJson(url("/api/chat/sessions").addQueryParameter("userId", String.valueOf(uid)).build());
    }

    public JsonNode getSession(String id) throws IOException {
        return getJson(url("/api/chat/sessions/" + id)
                .addQueryParameter("userId", String.valueOf(userId)).build());
    }

    public JsonNode getSessionMessages(String id) throws IOException {
        return getJson(url("/api/chat/sessions/" + id + "/messages")
                .addQueryParameter("userId", String.valueOf(userId)).build());
    }

    public JsonNode createSession(long agentId) throws IOException {
        Map<String, Object> body = Map.of("userId", userId, "agentId", agentId);
        Request req = new Request.Builder()
                .url(url("/api/chat/sessions").build())
                .post(jsonBody(body))
                .build();
        return execJson(req);
    }

    public JsonNode sendMessage(String sessionId, String text) throws IOException {
        Map<String, Object> body = Map.of("userId", userId, "message", text);
        Request req = new Request.Builder()
                .url(url("/api/chat/" + sessionId).build())
                .post(jsonBody(body))
                .build();
        return execJson(req);
    }

    public JsonNode cancelSession(String sessionId) throws IOException {
        Request req = new Request.Builder()
                .url(url("/api/chat/" + sessionId + "/cancel")
                        .addQueryParameter("userId", String.valueOf(userId)).build())
                .post(RequestBody.create(new byte[0]))
                .build();
        return execJson(req);
    }

    public JsonNode setSessionMode(String sessionId, String mode) throws IOException {
        Map<String, Object> body = Map.of("mode", mode);
        Request req = new Request.Builder()
                .url(url("/api/chat/sessions/" + sessionId + "/mode")
                        .addQueryParameter("userId", String.valueOf(userId)).build())
                .patch(jsonBody(body))
                .build();
        return execJson(req);
    }

    // ---------- Skills ----------

    public JsonNode listSkills() throws IOException {
        return getJson(url("/api/skills").build());
    }

    public JsonNode listBuiltinSkills() throws IOException {
        return getJson(url("/api/skills/builtin").build());
    }

    // ---------- Compact ----------

    public JsonNode compact(String sessionId, String level, String reason) throws IOException {
        Map<String, Object> body = reason == null
                ? Map.of("level", level)
                : Map.of("level", level, "reason", reason);
        Request req = new Request.Builder()
                .url(url("/api/chat/sessions/" + sessionId + "/compact")
                        .addQueryParameter("userId", String.valueOf(userId)).build())
                .post(jsonBody(body))
                .build();
        return execJson(req);
    }

    // ---------- helpers ----------

    private HttpUrl.Builder url(String path) {
        HttpUrl parsed = HttpUrl.parse(baseUrl + path);
        if (parsed == null) throw new IllegalArgumentException("Invalid URL: " + baseUrl + path);
        return parsed.newBuilder();
    }

    private RequestBody jsonBody(Object body) {
        try {
            return RequestBody.create(json.writeValueAsString(body), JSON_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    private JsonNode getJson(HttpUrl url) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        return execJson(req);
    }

    private JsonNode execJson(Request req) throws IOException {
        String text = execText(req);
        if (text == null || text.isEmpty()) return json.nullNode();
        return json.readTree(text);
    }

    private String execText(Request req) throws IOException {
        if (verbose) {
            System.err.println("--> " + req.method() + " " + req.url());
        }
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody body = resp.body();
            String text = body == null ? "" : body.string();
            if (verbose) {
                System.err.println("<-- " + resp.code() + " " + req.url());
                if (!text.isEmpty()) System.err.println(text);
            }
            if (!resp.isSuccessful()) {
                throw new ApiException(resp.code(), req.url().toString(), text);
            }
            return text;
        }
    }

    /** Parse a JSON array response into a list of maps. */
    public List<Map<String, Object>> toMapList(JsonNode node) {
        return json.convertValue(node, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** Parse a JSON object response into a map. */
    public Map<String, Object> toMap(JsonNode node) {
        return json.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    public static class ApiException extends IOException {
        public final int code;
        public final String url;
        public final String body;

        public ApiException(int code, String url, String body) {
            super("HTTP " + code + " " + url + (body != null && !body.isEmpty() ? ": " + body : ""));
            this.code = code;
            this.url = url;
            this.body = body;
        }
    }
}
