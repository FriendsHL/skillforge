package com.skillforge.server.channel.platform.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.channel.spi.DeliveryResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feishu (Lark) tenant access token management + Interactive Card posting.
 * token refresh is synchronized (M7).
 */
@Component
public class FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String TOKEN_URL =
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String SEND_MESSAGE_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id";
    private static final String WS_ENDPOINT_URL =
            "https://open.feishu.cn/callback/ws/endpoint";

    private final OkHttpClient http;
    private final ObjectMapper objectMapper;

    /** per-appId token cache. */
    private final Map<String, TokenCache> tokenByApp = new java.util.concurrent.ConcurrentHashMap<>();

    public FeishuClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .build();
    }

    public synchronized String getAccessToken(ChannelConfigDecrypted config) {
        try {
            JsonNode creds = objectMapper.readTree(
                    config.credentialsJson() == null ? "{}" : config.credentialsJson());
            String appId = creds.path("app_id").asText();
            String appSecret = creds.path("app_secret").asText();
            if (appId.isBlank() || appSecret.isBlank()) {
                throw new IllegalStateException("feishu credentials missing app_id/app_secret");
            }
            TokenCache cache = tokenByApp.get(appId);
            if (cache != null && Instant.now().isBefore(cache.expiry.minusSeconds(60))) {
                return cache.token;
            }
            String newToken = refresh(appId, appSecret);
            tokenByApp.put(appId, new TokenCache(newToken, Instant.now().plusSeconds(7200)));
            return newToken;
        } catch (Exception e) {
            throw new RuntimeException("Feishu token fetch failed: " + e.getMessage(), e);
        }
    }

    public String testConnection(ChannelConfigDecrypted config) {
        String token = getAccessToken(config);
        if (token == null || token.isBlank()) {
            throw new RuntimeException("Feishu token is empty");
        }
        return "token_ok";
    }

    public String getWsEndpoint(ChannelConfigDecrypted config) {
        try {
            JsonNode creds = objectMapper.readTree(
                    config.credentialsJson() == null ? "{}" : config.credentialsJson());
            String appId = creds.path("app_id").asText();
            String appSecret = creds.path("app_secret").asText();
            if (appId.isBlank() || appSecret.isBlank()) {
                throw new IllegalStateException("feishu credentials missing app_id/app_secret");
            }

            Map<String, String> body = Map.of("AppID", appId, "AppSecret", appSecret);
            String payload = objectMapper.writeValueAsString(body);
            Request req = new Request.Builder()
                    .url(WS_ENDPOINT_URL)
                    .header("locale", "zh")
                    .post(RequestBody.create(payload, JSON))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                String bodyText = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new RuntimeException("http " + resp.code() + ": " + bodyText);
                }
                JsonNode json = objectMapper.readTree(bodyText);
                int code = json.path("code").asInt(-1);
                if (code != 0) {
                    throw new RuntimeException("endpoint api code=" + code + ", msg=" + json.path("msg").asText(""));
                }
                String url = json.path("data").path("url").asText("");
                if (url.isBlank()) {
                    url = json.path("data").path("ws_url").asText("");
                }
                if (url.isBlank()) {
                    url = json.path("data").path("URL").asText("");
                }
                if (url.isBlank()) {
                    throw new RuntimeException("ws endpoint is empty, response=" + bodyText);
                }
                return url;
            }
        } catch (Exception e) {
            throw new RuntimeException("Feishu ws endpoint fetch failed: " + e.getMessage(), e);
        }
    }

    private String refresh(String appId, String appSecret) throws Exception {
        Map<String, String> body = Map.of("app_id", appId, "app_secret", appSecret);
        String payload = objectMapper.writeValueAsString(body);
        Request req = new Request.Builder()
                .url(TOKEN_URL)
                .post(RequestBody.create(payload, JSON))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String bodyText = resp.body() != null ? resp.body().string() : "";
            JsonNode json = objectMapper.readTree(bodyText);
            int code = json.path("code").asInt(-1);
            if (code != 0) {
                throw new RuntimeException("feishu token endpoint returned code=" + code
                        + " msg=" + json.path("msg").asText(""));
            }
            return json.path("tenant_access_token").asText();
        }
    }

    public DeliveryResult sendInteractive(ChannelReply reply, ChannelConfigDecrypted config) {
        String token;
        try {
            token = getAccessToken(config);
        } catch (RuntimeException e) {
            return DeliveryResult.retry(0, e.getMessage());
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("elements", List.of(Map.of(
                "tag", "markdown",
                "content", reply.markdownText())));
        card.put("header", Map.of("title", Map.of(
                "tag", "plain_text",
                "content", "SkillForge")));

        Map<String, Object> sendBody = new LinkedHashMap<>();
        sendBody.put("receive_id", reply.conversationId());
        sendBody.put("msg_type", "interactive");
        try {
            sendBody.put("content", objectMapper.writeValueAsString(card));
        } catch (Exception e) {
            return DeliveryResult.failed("serialize card: " + e.getMessage());
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(sendBody);
        } catch (Exception e) {
            return DeliveryResult.failed("serialize body: " + e.getMessage());
        }

        Request req = new Request.Builder()
                .url(SEND_MESSAGE_URL)
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(payload, JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String bodyText = rb != null ? rb.string() : "";
            if (!resp.isSuccessful()) {
                long retry = parseRetryAfter(resp.header("Retry-After"));
                if (resp.code() == 429) return DeliveryResult.retry(retry, "rate limited");
                if (resp.code() >= 500) return DeliveryResult.retry(retry, "http " + resp.code());
                return DeliveryResult.failed("http " + resp.code() + ": " + bodyText);
            }
            JsonNode json = objectMapper.readTree(bodyText);
            int code = json.path("code").asInt(-1);
            if (code == 0) return DeliveryResult.ok();
            // 11215: rate limit, 11232: forbidden, others generally terminal
            if (code == 11215) return DeliveryResult.retry(1000,
                    "feishu code 11215: " + json.path("msg").asText(""));
            return DeliveryResult.failed("feishu code " + code + ": " + json.path("msg").asText(""));
        } catch (java.io.IOException e) {
            return DeliveryResult.retry(0, e.getMessage());
        }
    }

    private long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) return 0;
        try {
            return Long.parseLong(header.trim()) * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record TokenCache(String token, Instant expiry) {}
}
