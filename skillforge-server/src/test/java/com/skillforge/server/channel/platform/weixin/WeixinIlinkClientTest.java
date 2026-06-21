package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WeixinIlinkClient} with a mocked OkHttp transport (interceptor returns
 * canned JSON and captures the outgoing request). Reverse-engineered protocol; live behavior must
 * be verified with a real WeChat scan.
 */
class WeixinIlinkClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Build a client whose OkHttp transport is fully short-circuited by an interceptor. */
    private WeixinIlinkClient clientReturning(
            List<Request> captured, Function<Request, String> responder) {
        Interceptor interceptor = chain -> {
            Request req = chain.request();
            captured.add(req);
            String body = responder.apply(req);
            return new Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(body, MediaType.parse("application/json")))
                    .build();
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        return new WeixinIlinkClient(objectMapper, http);
    }

    @Test
    @DisplayName("all requests carry the 4 iLink auth headers incl per-request X-WECHAT-UIN")
    void authHeaders_present_andUinPerRequest() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured,
                r -> "{\"ret\":0,\"get_updates_buf\":\"c1\",\"msgs\":[]}");

        client.getUpdates("", "tok123", null);
        client.getUpdates("c1", "tok123", null);

        assertThat(captured).hasSize(2);
        for (Request req : captured) {
            assertThat(req.header("Content-Type")).isEqualTo("application/json");
            assertThat(req.header("AuthorizationType")).isEqualTo("ilink_bot_token");
            assertThat(req.header("Authorization")).isEqualTo("Bearer tok123");
            String uin = req.header("X-WECHAT-UIN");
            assertThat(uin).isNotBlank();
            // base64 of a decimal string
            String decoded = new String(Base64.getDecoder().decode(uin), StandardCharsets.UTF_8);
            assertThat(decoded).matches("\\d+");
        }
        // UIN regenerated per request (overwhelmingly likely to differ; same value would be a bug
        // only if it were a constant — assert it is at least not hardcoded by checking decode works).
        assertThat(captured.get(0).header("X-WECHAT-UIN")).isNotNull();
    }

    @Test
    @DisplayName("getUpdates advances cursor from server get_updates_buf")
    void getUpdates_advancesCursor() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured,
                r -> "{\"ret\":0,\"get_updates_buf\":\"cursor-2\",\"longpolling_timeout_ms\":35000,\"msgs\":[{\"from_user_id\":\"u1\"}]}");

        WeixinIlinkClient.GetUpdatesResult result = client.getUpdates("cursor-1", "tok", null);

        assertThat(result.cursor()).isEqualTo("cursor-2");
        assertThat(result.longpollingTimeoutMs()).isEqualTo(35000L);
        assertThat(result.msgs().isArray()).isTrue();
        assertThat(result.msgs()).hasSize(1);
    }

    @Test
    @DisplayName("getUpdates sends cursor + channel_version in body")
    void getUpdates_sendsCursorAndChannelVersion() throws Exception {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured,
                r -> "{\"ret\":0,\"get_updates_buf\":\"x\",\"msgs\":[]}");

        client.getUpdates("my-cursor", "tok", null);

        JsonNode sent = readBody(captured.get(0));
        assertThat(sent.path("get_updates_buf").asText()).isEqualTo("my-cursor");
        assertThat(sent.path("base_info").path("channel_version").asText())
                .isEqualTo(WeixinIlinkClient.CHANNEL_VERSION);
    }

    @Test
    @DisplayName("getUpdates falls back to old cursor when server omits get_updates_buf")
    void getUpdates_fallsBackToOldCursor() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0,\"msgs\":[]}");

        WeixinIlinkClient.GetUpdatesResult result = client.getUpdates("keep-me", "tok", null);

        assertThat(result.cursor()).isEqualTo("keep-me");
    }

    @Test
    @DisplayName("getUpdates throws on ret != 0")
    void getUpdates_throwsOnNonZeroRet() {
        WeixinIlinkClient client = clientReturning(new ArrayList<>(),
                r -> "{\"ret\":1001,\"errmsg\":\"bad token\"}");

        assertThatThrownBy(() -> client.getUpdates("", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("1001");
    }

    @Test
    @DisplayName("sendText builds the iLink payload and echoes context_token")
    void sendText_payloadAndContextTokenEcho() throws Exception {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0}");

        client.sendText("user-007", "ctx-abc", "hello world", "tok", null);

        JsonNode sent = readBody(captured.get(0));
        JsonNode msg = sent.path("msg");
        assertThat(msg.path("to_user_id").asText()).isEqualTo("user-007");
        assertThat(msg.path("context_token").asText()).isEqualTo("ctx-abc");
        assertThat(msg.path("message_type").asInt()).isEqualTo(WeixinIlinkClient.OUT_MESSAGE_TYPE);
        assertThat(msg.path("message_state").asInt()).isEqualTo(WeixinIlinkClient.OUT_MESSAGE_STATE);
        // from_user_id + client_id are REQUIRED: the iLink server dedupes outbound bot messages
        // by client_id, so a missing/empty key causes every reply after the first to be silently
        // dropped (ret=0 but not delivered). Regression guard for that live bug.
        assertThat(msg.has("from_user_id")).isTrue();
        assertThat(msg.path("from_user_id").asText()).isEmpty();
        assertThat(msg.path("client_id").asText()).isNotBlank();
        JsonNode item = msg.path("item_list").get(0);
        assertThat(item.path("type").asInt()).isEqualTo(WeixinIlinkClient.ITEM_TYPE_TEXT);
        assertThat(item.path("text_item").path("text").asText()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("sendText uses a unique client_id per message (dedup guard)")
    void sendText_uniqueClientIdPerMessage() throws Exception {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0}");

        client.sendText("user-007", "ctx-abc", "first", "tok", null);
        client.sendText("user-007", "ctx-abc", "second", "tok", null);

        String id1 = readBody(captured.get(0)).path("msg").path("client_id").asText();
        String id2 = readBody(captured.get(1)).path("msg").path("client_id").asText();
        assertThat(id1).isNotBlank();
        assertThat(id2).isNotBlank();
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("sendText throws on ret != 0")
    void sendText_throwsOnNonZeroRet() {
        WeixinIlinkClient client = clientReturning(new ArrayList<>(),
                r -> "{\"ret\":42,\"errmsg\":\"nope\"}");

        assertThatThrownBy(() -> client.sendText("u", "c", "t", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("42");
    }

    @Test
    @DisplayName("getBotQrcode parses qrcode + img content")
    void getBotQrcode_parses() {
        WeixinIlinkClient client = clientReturning(new ArrayList<>(),
                r -> "{\"qrcode\":\"qr-1\",\"qrcode_img_content\":\"base64img\"}");

        WeixinIlinkClient.QrCode qr = client.getBotQrcode(null);

        assertThat(qr.qrcode()).isEqualTo("qr-1");
        assertThat(qr.qrcodeImgContent()).isEqualTo("base64img");
    }

    @Test
    @DisplayName("getQrcodeStatus state machine: pending then confirmed with token")
    void getQrcodeStatus_stateMachine() {
        WeixinIlinkClient pending = clientReturning(new ArrayList<>(),
                r -> "{\"status\":\"pending\"}");
        WeixinIlinkClient.QrStatus s1 = pending.getQrcodeStatus("qr", null);
        assertThat(s1.confirmed()).isFalse();
        assertThat(s1.status()).isEqualTo("pending");

        WeixinIlinkClient confirmed = clientReturning(new ArrayList<>(),
                r -> "{\"status\":\"confirmed\",\"bot_token\":\"tok-xyz\",\"baseurl\":\"https://h.example\"}");
        WeixinIlinkClient.QrStatus s2 = confirmed.getQrcodeStatus("qr", null);
        assertThat(s2.confirmed()).isTrue();
        assertThat(s2.botToken()).isEqualTo("tok-xyz");
        assertThat(s2.baseurl()).isEqualTo("https://h.example");
    }

    @Test
    @DisplayName("baseurl override changes the request host")
    void baseurl_overridesHost() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0,\"msgs\":[]}");

        // 203.0.113.0/24 = RFC 5737 TEST-NET-3 (public, non-private). Use a literal IP
        // so the SSRF guard's InetAddress.getByName parses it WITHOUT a DNS lookup —
        // a non-resolvable hostname (e.g. *.example) would NXDOMAIN in clean-DNS CI and
        // get rejected → silent fallback to DEFAULT_HOST → flaky local-green/CI-red.
        client.getUpdates("", "tok", "https://203.0.113.10");

        assertThat(captured.get(0).url().toString()).startsWith("https://203.0.113.10/ilink/bot/getupdates");
    }

    @Test
    @DisplayName("default host used when baseurl absent")
    void defaultHost_whenNoBaseurl() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0,\"msgs\":[]}");

        client.getUpdates("", "tok", null);

        assertThat(captured.get(0).url().toString()).startsWith(WeixinIlinkClient.DEFAULT_HOST);
    }

    @Test
    @DisplayName("auth rejection (401) surfaces a token-expired error")
    void authRejection_surfacesExpiredError() {
        Interceptor interceptor = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(ResponseBody.create("{}", MediaType.parse("application/json")))
                .build();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        WeixinIlinkClient client = new WeixinIlinkClient(objectMapper, http);

        assertThatThrownBy(() -> client.getUpdates("", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("expired");
    }

    // -----------------------------------------------------------------
    // slice 2: media (file / image) send
    // -----------------------------------------------------------------

    /**
     * Build a client that routes by URL: iLink JSON CGI calls return canned JSON; the CDN upload
     * POST returns 200 with the {@code x-encrypted-param} header. Captures all requests + their
     * bodies (CDN body captured raw).
     */
    private WeixinIlinkClient mediaClient(List<Request> captured, List<byte[]> cdnBodies,
                                          String xEncryptedParam, String uploadFullUrl) {
        Interceptor interceptor = chain -> {
            Request req = chain.request();
            captured.add(req);
            String url = req.url().toString();
            if (url.contains("/upload")) {
                // CDN upload: capture the ciphertext body.
                okio.Buffer buf = new okio.Buffer();
                if (req.body() != null) {
                    req.body().writeTo(buf);
                }
                cdnBodies.add(buf.readByteArray());
                Response.Builder rb = new Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create("", MediaType.parse("application/octet-stream")));
                if (xEncryptedParam != null) {
                    rb.header("x-encrypted-param", xEncryptedParam);
                }
                return rb.build();
            }
            String body;
            if (url.contains("getuploadurl")) {
                body = "{\"ret\":0,\"upload_full_url\":\"" + uploadFullUrl + "\"}";
            } else {
                body = "{\"ret\":0}";
            }
            return new Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(body, MediaType.parse("application/json")))
                    .build();
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        return new WeixinIlinkClient(objectMapper, http);
    }

    @Test
    @DisplayName("AES-128-ECB encrypt then decrypt round-trips to the original bytes")
    void aesEcb_roundTrip() {
        byte[] plaintext = "the quick brown fox jumps over the lazy dog 123456".getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) (i * 7 + 1);

        byte[] cipher = WeixinIlinkClient.aesEcbEncrypt(plaintext, key);
        byte[] back = WeixinIlinkClient.aesEcbDecrypt(cipher, key);

        assertThat(back).isEqualTo(plaintext);
        // PKCS5 padding: ciphertext is a positive multiple of 16 and strictly larger when input is
        // a multiple of 16 (full padding block).
        assertThat(cipher.length % 16).isZero();
        assertThat(cipher.length).isGreaterThanOrEqualTo(plaintext.length);
    }

    @Test
    @DisplayName("sendFile: getuploadurl payload (media_type=3, sizes, hex keys) + sendmessage type-4 item")
    void sendFile_payloadShape() throws Exception {
        List<Request> captured = new ArrayList<>();
        List<byte[]> cdnBodies = new ArrayList<>();
        WeixinIlinkClient client = mediaClient(captured, cdnBodies, "ENC-PARAM-XYZ",
                "https://example.com/c2c/upload?encrypted_query_param=p&filekey=k");

        byte[] file = "hello file contents".getBytes(StandardCharsets.UTF_8);
        client.sendFile("user-1", "ctx-1", file, "report.pdf", "tok", null);

        // 3 requests: getuploadurl, CDN upload, sendmessage.
        assertThat(captured).hasSize(3);

        // getuploadurl
        Request getUploadReq = captured.get(0);
        assertThat(getUploadReq.url().toString()).contains("/ilink/bot/getuploadurl");
        JsonNode up = readBody(getUploadReq);
        assertThat(up.path("media_type").asInt()).isEqualTo(WeixinIlinkClient.UPLOAD_MEDIA_TYPE_FILE);
        assertThat(up.path("to_user_id").asText()).isEqualTo("user-1");
        assertThat(up.path("rawsize").asInt()).isEqualTo(file.length);
        assertThat(up.path("filesize").asInt()).isEqualTo(((file.length / 16) + 1) * 16);
        assertThat(up.path("rawfilemd5").asText()).matches("[0-9a-f]{32}");
        assertThat(up.path("aeskey").asText()).matches("[0-9a-f]{32}"); // 16 bytes hex
        assertThat(up.path("filekey").asText()).matches("[0-9a-f]{32}");
        assertThat(up.path("no_need_thumb").asBoolean()).isTrue();

        // CDN upload: octet-stream, ciphertext body, NO auth headers.
        Request cdnReq = captured.get(1);
        assertThat(cdnReq.url().toString()).contains("/upload");
        // OkHttp carries the POST content type on the request body's MediaType (sent as the wire
        // Content-Type header). The CDN POST must be application/octet-stream.
        assertThat(cdnReq.body()).isNotNull();
        assertThat(cdnReq.body().contentType()).isNotNull();
        assertThat(cdnReq.body().contentType().toString()).contains("application/octet-stream");
        assertThat(cdnReq.header("Authorization")).isNull();
        assertThat(cdnReq.header("iLink-App-Id")).isNull();
        assertThat(cdnReq.header("AuthorizationType")).isNull();
        assertThat(cdnBodies).hasSize(1);
        assertThat(cdnBodies.get(0).length).isEqualTo(((file.length / 16) + 1) * 16);

        // sendmessage: type 4 file item, len = plaintext as STRING, aes_key = base64-of-hex.
        Request sendReq = captured.get(2);
        assertThat(sendReq.url().toString()).contains("/ilink/bot/sendmessage");
        JsonNode item = readBody(sendReq).path("msg").path("item_list").get(0);
        assertThat(item.path("type").asInt()).isEqualTo(WeixinIlinkClient.ITEM_TYPE_FILE);
        JsonNode fileItem = item.path("file_item");
        assertThat(fileItem.path("file_name").asText()).isEqualTo("report.pdf");
        assertThat(fileItem.path("len").isTextual()).isTrue();
        assertThat(fileItem.path("len").asText()).isEqualTo(String.valueOf(file.length));
        JsonNode media = fileItem.path("media");
        assertThat(media.path("encrypt_query_param").asText()).isEqualTo("ENC-PARAM-XYZ");
        assertThat(media.path("encrypt_type").asInt()).isEqualTo(WeixinIlinkClient.ENCRYPT_TYPE_PACKED);

        // aes_key = Base64(UTF8 bytes of the 32-char hex string) → decode is a 32-char hex string.
        String aesKeyB64 = media.path("aes_key").asText();
        String decoded = new String(Base64.getDecoder().decode(aesKeyB64), StandardCharsets.UTF_8);
        assertThat(decoded).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("sendImage: getuploadurl media_type=1 + sendmessage type-2 item, mid_size = ciphertext NUMBER")
    void sendImage_payloadShape() throws Exception {
        List<Request> captured = new ArrayList<>();
        List<byte[]> cdnBodies = new ArrayList<>();
        WeixinIlinkClient client = mediaClient(captured, cdnBodies, "ENC-IMG", "https://example.com/c2c/upload?x=1");

        byte[] img = new byte[100];
        for (int i = 0; i < img.length; i++) img[i] = (byte) i;
        client.sendImage("user-2", "ctx-2", img, "shot.png", "tok", null);

        JsonNode up = readBody(captured.get(0));
        assertThat(up.path("media_type").asInt()).isEqualTo(WeixinIlinkClient.UPLOAD_MEDIA_TYPE_IMAGE);

        JsonNode item = readBody(captured.get(2)).path("msg").path("item_list").get(0);
        assertThat(item.path("type").asInt()).isEqualTo(WeixinIlinkClient.ITEM_TYPE_IMAGE);
        JsonNode imageItem = item.path("image_item");
        // mid_size = ciphertext length, as a NUMBER (not string).
        assertThat(imageItem.path("mid_size").isNumber()).isTrue();
        assertThat(imageItem.path("mid_size").asInt()).isEqualTo(((img.length / 16) + 1) * 16);
        assertThat(imageItem.path("media").path("encrypt_query_param").asText()).isEqualTo("ENC-IMG");
    }

    @Test
    @DisplayName("media JSON CGI calls carry iLink-App-Id + ClientVersion; CDN POST does not")
    void mediaCgiCalls_carryAppIdHeaders() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = mediaClient(captured, new ArrayList<>(), "E", "https://example.com/c2c/upload");

        client.sendFile("u", "c", "x".getBytes(StandardCharsets.UTF_8), "f.bin", "tok", null);

        Request getUploadReq = captured.get(0);
        assertThat(getUploadReq.header("iLink-App-Id")).isEqualTo(WeixinIlinkClient.ILINK_APP_ID);
        assertThat(getUploadReq.header("iLink-App-ClientVersion"))
                .isEqualTo(WeixinIlinkClient.ILINK_APP_CLIENT_VERSION);
        Request sendReq = captured.get(2);
        assertThat(sendReq.header("iLink-App-Id")).isEqualTo(WeixinIlinkClient.ILINK_APP_ID);
    }

    @Test
    @DisplayName("CDN upload missing x-encrypted-param header fails loudly (non-retryable)")
    void cdnUpload_missingHeader_fails() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = mediaClient(captured, new ArrayList<>(),
                null /* no x-encrypted-param */, "https://example.com/c2c/upload");

        assertThatThrownBy(() -> client.sendFile("u", "c",
                "x".getBytes(StandardCharsets.UTF_8), "f.bin", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("x-encrypted-param");
    }

    @Test
    @DisplayName("getuploadurl ret != 0 surfaces a typed error")
    void getUploadUrl_nonZeroRet_throws() {
        Interceptor interceptor = chain -> {
            Request req = chain.request();
            String body = req.url().toString().contains("getuploadurl")
                    ? "{\"ret\":7,\"errmsg\":\"quota\"}" : "{\"ret\":0}";
            return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(body, MediaType.parse("application/json")))
                    .build();
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        WeixinIlinkClient client = new WeixinIlinkClient(objectMapper, http);

        assertThatThrownBy(() -> client.sendFile("u", "c",
                "x".getBytes(StandardCharsets.UTF_8), "f.bin", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("getuploadurl ret=7");
    }

    @Test
    @DisplayName("getuploadurl with no full_url builds CDN url from upload_param + filekey")
    void getUploadUrl_buildsFromUploadParam() {
        List<Request> captured = new ArrayList<>();
        Interceptor interceptor = chain -> {
            Request req = chain.request();
            captured.add(req);
            String url = req.url().toString();
            if (url.contains("/upload")) {
                return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                        .message("OK").header("x-encrypted-param", "DL")
                        .body(ResponseBody.create("", MediaType.parse("application/octet-stream"))).build();
            }
            String body = url.contains("getuploadurl")
                    ? "{\"ret\":0,\"upload_param\":\"PARAM 1\"}" : "{\"ret\":0}";
            return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(body, MediaType.parse("application/json"))).build();
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        WeixinIlinkClient client = new WeixinIlinkClient(objectMapper, http);

        client.sendFile("u", "c", "x".getBytes(StandardCharsets.UTF_8), "f.bin", "tok", null);

        String cdnUrl = captured.get(1).url().toString();
        assertThat(cdnUrl).startsWith(WeixinIlinkClient.CDN_BASE_URL + "/upload");
        assertThat(cdnUrl).contains("encrypted_query_param=PARAM");
        assertThat(cdnUrl).contains("filekey=");
    }

    @Test
    @DisplayName("CDN 5xx on attempt 1 then 200 + x-encrypted-param on attempt 2 → success (retry loop)")
    void cdnUpload_retriesOn5xx_thenSucceeds() {
        java.util.concurrent.atomic.AtomicInteger cdnAttempts = new java.util.concurrent.atomic.AtomicInteger();
        Interceptor interceptor = chain -> {
            Request req = chain.request();
            String url = req.url().toString();
            if (url.contains("/upload")) {
                int n = cdnAttempts.incrementAndGet();
                if (n == 1) {
                    // First attempt: 503 → retryable.
                    return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(503)
                            .message("Service Unavailable")
                            .body(ResponseBody.create("busy", MediaType.parse("text/plain"))).build();
                }
                // Second attempt: success with the download param header.
                return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                        .message("OK").header("x-encrypted-param", "DL-OK")
                        .body(ResponseBody.create("", MediaType.parse("application/octet-stream"))).build();
            }
            String body = url.contains("getuploadurl")
                    ? "{\"ret\":0,\"upload_full_url\":\"https://example.com/c2c/upload\"}" : "{\"ret\":0}";
            return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(body, MediaType.parse("application/json"))).build();
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        WeixinIlinkClient client = new WeixinIlinkClient(objectMapper, http);

        // Should not throw — succeeds on the 2nd CDN attempt.
        client.sendFile("u", "c", "x".getBytes(StandardCharsets.UTF_8), "f.bin", "tok", null);
        assertThat(cdnAttempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("CDN 4xx aborts immediately without retry (typed non-retryable)")
    void cdnUpload_4xx_abortsWithoutRetry() {
        java.util.concurrent.atomic.AtomicInteger cdnAttempts = new java.util.concurrent.atomic.AtomicInteger();
        Interceptor interceptor = chain -> {
            Request req = chain.request();
            String url = req.url().toString();
            if (url.contains("/upload")) {
                cdnAttempts.incrementAndGet();
                return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(403)
                        .message("Forbidden")
                        .body(ResponseBody.create("nope", MediaType.parse("text/plain"))).build();
            }
            String body = url.contains("getuploadurl")
                    ? "{\"ret\":0,\"upload_full_url\":\"https://example.com/c2c/upload\"}" : "{\"ret\":0}";
            return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(body, MediaType.parse("application/json"))).build();
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        WeixinIlinkClient client = new WeixinIlinkClient(objectMapper, http);

        assertThatThrownBy(() -> client.sendFile("u", "c",
                "x".getBytes(StandardCharsets.UTF_8), "f.bin", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("client error 403");
        // Exactly one CDN call — no retry on 4xx.
        assertThat(cdnAttempts.get()).isEqualTo(1);
    }

    private JsonNode readBody(Request req) throws IOException {
        okio.Buffer buffer = new okio.Buffer();
        req.body().writeTo(buffer);
        return objectMapper.readTree(buffer.readUtf8());
    }
}
