package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.hook.method.UrlValidator;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tencent ClawBot iLink HTTP JSON client (host = ilinkai.weixin.qq.com).
 *
 * <p><b>NOTE — reverse-engineered protocol.</b> The iLink-for-custom-backend protocol is
 * community-reverse-engineered, not officially documented by Tencent. Field names / flows
 * here reflect the best current understanding and may drift. End-to-end correctness can only
 * be verified against a live WeChat ClawBot scan (cannot be exercised in CI). All parsing is
 * defensive: missing fields / {@code ret != 0} surface as typed errors rather than NPEs.
 *
 * <p>Covers (slice 1): QR login (get_bot_qrcode + get_qrcode_status), long-poll inbound
 * (getupdates), and outbound text (sendmessage). File/media send (getuploadurl + AES-128-ECB
 * + CDN, item type 4) is DEFERRED — see {@link #sendFileDeferred}.
 */
@Component
public class WeixinIlinkClient {

    private static final Logger log = LoggerFactory.getLogger(WeixinIlinkClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** Default iLink host; overridable per-config via {@code baseurl} once login confirms. */
    static final String DEFAULT_HOST = "https://ilinkai.weixin.qq.com";
    static final String CHANNEL_VERSION = "1.0.2";
    static final int BOT_TYPE_CUSTOM = 3;

    /** iLink item_list element type for plain text. */
    static final int ITEM_TYPE_TEXT = 1;
    /** message_type=2 / message_state=2 are the documented constants for an outbound bot text reply. */
    static final int OUT_MESSAGE_TYPE = 2;
    static final int OUT_MESSAGE_STATE = 2;

    private final OkHttpClient http;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public WeixinIlinkClient(ObjectMapper objectMapper) {
        this(objectMapper, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                // getupdates long-poll holds ~35s server-side; give generous headroom.
                .readTimeout(Duration.ofSeconds(60))
                .build());
    }

    /** Test seam: inject a mock OkHttpClient. */
    WeixinIlinkClient(ObjectMapper objectMapper, OkHttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    // ---------------------------------------------------------------------
    // QR login
    // ---------------------------------------------------------------------

    /**
     * Request a fresh login QR code. No bot_token yet at this stage (pre-bind), so the
     * Authorization header is sent empty — only the host + content-type matter here.
     *
     * @return {@code {qrcode, qrcode_img_content}}
     */
    public QrCode getBotQrcode(String baseurl) {
        String url = host(baseurl) + "/ilink/bot/get_bot_qrcode?bot_type=" + BOT_TYPE_CUSTOM;
        Request req = authedRequest(url, "")
                .get()
                .build();
        JsonNode root = executeJson(req, "get_bot_qrcode");
        String qrcode = root.path("qrcode").asText("");
        String img = root.path("qrcode_img_content").asText("");
        if (qrcode.isBlank()) {
            throw new WeixinIlinkException("get_bot_qrcode returned empty qrcode");
        }
        return new QrCode(qrcode, img);
    }

    /**
     * Poll login status for a pending QR code. The server long-holds this call (~35s) until
     * the scan is confirmed / cancelled / times out, so callers should loop.
     *
     * @return current status; on {@code confirmed} carries bot_token + baseurl.
     */
    public QrStatus getQrcodeStatus(String qrcode, String baseurl) {
        String url = host(baseurl) + "/ilink/bot/get_qrcode_status?qrcode="
                + java.net.URLEncoder.encode(qrcode, StandardCharsets.UTF_8);
        Request req = authedRequest(url, "")
                .get()
                .build();
        JsonNode root = executeJson(req, "get_qrcode_status");
        String status = root.path("status").asText("pending");
        String botToken = root.path("bot_token").asText(null);
        String resolvedBaseurl = root.path("baseurl").asText(null);
        return new QrStatus(status, botToken, resolvedBaseurl);
    }

    // ---------------------------------------------------------------------
    // Inbound long-poll
    // ---------------------------------------------------------------------

    /**
     * Long-poll for inbound messages. The cursor ({@code get_updates_buf}) MUST be persisted and
     * fed back on the next call so the server resumes from the last delivered message and we don't
     * re-receive (dedup storm).
     *
     * @param cursor    last persisted {@code get_updates_buf}; empty string on first call.
     * @param botToken  bound bot token.
     * @param baseurl   config baseurl (nullable → default host).
     */
    public GetUpdatesResult getUpdates(String cursor, String botToken, String baseurl) {
        return getUpdates(cursor, botToken, baseurl, null);
    }

    /**
     * As {@link #getUpdates(String, String, String)}, but registers the in-flight {@link Call} into
     * {@code cancelHandle} so a caller (the long-poll connector) can {@code cancel()} the blocking
     * read on stop — {@code Thread.interrupt()} alone does not unblock OkHttp's socket read.
     */
    public GetUpdatesResult getUpdates(String cursor, String botToken, String baseurl,
                                       CancelHandle cancelHandle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("get_updates_buf", cursor == null ? "" : cursor);
        body.put("base_info", Map.of("channel_version", CHANNEL_VERSION));

        Request req = authedRequest(host(baseurl) + "/ilink/bot/getupdates", botToken)
                .post(jsonBody(body))
                .build();
        JsonNode root = executeJson(req, "getupdates", cancelHandle);
        int ret = root.path("ret").asInt(0);
        if (ret != 0) {
            throw new WeixinIlinkException("getupdates ret=" + ret
                    + " msg=" + root.path("errmsg").asText(""));
        }
        // Advance cursor: prefer the server's new buf; fall back to the old one so we never
        // reset to "" (which would replay history).
        String newCursor = root.path("get_updates_buf").asText(cursor == null ? "" : cursor);
        long timeoutMs = root.path("longpolling_timeout_ms").asLong(35_000L);
        JsonNode msgs = root.path("msgs");
        return new GetUpdatesResult(newCursor, timeoutMs, msgs);
    }

    // ---------------------------------------------------------------------
    // Outbound text
    // ---------------------------------------------------------------------

    /**
     * Send a plain-text reply. {@code contextToken} MUST be the echo of the inbound message's
     * context_token — without it the reply is not associated with the originating conversation
     * window (INV-2).
     */
    public void sendText(String toUserId, String contextToken, String text,
                         String botToken, String baseurl) {
        Map<String, Object> textItem = Map.of("text", text == null ? "" : text);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", ITEM_TYPE_TEXT);
        item.put("text_item", textItem);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("to_user_id", toUserId);
        msg.put("message_type", OUT_MESSAGE_TYPE);
        msg.put("message_state", OUT_MESSAGE_STATE);
        msg.put("context_token", contextToken == null ? "" : contextToken);
        msg.put("item_list", List.of(item));

        Map<String, Object> body = Map.of("msg", msg);

        Request req = authedRequest(host(baseurl) + "/ilink/bot/sendmessage", botToken)
                .post(jsonBody(body))
                .build();
        JsonNode root = executeJson(req, "sendmessage");
        int ret = root.path("ret").asInt(0);
        if (ret != 0) {
            throw new WeixinIlinkException("sendmessage ret=" + ret
                    + " msg=" + root.path("errmsg").asText(""));
        }
    }

    /**
     * DEFERRED (slice 1): file/image send via getuploadurl + AES-128-ECB encrypt + CDN PUT +
     * sendmessage item type 4. Implemented in a later slice once the media crypto/CDN flow is
     * validated against a live endpoint.
     */
    public void sendFileDeferred(String toUserId, String contextToken, byte[] fileBytes,
                                 String fileName, String botToken, String baseurl) {
        // TODO(WECHAT-CHANNEL slice 2): getuploadurl → AES-128-ECB(fileBytes) → PUT CDN →
        // sendmessage item_list[{type:4, file_item:{aes_key, cdn_url, ...}}]. Requires live
        // testing of the AES/CDN handshake (cannot be verified in CI).
        throw new UnsupportedOperationException("weixin file send not implemented (slice 1)");
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Resolve the request host. The {@code baseurl} originates from the (reverse-engineered,
     * untrusted) get_qrcode_status response and is stored in config, so it is SSRF-validated
     * (BUG-33 {@link UrlValidator}) before use — every request carries the bot_token, so a spoofed
     * baseurl could pivot to internal/metadata addresses and leak the token. On any validation
     * error we log and fall back to {@link #DEFAULT_HOST} rather than hitting the bad host.
     */
    private String host(String baseurl) {
        if (baseurl == null || baseurl.isBlank()) {
            return DEFAULT_HOST;
        }
        String trimmed = baseurl.trim();
        String error = UrlValidator.validate(trimmed);
        if (error != null) {
            log.warn("weixin baseurl rejected by SSRF guard ({}), falling back to default host", error);
            return DEFAULT_HOST;
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Build a request with the 4 iLink auth headers. {@code X-WECHAT-UIN} is regenerated per
     * request = base64(String(randomUint32())). The bot token is never logged.
     */
    private Request.Builder authedRequest(String url, String botToken) {
        return new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", newWechatUin())
                .header("Authorization", "Bearer " + (botToken == null ? "" : botToken));
    }

    /** base64(String(randomUint32())) — changes per request. */
    static String newWechatUin() {
        long uint32 = ThreadLocalRandom.current().nextLong(0L, 1L << 32);
        return Base64.getEncoder()
                .encodeToString(Long.toString(uint32).getBytes(StandardCharsets.UTF_8));
    }

    private RequestBody jsonBody(Map<String, Object> body) {
        try {
            return RequestBody.create(objectMapper.writeValueAsString(body), JSON);
        } catch (Exception e) {
            throw new WeixinIlinkException("failed to serialize iLink request body", e);
        }
    }

    private JsonNode executeJson(Request req, String op) {
        return executeJson(req, op, null);
    }

    private JsonNode executeJson(Request req, String op, CancelHandle cancelHandle) {
        Call call = http.newCall(req);
        if (cancelHandle != null) {
            cancelHandle.set(call);
        }
        try (Response resp = call.execute()) {
            ResponseBody rb = resp.body();
            String bodyText = rb != null ? rb.string() : "";
            if (!resp.isSuccessful()) {
                if (resp.code() == 401 || resp.code() == 403) {
                    throw new WeixinIlinkException(
                            "iLink " + op + " auth rejected (http " + resp.code()
                                    + ") — bot_token may be expired, re-scan required");
                }
                throw new WeixinIlinkException("iLink " + op + " http " + resp.code());
            }
            if (bodyText.isBlank()) {
                throw new WeixinIlinkException("iLink " + op + " returned empty body");
            }
            return objectMapper.readTree(bodyText);
        } catch (IOException e) {
            throw new WeixinIlinkException("iLink " + op + " IO error: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // Result records
    // ---------------------------------------------------------------------

    public record QrCode(String qrcode, String qrcodeImgContent) {}

    public record QrStatus(String status, String botToken, String baseurl) {
        public boolean confirmed() {
            return "confirmed".equalsIgnoreCase(status);
        }
    }

    public record GetUpdatesResult(String cursor, long longpollingTimeoutMs, JsonNode msgs) {}

    /**
     * Holds the current in-flight {@link Call} so the connector can cancel a blocking long-poll on
     * stop (interrupt alone won't unblock the socket read).
     */
    public static final class CancelHandle {
        private volatile Call call;

        void set(Call call) {
            this.call = call;
        }

        /** Cancel the in-flight call, if any. */
        public void cancel() {
            Call c = call;
            if (c != null) {
                c.cancel();
            }
        }
    }

    /** Typed iLink error (token expiry / ret!=0 / transport). */
    public static class WeixinIlinkException extends RuntimeException {
        public WeixinIlinkException(String message) {
            super(message);
        }

        public WeixinIlinkException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
