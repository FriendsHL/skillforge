package com.skillforge.server.channel.platform.telegram;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot API client. Sends sendMessage (parse_mode=HTML) with 4096-char
 * Unicode-safe splitting.
 */
@Component
public class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_LEN = 4096;

    private final OkHttpClient http;
    private final ObjectMapper objectMapper;

    public TelegramBotClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .build();
    }

    public DeliveryResult sendMessage(ChannelReply reply, ChannelConfigDecrypted config) {
        try {
            JsonNode creds = objectMapper.readTree(
                    config.credentialsJson() == null ? "{}" : config.credentialsJson());
            String token = creds.path("bot_token").asText();
            if (token.isBlank()) {
                return DeliveryResult.failed("missing bot_token in credentials");
            }

            List<String> chunks = splitForTelegram(reply.markdownText());
            DeliveryResult last = DeliveryResult.ok();
            for (String chunk : chunks) {
                last = sendOne(token, reply.conversationId(), chunk);
                if (!last.success()) {
                    return last;
                }
            }
            return last;
        } catch (Exception e) {
            return DeliveryResult.retry(0, "telegram send failed: " + e.getMessage());
        }
    }

    private DeliveryResult sendOne(String token, String chatId, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return DeliveryResult.failed("serialize failed: " + e.getMessage());
        }

        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(payload, JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String bodyText = rb != null ? rb.string() : "";
            if (!resp.isSuccessful()) {
                long retry = parseRetryAfter(resp.header("Retry-After"));
                if (resp.code() == 429) return DeliveryResult.retry(retry, "rate limited");
                if (resp.code() >= 500) return DeliveryResult.retry(retry, "http " + resp.code());
                if (resp.code() == 401 || resp.code() == 403) {
                    return DeliveryResult.failed("http " + resp.code() + ": " + bodyText);
                }
                return DeliveryResult.failed("http " + resp.code() + ": " + bodyText);
            }
            JsonNode json = objectMapper.readTree(bodyText);
            if (json.path("ok").asBoolean(false)) {
                return DeliveryResult.ok();
            }
            int errCode = json.path("error_code").asInt(0);
            String desc = json.path("description").asText("");
            if (errCode == 429) {
                long retry = json.path("parameters").path("retry_after").asLong(1) * 1000;
                return DeliveryResult.retry(retry, "telegram 429: " + desc);
            }
            return DeliveryResult.failed("telegram " + errCode + ": " + desc);
        } catch (java.io.IOException e) {
            return DeliveryResult.retry(0, e.getMessage());
        }
    }

    /**
     * Split text at 4096 code-point boundary. Uses offsetByCodePoints so we never
     * cut a surrogate pair (e.g. emoji). Prefers newline breaks when within the last 10%.
     */
    static List<String> splitForTelegram(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            parts.add("");
            return parts;
        }
        int cpCount = text.codePointCount(0, text.length());
        if (cpCount <= MAX_LEN) {
            parts.add(text);
            return parts;
        }
        int pos = 0;
        while (pos < cpCount) {
            int end = Math.min(pos + MAX_LEN, cpCount);
            int beginIdx = text.offsetByCodePoints(0, pos);
            int endIdx = text.offsetByCodePoints(0, end);
            String slice = text.substring(beginIdx, endIdx);
            if (end < cpCount) {
                int nl = slice.lastIndexOf('\n');
                if (nl > slice.length() * 9 / 10) {
                    slice = slice.substring(0, nl);
                    end = pos + slice.codePointCount(0, slice.length());
                }
            }
            parts.add(slice);
            pos = end;
        }
        return parts;
    }

    private long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) return 0;
        try {
            return Long.parseLong(header.trim()) * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
