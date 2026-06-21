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

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
 * <p>Covers: QR login (get_bot_qrcode + get_qrcode_status), long-poll inbound (getupdates),
 * outbound text (sendmessage), and (slice 2) outbound file/image media — see
 * {@link #sendFile} / {@link #sendImage}: getuploadurl → AES-128-ECB encrypt → CDN upload →
 * sendmessage (item type 4 file / type 2 image).
 */
@Component
public class WeixinIlinkClient {

    private static final Logger log = LoggerFactory.getLogger(WeixinIlinkClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** Default iLink host; overridable per-config via {@code baseurl} once login confirms. */
    static final String DEFAULT_HOST = "https://ilinkai.weixin.qq.com";
    static final String CHANNEL_VERSION = "1.0.2";
    static final int BOT_TYPE_CUSTOM = 3;

    /**
     * iLink-App-Id header. Mirrors {@code package.json#ilink_appid} of the reference
     * openclaw-weixin@2.4.3 client ({@code "bot"}). Sent on every JSON CGI call (getuploadurl /
     * sendmessage / getupdates / login). NOT sent on the CDN upload POST.
     */
    static final String ILINK_APP_ID = "bot";
    /**
     * iLink-App-ClientVersion header. Encoded as uint32 {@code (major<<16)|(minor<<8)|patch} from
     * the reference client version 2.4.3 → {@code (2<<16)|(4<<8)|3 = 0x020403 = 132099}. Sent on
     * every JSON CGI call.
     */
    static final String ILINK_APP_CLIENT_VERSION = "132099";

    /** CDN base for c2c media upload/download (reference: accounts.ts CDN_BASE_URL). */
    static final String CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c";

    /** UPLOAD media_type enum (getuploadurl) — NOTE: differs from the send item_list type below. */
    static final int UPLOAD_MEDIA_TYPE_IMAGE = 1;
    static final int UPLOAD_MEDIA_TYPE_FILE = 3;

    /** iLink item_list element type for plain text. */
    static final int ITEM_TYPE_TEXT = 1;
    /** item_list element type for an image (send side); ≠ UPLOAD_MEDIA_TYPE_IMAGE. */
    static final int ITEM_TYPE_IMAGE = 2;
    /** item_list element type for a generic file attachment (send side); ≠ UPLOAD_MEDIA_TYPE_FILE. */
    static final int ITEM_TYPE_FILE = 4;
    /** CDNMedia.encrypt_type=1 = packed thumbnail/mid info (matches reference for outbound media). */
    static final int ENCRYPT_TYPE_PACKED = 1;
    /** message_type=2 / message_state=2 are the documented constants for an outbound bot text reply. */
    static final int OUT_MESSAGE_TYPE = 2;
    static final int OUT_MESSAGE_STATE = 2;

    /** Max CDN upload retries on 5xx; 4xx aborts immediately (reference: cdn-upload.ts). */
    static final int CDN_UPLOAD_MAX_RETRIES = 3;
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
     *
     * <p><b>{@code client_id} is REQUIRED and MUST be unique per message.</b> The iLink server
     * deduplicates outbound bot messages by {@code client_id}; if it is omitted (treated as the
     * empty-string key), only the FIRST reply in a conversation is delivered and every subsequent
     * reply is silently dropped (sendmessage still returns {@code ret=0}). Matches the reference
     * openclaw-weixin {@code send.ts}, which sets {@code from_user_id:""} + {@code client_id} on
     * every message — same shape as {@link #sendMedia}.
     */
    public void sendText(String toUserId, String contextToken, String text,
                         String botToken, String baseurl) {
        Map<String, Object> textItem = Map.of("text", text == null ? "" : text);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", ITEM_TYPE_TEXT);
        item.put("text_item", textItem);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", newClientId());
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

    // ---------------------------------------------------------------------
    // Outbound media (file / image) — slice 2
    // ---------------------------------------------------------------------

    /**
     * Upload {@code fileBytes} to the CDN and send it as a generic file attachment (item type 4).
     *
     * @param fileName user-visible attachment name (FileItem.file_name).
     * @see #sendMedia(boolean, String, String, byte[], String, String, String) for the full flow.
     */
    public void sendFile(String toUserId, String contextToken, byte[] fileBytes,
                         String fileName, String botToken, String baseurl) {
        sendMedia(false, toUserId, contextToken, fileBytes, fileName, botToken, baseurl);
    }

    /**
     * Upload {@code fileBytes} to the CDN and send it as an image (item type 2). Use for
     * screenshots / generated charts so the recipient sees an inline image rather than a file.
     */
    public void sendImage(String toUserId, String contextToken, byte[] fileBytes,
                          String fileName, String botToken, String baseurl) {
        sendMedia(true, toUserId, contextToken, fileBytes, fileName, botToken, baseurl);
    }

    /**
     * Full media send: getuploadurl → AES-128-ECB encrypt → CDN upload → sendmessage.
     *
     * <p>Steps (reference: openclaw-weixin@2.4.3 cdn/upload.ts + messaging/send.ts):
     * <ol>
     *   <li>{@code rawsize}=plaintext length, {@code rawfilemd5}=md5(plaintext) hex; generate
     *       random 16-byte {@code aeskey} + 16-byte {@code filekey}(hex); {@code filesize}=ECB
     *       PKCS5-padded ciphertext length.</li>
     *   <li>POST {@code getuploadurl} → resolve upload URL (prefer {@code upload_full_url}, else
     *       build from {@code upload_param}+{@code filekey}).</li>
     *   <li>AES-128-ECB encrypt the whole file; POST ciphertext to the CDN URL with ONLY a
     *       {@code Content-Type: application/octet-stream} header (no bearer/app-id); read the
     *       {@code x-encrypted-param} response header.</li>
     *   <li>POST {@code sendmessage} with the file/image item.</li>
     * </ol>
     */
    private void sendMedia(boolean asImage, String toUserId, String contextToken, byte[] fileBytes,
                           String fileName, String botToken, String baseurl) {
        if (fileBytes == null) {
            throw new WeixinIlinkException("weixin media send: file bytes are null");
        }
        int rawsize = fileBytes.length;
        String rawfilemd5 = md5Hex(fileBytes);
        byte[] aesKeyBytes = randomBytes(16);
        String aesKeyHex = toHex(aesKeyBytes);
        String filekey = toHex(randomBytes(16));
        byte[] ciphertext = aesEcbEncrypt(fileBytes, aesKeyBytes);
        int filesize = ciphertext.length;

        // (1) getuploadurl
        String uploadUrl = getUploadUrl(
                asImage ? UPLOAD_MEDIA_TYPE_IMAGE : UPLOAD_MEDIA_TYPE_FILE,
                toUserId, filekey, aesKeyHex, rawsize, rawfilemd5, filesize, botToken, baseurl);

        // (2) CDN upload (no auth headers); read x-encrypted-param.
        String encryptQueryParam = uploadCiphertextToCdn(uploadUrl, ciphertext);

        // (3) sendmessage with the media item.
        // CRITICAL: aes_key MUST be Base64(UTF8 bytes of the 32-char hex string), NOT
        // Base64(raw 16 key bytes). Reference send.ts: Buffer.from(uploaded.aeskey).toString(
        // "base64") where uploaded.aeskey == aeskey.toString("hex"). Getting this wrong (e.g.
        // base64-encoding the raw key) makes the recipient unable to decrypt the media.
        String aesKeyForSend = Base64.getEncoder()
                .encodeToString(aesKeyHex.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> media = new LinkedHashMap<>();
        media.put("encrypt_query_param", encryptQueryParam);
        media.put("aes_key", aesKeyForSend);
        media.put("encrypt_type", ENCRYPT_TYPE_PACKED);

        Map<String, Object> item = new LinkedHashMap<>();
        if (asImage) {
            item.put("type", ITEM_TYPE_IMAGE);
            Map<String, Object> imageItem = new LinkedHashMap<>();
            imageItem.put("media", media);
            // image mid_size = ciphertext length (NUMBER), per reference (fileSizeCiphertext).
            imageItem.put("mid_size", filesize);
            item.put("image_item", imageItem);
        } else {
            item.put("type", ITEM_TYPE_FILE);
            Map<String, Object> fileItem = new LinkedHashMap<>();
            fileItem.put("media", media);
            fileItem.put("file_name", fileName == null ? "" : fileName);
            // file len = plaintext size as STRING, per reference (String(uploaded.fileSize)).
            fileItem.put("len", String.valueOf(rawsize));
            item.put("file_item", fileItem);
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", newClientId());
        msg.put("message_type", OUT_MESSAGE_TYPE);
        msg.put("message_state", OUT_MESSAGE_STATE);
        msg.put("context_token", contextToken == null ? "" : contextToken);
        msg.put("item_list", List.of(item));

        Map<String, Object> body = Map.of("msg", msg);
        Request req = authedRequest(host(baseurl) + "/ilink/bot/sendmessage", botToken)
                .post(jsonBody(body))
                .build();
        JsonNode root = executeJson(req, "sendmessage(media)");
        int ret = root.path("ret").asInt(0);
        if (ret != 0) {
            throw new WeixinIlinkException("sendmessage(media) ret=" + ret
                    + " msg=" + root.path("errmsg").asText(""));
        }
    }

    /**
     * POST getuploadurl and resolve the CDN upload URL. Prefers {@code upload_full_url}; falls back
     * to building {@code {CDN_BASE}/upload?encrypted_query_param=<upload_param>&filekey=<filekey>}.
     */
    private String getUploadUrl(int mediaType, String toUserId, String filekey, String aesKeyHex,
                               int rawsize, String rawfilemd5, int filesize,
                               String botToken, String baseurl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filekey", filekey);
        body.put("media_type", mediaType);
        body.put("to_user_id", toUserId);
        body.put("rawsize", rawsize);
        body.put("rawfilemd5", rawfilemd5);
        body.put("filesize", filesize);
        body.put("no_need_thumb", true);
        body.put("aeskey", aesKeyHex);
        body.put("base_info", Map.of("channel_version", CHANNEL_VERSION));

        Request req = authedRequest(host(baseurl) + "/ilink/bot/getuploadurl", botToken)
                .post(jsonBody(body))
                .build();
        JsonNode root = executeJson(req, "getuploadurl");
        int ret = root.path("ret").asInt(0);
        if (ret != 0) {
            throw new WeixinIlinkException("getuploadurl ret=" + ret
                    + " msg=" + root.path("errmsg").asText(""));
        }
        String fullUrl = root.path("upload_full_url").asText("").trim();
        if (!fullUrl.isBlank()) {
            return fullUrl;
        }
        String uploadParam = root.path("upload_param").asText("");
        if (uploadParam.isBlank()) {
            throw new WeixinIlinkException(
                    "getuploadurl returned neither upload_full_url nor upload_param");
        }
        return CDN_BASE_URL + "/upload?encrypted_query_param="
                + java.net.URLEncoder.encode(uploadParam, StandardCharsets.UTF_8)
                + "&filekey=" + java.net.URLEncoder.encode(filekey, StandardCharsets.UTF_8);
    }

    /**
     * POST the ciphertext to the CDN and return the {@code x-encrypted-param} response header
     * (= the download encrypt_query_param to put in the send item). The request carries ONLY
     * {@code Content-Type: application/octet-stream} — no bearer / app-id headers (the CDN is a
     * different host than the iLink CGI). Retries up to {@link #CDN_UPLOAD_MAX_RETRIES} on 5xx;
     * any 4xx aborts immediately.
     *
     * <p>CRITICAL: {@code x-encrypted-param} is the download param the recipient client needs; if
     * the header is missing we MUST fail loudly rather than send an item that can't be downloaded.
     */
    private String uploadCiphertextToCdn(String uploadUrl, byte[] ciphertext) {
        String validationError = UrlValidator.validate(uploadUrl);
        if (validationError != null) {
            throw new WeixinIlinkException("CDN upload URL rejected by SSRF guard: " + validationError);
        }
        WeixinIlinkException lastError = null;
        for (int attempt = 1; attempt <= CDN_UPLOAD_MAX_RETRIES; attempt++) {
            Request req = new Request.Builder()
                    .url(uploadUrl)
                    .post(RequestBody.create(ciphertext, OCTET_STREAM))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                int code = resp.code();
                if (code >= 400 && code < 500) {
                    // 4xx → non-retryable (signalled by type, not by message substring).
                    throw new NonRetryableUploadException(
                            "CDN upload client error " + code + ": " + headerOrBody(resp));
                }
                if (code != 200) {
                    // 5xx (or any non-200, non-4xx) → retryable.
                    lastError = new WeixinIlinkException(
                            "CDN upload server error " + code + ": " + headerOrBody(resp));
                    log.warn("CDN upload attempt {}/{} failed (http {})",
                            attempt, CDN_UPLOAD_MAX_RETRIES, code);
                    continue;
                }
                String encryptedParam = resp.header("x-encrypted-param");
                if (encryptedParam == null || encryptedParam.isBlank()) {
                    // Missing download param → non-retryable: the item can't be downloaded.
                    throw new NonRetryableUploadException(
                            "CDN upload response missing x-encrypted-param header");
                }
                return encryptedParam;
            } catch (NonRetryableUploadException e) {
                // 4xx / missing-header → abort immediately (typed, no string matching).
                throw e;
            } catch (IOException e) {
                lastError = new WeixinIlinkException("CDN upload IO error: " + e.getMessage(), e);
                log.warn("CDN upload attempt {}/{} IO error: {}",
                        attempt, CDN_UPLOAD_MAX_RETRIES, e.getMessage());
            }
        }
        throw lastError != null ? lastError
                : new WeixinIlinkException("CDN upload failed after " + CDN_UPLOAD_MAX_RETRIES + " attempts");
    }

    private static String headerOrBody(Response resp) {
        String h = resp.header("x-error-message");
        if (h != null && !h.isBlank()) {
            return h;
        }
        try {
            ResponseBody rb = resp.body();
            return rb != null ? rb.string() : "status " + resp.code();
        } catch (IOException e) {
            return "status " + resp.code();
        }
    }

    // ---------------------------------------------------------------------
    // Media crypto helpers (package-private for unit testing)
    // ---------------------------------------------------------------------

    /** AES-128-ECB / PKCS5Padding encrypt with the raw 16-byte key. */
    static byte[] aesEcbEncrypt(byte[] plaintext, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new WeixinIlinkException("AES-128-ECB encrypt failed: " + e.getMessage(), e);
        }
    }

    /** AES-128-ECB / PKCS5Padding decrypt — test seam for round-trip assertions. */
    static byte[] aesEcbDecrypt(byte[] ciphertext, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new WeixinIlinkException("AES-128-ECB decrypt failed: " + e.getMessage(), e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        SECURE_RANDOM.nextBytes(b);
        return b;
    }

    private static String md5Hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            throw new WeixinIlinkException("md5 failed: " + e.getMessage(), e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Per-message client id (reference: generateId("openclaw-weixin")). */
    private static String newClientId() {
        return "skillforge-weixin-" + java.util.UUID.randomUUID();
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
     * Build a JSON CGI request with the iLink auth + app-identity headers. {@code X-WECHAT-UIN} is
     * regenerated per request = base64(String(randomUint32())). {@code iLink-App-Id} +
     * {@code iLink-App-ClientVersion} mirror the reference client and are sent on every JSON call
     * (slice 1 + slice 2) for consistency — the CDN upload POST in {@link #uploadCiphertextToCdn}
     * deliberately carries NONE of these (only Content-Type: application/octet-stream). The bot
     * token is never logged.
     */
    private Request.Builder authedRequest(String url, String botToken) {
        return new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", newWechatUin())
                .header("iLink-App-Id", ILINK_APP_ID)
                .header("iLink-App-ClientVersion", ILINK_APP_CLIENT_VERSION)
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

    /**
     * CDN upload failure that must NOT be retried (4xx client error / missing x-encrypted-param).
     * Carried by type so {@link #uploadCiphertextToCdn} aborts immediately without fragile
     * message-substring matching.
     */
    static final class NonRetryableUploadException extends WeixinIlinkException {
        NonRetryableUploadException(String message) {
            super(message);
        }
    }
}
